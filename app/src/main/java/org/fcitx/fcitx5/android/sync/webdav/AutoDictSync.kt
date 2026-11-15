/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.sync.webdav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.sync.webdav.AutoDictSync.applyPendingMergesAndPush
import org.fcitx.fcitx5.android.sync.webdav.AutoDictSync.notifyDictChanged
import org.fcitx.fcitx5.android.sync.webdav.AutoDictSync.stoppingForRestart
import org.fcitx.fcitx5.android.sync.webdav.AutoDictSync.syncOnce
import org.fcitx.fcitx5.android.sync.webdav.AutoDictSync.tickRunning
import org.fcitx.fcitx5.android.sync.webdav.AutoDictSync.uploadJob
import org.fcitx.fcitx5.android.utils.AppUtil
import timber.log.Timber
import kotlin.time.Duration.Companion.milliseconds

/**
 * WebDAV 自动同步（事件 + 常驻驱动，不引入 WorkManager）。
 *
 * 两条链路都以 [WebDavSyncConfig.dictAutoSync] / [WebDavSyncConfig.prefsAutoSync] 为开关；
 * 连接未配置时静默跳过。进程被系统回收后自然停止触发，符合“仅进程存活时自动同步”的约束。
 *
 * **词库（双向）**：
 * - 上传：词库变更事件经 [notifyDictChanged] 触发去抖上传；[syncOnce] 的周期 tick 另做兜底
 *   （部分改动路径没有事件通知，例如删除词库、启用/停用、自定义短语编辑），上传前先让引擎
 *   `save()` 把自学习词库落盘，否则刚学会的词还在引擎内存里。
 * - 下载：周期 tick 检查云端词库固定文件 mtime，有更新且键盘未激活时下载恢复。
 *
 * **设置（仅上传）**：见 [AutoPrefsBackup]——恢复必须重建进程，无法后台静默完成。
 */
object AutoDictSync {

    private const val TAG = "AutoDictSync"
    /** 词库变更后的上传去抖时间 */
    private const val UPLOAD_DEBOUNCE_MS = 30 * 1000L
    /** 启动后首次检查延迟 */
    private const val FIRST_CHECK_DELAY_MS = 5 * 1000L
    /** 常驻进程内的周期检查间隔 */
    private const val CHECK_INTERVAL_MS = 30 * 60 * 1000L
    /** 结束进程前对“键盘仍隐藏”的复查间隔（下载+恢复耗时较长，期间用户可能重新唤出键盘） */
    private const val RESTART_RECHECK_DELAY_MS = 1000L
    /** 重建进程前等待“已在进行中”的上传完成的上限；超时仅告警、不阻塞退出 */
    private const val WAIT_UPLOAD_TIMEOUT_MS = 60 * 1000L

    /** 键盘当前是否处于激活状态：激活时不做任何自动同步（避免打断输入、避免替换正在用的文件）。 */
    @Volatile
    private var keyboardVisible = false

    @Volatile
    private var autoSyncStarted = false

    /** 周期 tick 是否正在执行：合并并发请求（启动、开关打开、界面退到后台），避免重复网络往返。 */
    @Volatile
    private var tickRunning = false

    /** 保护 [uploadJob]：可从任意线程通知，取消与重新排期必须串行。 */
    private val uploadLock = Any()

    private var uploadJob: Job? = null

    /** 为重建进程而退出前“排空上传”置位：置位后 [notifyDictChanged] 不再排期新上传。 */
    @Volatile
    private var stoppingForRestart = false

    /** 实际传输任务：去抖任务越过延迟后启动并记录；一旦开始只等待不取消（取消会令远端写到一半）。 */
    private var transferJob: Job? = null

    private val scope get() = FcitxApplication.getInstance().coroutineScope

    /** IME 服务在输入视图出现/消失时调用。 */
    fun setKeyboardVisible(visible: Boolean) {
        keyboardVisible = visible
    }

