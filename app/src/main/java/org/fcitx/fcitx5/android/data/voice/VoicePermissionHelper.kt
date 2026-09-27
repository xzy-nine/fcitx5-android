/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 录音权限的查询与申请。
 *
 * 输入法服务（Service）无法自行申请运行时权限，因此申请统一经透明的
 * [org.fcitx.fcitx5.android.ui.main.VoicePermissionActivity] 中转，
 * 结果写入同进程的 [VoicePermissionState]，IME 面板据此即时刷新。
 */
package org.fcitx.fcitx5.android.data.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import org.fcitx.fcitx5.android.ui.main.VoicePermissionActivity

object VoicePermissionHelper {

    const val PERMISSION_RECORD_AUDIO = Manifest.permission.RECORD_AUDIO

    fun hasRecordAudioPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION_RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    fun requestRecordAudioPermission(context: Context) {
        context.startActivity(
            Intent(context, VoicePermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
