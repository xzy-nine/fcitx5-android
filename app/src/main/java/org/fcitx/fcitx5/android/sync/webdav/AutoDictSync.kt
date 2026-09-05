/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.sync.webdav

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.File

/**
 * 词库自动同步（事件 + 常驻驱动，不引入 WorkManager）。
 *
 * - 上传：词库变更事件（词典导入/删除/自定义短语等）经 [notifyDictChanged] 触发，
 *   去抖一段时间后在进程内自动上传（内容未变则引擎侧跳过）。
 * - 下载：应用进程启动后按固定间隔检查云端词库固定文件 mtime，有更新且键盘未激活时下载恢复。
 *
 * 两项都以 [WebDavSyncConfig.dictAutoSync] 为总开关；连接未配置时静默跳过。
 * 进程被系统回收后自然停止触发，符合“仅进程存活时自动同步”的约束。
 */
object AutoDictSync {

    private const val TAG = "AutoDictSync"
    /** 词库变更后的上传去抖时间 */
    private const val UPLOAD_DEBOUNCE_MS = 2 * 60 * 1000L
    /** 启动后首次检查延迟 */
    private const val FIRST_CHECK_DELAY_MS = 5 * 1000L
    /** 常驻进程内的周期检查间隔 */
    private const val CHECK_INTERVAL_MS = 30 * 60 * 1000L

    /** 键盘当前是否处于激活状态：激活时不做自动下载（避免打断输入）。 */
    @Volatile
    private var keyboardVisible = false

    @Volatile
    private var downloadLoopStarted = false

    /** 保护 [uploadJob]：可从任意线程通知，取消与重新排期必须串行。 */
    private val uploadLock = Any()

    private var uploadJob: Job? = null

    private val scope get() = FcitxApplication.getInstance().coroutineScope

    private val downloadDir: File by lazy {
        File(appContext.cacheDir, "webdav").apply { mkdirs() }
    }

    /** IME 服务在输入视图出现/消失时调用。 */
    fun setKeyboardVisible(visible: Boolean) {
        keyboardVisible = visible
    }

    /** 应用启动或自动开关被打开后调用，启动常驻周期下载检查。 */
    fun startDownloadLoop() {
        if (downloadLoopStarted) return
        downloadLoopStarted = true
        scope.launch {
            delay(FIRST_CHECK_DELAY_MS)
            while (isActive) {
                maybeDownloadIfNewer()
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    /** 词库变更通知：重新安排去抖后的自动上传。可在任意线程调用。 */
    fun notifyDictChanged() {
        val cfg = runCatching { WebDavSyncConfig.load() }.getOrNull() ?: return
        if (!cfg.dictAutoSync || cfg.serverUrl.isBlank()) return
        runCatching {
            synchronized(uploadLock) {
                uploadJob?.cancel()
                uploadJob = scope.launch {
                    delay(UPLOAD_DEBOUNCE_MS)
                    val current = runCatching { WebDavSyncConfig.load() }.getOrNull() ?: return@launch
                    if (!current.dictAutoSync || current.serverUrl.isBlank()) return@launch
                    WebDavSyncEngine.uploadDict(current).onFailure {
                        Timber.e(it, "$TAG auto upload failed: ${it.javaClass.simpleName}")
                    }
                }
            }
        }.onFailure {
            Timber.e(it, "$TAG schedule auto upload failed")
        }
    }

    private suspend fun maybeDownloadIfNewer() {
        val cfg = runCatching { WebDavSyncConfig.load() }.getOrNull() ?: return
        if (!cfg.dictAutoSync || cfg.serverUrl.isBlank()) return
        if (keyboardVisible) return
        val file = File(downloadDir, "dict-download.zip")
        val result = WebDavSyncEngine.downloadDictIfNewer(cfg, file)
        val downloaded = result.getOrNull()
        if (downloaded == null) {
            result.exceptionOrNull()?.let {
                Timber.e(it, "$TAG auto download failed: ${it.javaClass.simpleName}")
            }
            return
        }
        if (downloaded.message == WebDavSyncEngine.DICT_DOWNLOADED_MSG) {
            SyncRestorer.restoreDictZip(file)
                // 仅恢复成功才提交远端 mtime，失败时保留旧状态让下次自动同步重试
                .onSuccess { WebDavSyncEngine.commitDictDownloaded(downloaded.remoteMtime) }
                .onFailure { e ->
                    Timber.e(e, "$TAG auto dict restore failed: ${e.javaClass.simpleName}")
                }
        }
    }
}
