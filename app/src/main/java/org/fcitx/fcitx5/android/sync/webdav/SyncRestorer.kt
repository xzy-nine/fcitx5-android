/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.sync.webdav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.data.UserDataManager
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import java.io.File

/**
 * 恢复器：把云端下载的 zip 恢复到本地。
 *
 * 偏好 zip / 词库 zip 均为 UserDataManager 分区兼容格式，因此直接走
 * [UserDataManager.import]（metadata 硬校验 + 分区缺失软容忍），天然实现
 * “选择恢复偏好或词库”的分区级部分恢复。
 *
 * 两个方向都会先停止 fcitx 再写盘，避免引擎占用/读到半成品；
 * 偏好恢复要求随后重启进程（由 UI 通知并退出），词库恢复后重启引擎即可。
 */
object SyncRestorer {

    /** 恢复偏好 zip。成功后应提示并退出进程让配置生效。返回导入元数据（含导出时间）。 */
    suspend fun restorePrefsZip(zipFile: File): Result<UserDataManager.Metadata> =
        withContext(Dispatchers.IO) {
            try {
                FcitxDaemon.stopFcitx()
                val metadata = UserDataManager.import(zipFile.inputStream()).getOrThrow()
                Result.success(metadata)
            } catch (e: Exception) {
                // 导入失败时恢复引擎运行
                FcitxDaemon.startFcitx()
                Result.failure(e)
            }
        }

    /** 恢复词库 zip：停止 fcitx → 写盘 → 重启引擎加载新词库。 */
    suspend fun restoreDictZip(zipFile: File): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                FcitxDaemon.stopFcitx()
                UserDataManager.import(zipFile.inputStream()).getOrThrow()
                FcitxDaemon.startFcitx()
                Result.success(Unit)
            } catch (e: Exception) {
                runCatching { FcitxDaemon.startFcitx() }
                Result.failure(e)
            }
        }
}
