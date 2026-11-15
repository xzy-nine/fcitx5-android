/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.sync.webdav

import timber.log.Timber

/**
 * 设置自动备份（**仅上传**）。
 *
 * 设计约束：设置**只备份、不自动恢复**——恢复会停止输入法并重建进程，无法在后台静默完成；
 * 且各设备的备份文件名不同（按设备名区分），云端多份备份互不冲突，因此无需下载合并。
 *
 * 因此本对象只调用 [WebDavSyncEngine.uploadSettingsBackup]，任何路径都不调用
 * `downloadRemoteZip`/`SyncRestorer`。恢复只能由用户在界面上手动选择备份文件触发。
 */
object AutoPrefsBackup {

    private const val TAG = "AutoPrefsBackup"

    /** 内容变化时上传设置备份；未变化时引擎侧直接跳过（不产生网络写）。 */
    suspend fun backupIfChanged(cfg: WebDavSyncConfig): Result<String> =
        WebDavSyncEngine.uploadSettingsBackup(cfg).onFailure {
            Timber.e(it, "$TAG settings backup failed: ${it.javaClass.simpleName}")
        }
}
