/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.voice

import android.content.Context
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * custom: 录音权限的进程内状态。
 *
 * 输入法服务（[org.fcitx.fcitx5.android.input.FcitxInputMethodService]）是 Service，
 * 无法直接发起运行时权限请求，因此由 [org.fcitx.fcitx5.android.ui.main.VoicePermissionActivity]
 * 从 Activity 侧请求，结果写入这里的 StateFlow（同进程、即时可见），
 * IME 语音面板据此实时刷新。
 */
object VoicePermissionState {

    const val PERMISSION = android.Manifest.permission.RECORD_AUDIO

    private val _granted = MutableStateFlow(false)

    val granted: StateFlow<Boolean> = _granted.asStateFlow()

    fun has(context: Context): Boolean = ContextCompat.checkSelfPermission(
        context, PERMISSION
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** 从系统实际状态刷新缓存值（面板/设置页显示前调用）。 */
    fun refresh(context: Context) {
        _granted.value = has(context)
    }

    fun set(granted: Boolean) {
        _granted.value = granted
    }
}
