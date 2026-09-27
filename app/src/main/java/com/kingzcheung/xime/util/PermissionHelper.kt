/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Kingz Cheung
 *
 * 移植自 Xime (https://github.com/ximeiorg/xime)，见仓库根 NOTICE.md。
 */
package com.kingzcheung.xime.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object PermissionHelper {
    const val PERMISSION_RECORD_AUDIO = android.Manifest.permission.RECORD_AUDIO
    const val REQUEST_CODE_RECORD_AUDIO = 1001
    
    fun hasRecordAudioPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            PERMISSION_RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    fun requestRecordAudioPermission(context: Context) {
        // 本移植：输入法服务无法自行申请运行时权限，改由透明的 VoicePermissionActivity 中转，
        // 结果写入 VoicePermissionState（同进程 StateFlow，面板即时可见）。
        val intent = Intent(
            context,
            org.fcitx.fcitx5.android.ui.main.VoicePermissionActivity::class.java
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}