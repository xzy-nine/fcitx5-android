/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.sync.webdav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 引擎自持词库的**延后叠加**。
 *
 * `pinyin/user.dict` 在引擎构造时被读入内存，此后任何保存（自动保存、退出前保存）都会用
 * 内存内容整体覆写磁盘。因此运行期间就地叠加会被下一次保存抹掉，甚至随后的上传会把云端
 * 并集也覆盖回去。正确做法分两步：
 * 1. 同步时只把云端副本暂存下来（不动本地文件），并在配置里登记待处理项；
 * 2. 等引擎**停机之后**（进程重建前，或引擎已 stop 的窗口内）再与本地叠加，
 *    然后把并集上传回云端。
 */
object PendingDictMerge {

    private const val TAG = "PendingDictMerge"

    private val dataRoot: File
        get() = File(appContext.getExternalFilesDir(null)!!, DictCollector.DATA_DIR_NAME)

    /** 暂存云端副本的目录。刻意放在独立的持久子目录，避免被同步的临时目录清理掉。 */
    fun stagingDir(): File =
        File(appContext.cacheDir, "webdav/pending_merge").apply { mkdirs() }

    /**
     * 暂存一份云端副本并登记待叠加。返回是否登记成功。
     *
     * 只复制文件、不改动本地词库；真正的叠加推迟到 [applyPending]。
     */
    fun stage(rel: String, cloudCopy: File): Boolean {
        return try {
            val dest = File(stagingDir(), DictMerger.pendingFileNameFor(rel))
            Files.copy(cloudCopy.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (e: Exception) {
            Timber.w(e, "$TAG stage failed for $rel: ${e.javaClass.simpleName}")
            false
        }
    }

    /** 已登记的待叠加项（相对路径 -> 暂存副本）。文件缺失的条目会被忽略。 */
    fun pendingFiles(cfg: WebDavSyncConfig): Map<String, File> =
        cfg.pendingDictMerges.mapValues { (_, name) -> File(stagingDir(), name) }
            .filterValues { it.isFile }

    /**
     * 引擎已停机时调用：把登记的云端副本与本地叠加，清除这些文件的本地摘要
     * （使随后上传必定推送并集），并返回已处理的相对路径集合。
     *
     * 全程在 IO 线程执行（调用方可能是主线程的退出流程）。
     */
    suspend fun applyPending(cfg: WebDavSyncConfig): Set<String> = withContext(Dispatchers.IO) {
        val pending = pendingFiles(cfg)
        if (pending.isEmpty()) {
            // 登记项对应的副本已丢失：清掉登记，避免永久卡住
            if (cfg.pendingDictMerges.isNotEmpty()) {
                commit { it.copy(pendingDictMerges = emptyMap()) }
            }
            return@withContext emptySet()
        }
        val done = DictMerger.applyPending(
            pending = pending,
            dataRoot = dataRoot,
            stagingDir = stagingDir(),
            workDir = File(appContext.cacheDir, "webdav/merge_work").apply { mkdirs() }
        )
        if (done.isEmpty()) {
            Timber.w("$TAG nothing applied, keeping ${pending.size} pending item(s) for retry")
            return@withContext emptySet()
        }
        commit { stored ->
            // 清摘要 → 下一次上传必定推送并集，否则云端会停留在旧内容
            stored.copy(
                dictFileDigests = stored.dictFileDigests - done,
                pendingDictMerges = stored.pendingDictMerges - done
            )
        }
        Timber.i("$TAG applied pending merges: $done")
        done
    }

    /** 重新读取配置后只改必要字段再写回，避免覆盖其它字段。 */
    private fun commit(update: (WebDavSyncConfig) -> WebDavSyncConfig): Boolean =
        update(WebDavSyncConfig.load()).save().isSuccess
}