    /** 应用启动或自动开关被打开后调用，启动常驻周期同步检查。 */
    fun startAutoSync() {
        if (autoSyncStarted) return
        autoSyncStarted = true
        scope.launch {
            // 先补做上次遗留的延后叠加：必须在引擎启动前，故不放在首个 tick 之后
            recoverPendingMerges()
            delay(FIRST_CHECK_DELAY_MS.milliseconds)
            while (isActive) {
                syncOnce()
                delay(CHECK_INTERVAL_MS.milliseconds)
            }
        }
    }

    /**
     * 补做上次遗留的延后叠加（例如退出前未能完成）。
     *
     * 仅在**引擎尚未启动**（无任何连接）时执行：引擎一旦运行，其保存会用内存内容覆写
     * `user.dict`，此时叠加会被抹掉。已有连接则跳过——登记项与暂存副本都还在磁盘上，
     * 留到下次进程重建（词库恢复需要重建时会走到 [applyPendingMergesAndPush]）再处理。
     */
    private suspend fun recoverPendingMerges() {
        if (FcitxDaemon.getFirstConnectionOrNull() != null) {
            Timber.i("$TAG skip pending merge recovery: fcitx connection already exists")
            return
        }
        val cfg = withContext(Dispatchers.IO) {
            runCatching { WebDavSyncConfig.load() }.getOrNull()
        } ?: return
        if (cfg.pendingDictMerges.isEmpty()) return
        val applied = withContext(Dispatchers.IO) {
            runCatching { PendingDictMerge.applyPending(cfg) }
                .onFailure { Timber.w(it, "$TAG recover pending merges failed") }
                .getOrDefault(emptySet())
        }
        if (applied.isEmpty()) return
        withContext(Dispatchers.IO) {
            runCatching { WebDavSyncEngine.uploadDictFiles(cfg) }
                .onFailure { Timber.e(it, "$TAG push recovered merged dict failed") }
        }
    }

    /**
     * 请求立即执行一次同步检查（不等待结果）。
     *
     * 开关刚被打开、或用户离开设置界面时调用，避免最长等一个周期才首次同步；
     * 与正在执行的 tick 合并（[tickRunning]），不会叠加并发请求。
     */
    fun requestSyncNow() {
        scope.launch { syncOnce() }
    }

