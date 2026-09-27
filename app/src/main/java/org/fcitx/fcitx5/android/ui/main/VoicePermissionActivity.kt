/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import org.fcitx.fcitx5.android.data.voice.VoicePermissionState

/**
 * custom: 申请录音权限的透明中转 Activity。
 *
 * 输入法服务无法调用 `requestPermissions`（需要 Activity 窗口令牌），因此 IME 语音面板/
 * 设置页通过 [com.kingzcheung.xime.util.PermissionHelper.requestRecordAudioPermission]
 * 以 `FLAG_ACTIVITY_NEW_TASK` 拉起本 Activity；结果写入 [VoicePermissionState]（同进程
 * StateFlow），面板即时可见，随后本 Activity 立即结束、不进入最近任务。
 */
class VoicePermissionActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        VoicePermissionState.set(granted)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VoicePermissionState.has(this)) {
            VoicePermissionState.set(true)
            finish()
            return
        }
        launcher.launch(VoicePermissionState.PERMISSION)
    }
}
