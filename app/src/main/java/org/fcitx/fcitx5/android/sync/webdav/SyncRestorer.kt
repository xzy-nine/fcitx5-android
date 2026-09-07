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
 * 恢复器：把云端下载的 zip 恢复到本地。
 *
 * 偏好 zip / 词库 zip 均为 UserDataManager 分区兼容格式，因此走
 * [UserDataImportCompat]（UserDataManager 分区导入 + 历史 debug 包名兼容），
 * 天然实现“选择恢复偏好或词库”的分区级部分恢复。
 *
 * 两个方向都会先停止 fcitx 再写盘，避免引擎占用/读到半成品；
 * 偏好恢复要求随后重启进程（由 UI 通知并退出），词库恢复后重启引擎即可。
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

    /** 恢复词库 zip：停止 fcitx → 写盘 → 重启引擎加载新词库。 */
    suspend fun restoreDictZip(zipFile: File): Result<Unit> =
        withContext(Dispatchers.IO) {
            Timber.i("$TAG restoreDictZip begin")
            logZipContent("dict", zipFile)
            try {
                Timber.i("$TAG restoreDictZip stopping fcitx ...")
                FcitxDaemon.stopFcitx()
                Timber.i("$TAG restoreDictZip fcitx stopped, importing ...")
                UserDataImportCompat.import(zipFile.inputStream()).getOrThrow()
                Timber.i("$TAG restoreDictZip imported, starting fcitx ...")
                FcitxDaemon.startFcitx()
                Timber.i("$TAG restoreDictZip done")
                Result.success(Unit)
            } catch (e: Exception) {
                logFailure("restoreDictZip", e)
                runCatching { FcitxDaemon.startFcitx() }
                Result.failure(e)
            }
        }
}