    /**
     * 一次完整的自动同步：先补传本地词库改动，再拉取云端更新，最后备份设置。
     *
     * 词库部分要求键盘隐藏——自动同步绝不能打断输入，也不能在用户使用中替换词库文件
     * （table 类词库生效需要重建进程）。设置备份只是读取并上传，不做本地替换，
     * 因此不受键盘可见性限制，否则在经常唤出键盘的设备上可能长期得不到备份。
     */
    private suspend fun syncOnce() {
        if (tickRunning) return
        tickRunning = true
        try {
            if (stoppingForRestart) return
            // 读配置文件是文件 IO + ContentProvider 查询，放 IO 线程：
            // 本对象跑在主线程作用域上，直接读会占用 UI 线程。
            val cfg = withContext(Dispatchers.IO) {
                runCatching { WebDavSyncConfig.load() }.getOrNull()
            } ?: return
            if (cfg.serverUrl.isBlank()) return
            if (cfg.dictAutoSync && !keyboardVisible) {
                // 返回 false 表示已排空上传并结束进程，不再继续后续步骤
                if (!syncDict(cfg)) return
            }
            if (cfg.prefsAutoSync && !stoppingForRestart) {
                AutoPrefsBackup.backupIfChanged(cfg).onFailure {
                    Timber.e(it, "$TAG auto settings backup failed: ${it.javaClass.simpleName}")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG syncOnce failed: ${e.javaClass.simpleName}")
        } finally {
            tickRunning = false
        }
    }

    /**
     * 词库双向同步。返回 false 表示调用方应立即停止后续步骤（进程即将被重建）。
     *
     * **顺序必须是「先下载叠加、再上传」**：反向顺序（先上传后下载）会让本地把云端独有的
     * 条目覆盖掉，且上传刚更新了远端快照，随后的下载会判定“远端未变”而跳过，
     * 于是云端独有条目永久丢失。先叠加则本地已是并集，再上传即把并集推回云端。
     *
     * 首次同步（本地无同步记录）例外：此时先探测云端，云端已有备份则**只下载不上传**，
     * 避免用本地默认词库覆盖云端。
     */
    private suspend fun syncDict(cfg: WebDavSyncConfig): Boolean {
        // 上传与下载共用同一目录；权限缺失时后台弹不出权限框，静默跳过留给手动入口
        if (LocalNetworkGuard.isDenied(WebDavSyncEngine.dictDirUrl(cfg))) {
            Timber.i("$TAG auto dict sync skipped: local network permission not granted")
            return true
        }
        // 读配置文件是文件 IO + ContentProvider 查询，放 IO 线程
        val stored = withContext(Dispatchers.IO) {
            runCatching { WebDavSyncConfig.load() }.getOrNull()
        } ?: cfg
        val allowUpload = shouldUploadDict(stored)
        if (!allowUpload) {
            // 首次同步且云端已有备份：只下载叠加，绝不上传本地内容
            Timber.i("$TAG first sync: download-and-merge only, no upload")
        } else {
            // 引擎自学习词库需先落盘，否则下载叠加时会丢掉本机刚学会的词
            flushEngineState()
        }
        // 下载会覆盖/叠加本地词库文件，写盘前确认键盘已隐藏
        if (keyboardVisible) {
            Timber.i("$TAG keyboard visible, skip auto dict sync")
            return true
        }
        // 已进入“排空上传并退出”流程时不再下载（此时进程即将重建）
        if (stoppingForRestart) return false
        val result = WebDavSyncEngine.downloadDictFiles(stored)
        val outcome = result.getOrNull()
        if (outcome == null) {
            result.exceptionOrNull()?.let {
                Timber.e(it, "$TAG auto download failed: ${it.javaClass.simpleName}")
            }
            return true
        }
        Timber.i(
            "$TAG auto dict synced: downloaded=${outcome.downloaded}, " +
                "merged=${outcome.mergedRels.size}, kinds=${outcome.kinds}"
        )
        // 本地此刻已是并集（云端独有条目 + 本地独有条目），把并集推回云端，
        // 否则并集只留在本机，换设备即丢失。叠加过的文件摘要已被清空，会被重新推送。
        if (allowUpload && !stoppingForRestart) {
            WebDavSyncEngine.uploadDictFiles(stored).onFailure {
                Timber.e(it, "$TAG push merged dict failed: ${it.javaClass.simpleName}")
            }
        }
        if (outcome.downloaded == 0 && outcome.mergedRels.isEmpty()) return true
        // 拼音/自定义短语可以热重载；table 与 user.dict 需要重建进程
        DictReload.applyReloadable(outcome.kinds)
        if (DictReload.needsRestart(outcome.kinds)) {
            restartProcessWhenIdle()
            return false
        }
        return true
    }

    /**
     * 自动上传前是否允许上传本地词库。
     *
     * 本地已有同步记录 → 本地词库与云端同源，可直接上传（内容未变的文件由引擎跳过）。
     * 全新设备（无记录）→ 先探测云端：**云端已有词库备份时绝不上传**，否则会用本地默认
     * 词库覆盖云端；云端确实为空时才允许上传，使新设备能直接成为备份来源。
     * 探测失败（网络/权限）返回 false，宁可下次重试也不冒覆盖风险。
     */
    private suspend fun shouldUploadDict(cfg: WebDavSyncConfig): Boolean {
        if (cfg.dictFileDigests.isNotEmpty()) return true
        val remoteCount = WebDavSyncEngine.remoteDictFileCount(cfg)
        if (remoteCount == null) {
            Timber.i("$TAG skip auto upload: cannot verify remote dict state (first sync)")
            return false
        }
        if (remoteCount > 0) {
            Timber.i("$TAG skip auto upload: remote already has $remoteCount dict files (first sync)")
            return false
        }
        Timber.i("$TAG first sync with empty remote, allow initial dict upload")
        return true
    }

    /**
     * 让引擎把内存中的用户数据落盘（自学习词库 `pinyin/user.dict`、`user.history` 等）。
     *
     * 这些文件只在引擎自动保存（默认 30 分钟空闲）或退出时才写盘，上传前不 flush 会漏掉新词。
     * 用 [FcitxConnection.runOnReady] 而非 `runIfReady`：后者是即发即忘，会与随后的上传竞争。
     * 引擎未就绪时不等待——此时内存里也没有需要落盘的新数据。
     */
    private suspend fun flushEngineState() {
        val connection = FcitxDaemon.getFirstConnectionOrNull() ?: return
        if (!connection.peek { isReady }) return
        runCatching { connection.runOnReady { save() } }
            .onFailure { Timber.w(it, "$TAG flush engine state failed") }
    }

    /** 词库变更通知：重新安排去抖后的自动上传。可在任意线程调用。 */
    fun notifyDictChanged() {        // 正在为重建进程退出而排空上传：不再接受新的排期
        if (stoppingForRestart) return
        // 读配置文件是文件 IO + ContentProvider 查询，本函数可能被主线程调用，
        // 故整个检查放到 IO 线程，不占用 UI 线程。
        scope.launch(Dispatchers.IO) {
            val cfg = runCatching { WebDavSyncConfig.load() }.getOrNull() ?: return@launch
            if (!cfg.dictAutoSync || cfg.serverUrl.isBlank()) return@launch
            // 后台自动同步无法弹出权限框：目标为局域网且未授权时静默跳过，留给手动入口处理
            if (LocalNetworkGuard.isDenied(WebDavSyncEngine.cloudDirUrl(cfg))) {
                Timber.i("$TAG auto upload skipped: local network permission not granted")
                return@launch
            }
            scheduleAutoUpload(cfg)
        }
    }

    /** 排期一次去抖后的自动上传。调用方需已确认同步开关开启。 */
    private fun scheduleAutoUpload(cfg: WebDavSyncConfig) {
        runCatching {
            synchronized(uploadLock) {
                // 锁内二次确认，防止与“排空退出”并发时排入退出后才会执行的上传
                if (stoppingForRestart) return@synchronized
                // 已有传输在进行：保留本次上传（取消会令远端文件写到一半），不再重复排期
                if (transferJob?.isActive == true) return@synchronized
                // 仍在去抖：尚未发生任何网络写，安全取消并重新排期
                uploadJob?.cancel()
                uploadJob = scope.launch {
                    delay(UPLOAD_DEBOUNCE_MS.milliseconds)
                    // 读配置文件是文件 IO + ContentProvider 查询，放 IO 线程
                    val current = withContext(Dispatchers.IO) {
                        runCatching { WebDavSyncConfig.load() }.getOrNull()
                    } ?: return@launch
                    if (!current.dictAutoSync || current.serverUrl.isBlank()) return@launch
                    if (stoppingForRestart) return@launch
                    // 转移阶段以独立任务运行（不是 uploadJob 的子协程）：
                    // 之后的 notifyDictChanged 不能再取消它，drainUploadAndExit 只等待它完成。
                    val job = scope.launch {
                        // 引擎自学习词库需先落盘再上传
                        flushEngineState()
                        WebDavSyncEngine.uploadDictFiles(current).onFailure {
                            Timber.e(it, "$TAG auto upload failed: ${it.javaClass.simpleName}")
                        }
                    }
                    synchronized(uploadLock) { transferJob = job }
                    job.join()
                }
            }
        }.onFailure {
            Timber.e(it, "$TAG schedule auto upload failed")
        }
    }

    /**
     * 词库恢复后重建进程：引擎虽然重启成功，但 IME 与引擎之间的连接状态不会复位
     * （按键有反馈却无法上屏），只有重建进程才能恢复输入。
     *
     * 严格确保键盘已隐藏才执行，并且**只结束进程**：
     * - 恢复前已检查过一次，恢复完成时再检查一次，并延迟复查一次；
     * - 任一次发现键盘可见就放弃本次重启（输入中绝不打断，状态留给下次周期/手动恢复处理）；
     * - 不发重启通知、不启动任何界面，避免把应用拉到前台。
     */
    private suspend fun restartProcessWhenIdle() {
        if (keyboardVisible) {
            Timber.i("$TAG dict restored but keyboard visible, skip restarting process")
            return
        }
        delay(RESTART_RECHECK_DELAY_MS.milliseconds)
        if (keyboardVisible) {
            Timber.i("$TAG keyboard showed up during recheck, skip restarting process")
            return
        }
        Timber.i("$TAG dict restored, keyboard hidden, draining pending upload before exit")
        drainUploadAndExit()
    }

    /**
     * 重建进程前排空待处理上传，避免把正在 PUT 的远端文件写到一半：
     * 1. 置位 [stoppingForRestart]，[notifyDictChanged] 不再排期新上传；
     * 2. 仍处于去抖延迟的任务直接取消（尚未发生任何网络写）；
     * 3. 已开始传输的任务等待其自然完成（带超时，超时仅告警后照常退出）；
     * 4. 引擎停机后完成延后的词库叠加（`pinyin/user.dict` 只能在引擎不再写盘时合并），
     *    并把并集推回云端。
     */
    private suspend fun drainUploadAndExit() {
        stoppingForRestart = true
        val (debounce, transfer) = synchronized(uploadLock) { uploadJob to transferJob }
        // 仍在去抖的任务：尚未发生任何网络写，直接取消（不等待）
        if (debounce != null && debounce.isActive) {
            runCatching { debounce.cancel() }
                .onFailure { Timber.w(it, "$TAG cancel pending auto upload failed") }
        }
        // 已在传输的任务：只等待其自然完成（带超时，超时仅告警后照常退出），绝不取消
        if (transfer != null && transfer.isActive) {
            Timber.i("$TAG auto upload in flight, wait up to ${WAIT_UPLOAD_TIMEOUT_MS}ms")
            val joined = withTimeoutOrNull(WAIT_UPLOAD_TIMEOUT_MS.milliseconds) { transfer.join() }
            if (joined == null) {
                Timber.w("$TAG timed out waiting for auto upload, exiting anyway")
            }
        }
        runCatching { FcitxDaemon.stopFcitx() }
            .onFailure { Timber.w(it, "$TAG stopFcitx before exit failed") }
        // 引擎已停机：此刻合并它持有的 user.dict 才不会被内存内容覆写，随后把并集推回云端
        applyPendingMergesAndPush()
        AppUtil.exit()
    }

    /**
     * 完成延后的词库叠加并上传并集。仅在引擎停机后调用。
     *
     * 失败不阻塞退出：登记的待叠加项与暂存副本都留在磁盘上，下次进程重建后仍会重试。
     */
    private suspend fun applyPendingMergesAndPush() {
        // 读配置与叠加词库都是文件 IO，放 IO 线程
        val cfg = withContext(Dispatchers.IO) {
            runCatching { WebDavSyncConfig.load() }.getOrNull()
        } ?: return
        if (cfg.pendingDictMerges.isEmpty()) return
        val applied = withContext(Dispatchers.IO) {
            runCatching { PendingDictMerge.applyPending(cfg) }
                .onFailure { Timber.w(it, "$TAG apply pending merges failed") }
                .getOrDefault(emptySet())
        }
        if (applied.isEmpty()) return
        runCatching { WebDavSyncEngine.uploadDictFiles(cfg) }
            .onFailure { Timber.e(it, "$TAG push merged dict failed: ${it.javaClass.simpleName}") }
    }
}
