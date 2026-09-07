/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.sync.webdav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.data.UserDataImportCompat
import org.fcitx.fcitx5.android.data.UserDataManager
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import timber.log.Timber
import java.io.File
import java.util.zip.ZipFile

/**
 * 恢复器：把云端下载的偏好 zip 恢复到本地。
 *
 * 偏好 zip 为 UserDataManager 分区兼容格式，因此走
 * [UserDataImportCompat]（UserDataManager 分区导入 + 历史 debug 包名兼容）。
 *
 * 导入前先停止 fcitx 再写盘，避免引擎占用/读到半成品；
 * 导入成功后由调用方（UI）发重启通知并退出进程，使配置真正生效。
 *
 * 词库恢复走逐文件同步（[WebDavSyncEngine.downloadDictFiles] + [DictReload]），
 * 不在此处处理，因为拼音/自定义短语可热重载、table 类需重建进程，路径不同。
 */
object SyncRestorer {

    private const val TAG = "WebDavSync"

    /**
     * 打印 zip 条目结构（含顶层分区与 metadata.json 内容），用于定位导入失败原因。
     * 只做只读诊断，任何异常都被吞掉，不影响恢复流程。
     */
    private fun logZipContent(what: String, zipFile: File) {
        runCatching {
            Timber.i(
                "$TAG $what zip: path=${zipFile.absolutePath}, exists=${zipFile.exists()}, size=${zipFile.length()}"
            )
            ZipFile(zipFile).use { zip ->
                val names = zip.entries().toList().map { it.name }
                Timber.i("$TAG $what zip entries(${names.size}): ${names.joinToString()}")
                val meta = zip.getEntry("metadata.json")
                if (meta == null) {
                    Timber.w("$TAG $what zip has no metadata.json")
                } else {
                    val text = zip.getInputStream(meta).bufferedReader().use { it.readText() }
                    Timber.i("$TAG $what metadata.json: $text")
                }
            }
        }.onFailure {
            Timber.e("$TAG $what cannot read zip: ${it.javaClass.name}: ${it.message}")
        }
    }

    private fun logFailure(what: String, e: Throwable) {
        // 显式输出类名/消息/堆栈：ConciseTree 会丢弃 Throwable，文本里必须自带堆栈
        Timber.e(
            "$TAG $what failed: ${e.javaClass.name}, message=${e.message}, " +
                "trace=${e.stackTraceToString()}"
        )
    }

    /** 恢复偏好 zip。成功后应提示并退出进程让配置生效。返回导入元数据（含导出时间）。 */
    suspend fun restorePrefsZip(zipFile: File): Result<UserDataManager.Metadata> =
        withContext(Dispatchers.IO) {
            Timber.i("$TAG restorePrefsZip begin")
            logZipContent("prefs", zipFile)
            try {
                Timber.i("$TAG restorePrefsZip stopping fcitx ...")
                FcitxDaemon.stopFcitx()
                Timber.i("$TAG restorePrefsZip fcitx stopped, importing ...")
                val metadata = UserDataImportCompat.import(zipFile.inputStream()).getOrThrow()
                Timber.i("$TAG restorePrefsZip imported: $metadata")
                Result.success(metadata)
            } catch (e: Exception) {
                logFailure("restorePrefsZip", e)
                // 导入失败时恢复引擎运行
                FcitxDaemon.startFcitx()
                Result.failure(e)
            }
        }
}
