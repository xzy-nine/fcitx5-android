/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.view.View
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.voice.VoicePermissionState
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.wm.ComposeWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.input.wm.createComposeWindowView
import org.mechdancer.dependency.manager.must
import top.yukonga.miuix.kmp.basic.Scaffold

/**
 * custom: IME 内的语音输入面板窗口。
 *
 * 与剪贴板 / 文本编辑等页面同级：非 essential 的 ComposeWindow，
 * 由 `windowManager.attachWindow(VoiceInputWindow())` 打开，
 * 返回键盘用 `windowManager.attachWindow(KeyboardWindow)`。
 *
 * 会话状态与引擎由 [VoiceInputComponent] 持有（随 InputView scope 存活），
 * 本窗口只负责渲染与转发交互，并在离开时丢弃未结束的会话。
 */
class VoiceInputWindow : InputWindow.ExtendedInputWindow<VoiceInputWindow>(), ComposeWindow {

    private val service by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()
    private val voice: VoiceInputComponent by manager.must()

    override val showTitle: Boolean get() = false

    override val title: String by lazy { context.getString(R.string.voice_input) }

    /** 兜底实现；InputWindowManager 对 ComposeWindow 走 createComposeWindowView。 */
    override fun onCreateView(): View = createComposeWindowView(context) { Content() }

    @Composable
    override fun Content() {
        val state by voice.state.collectAsState()
        val spectrum by voice.spectrum.collectAsState()
        val permissionGranted by VoicePermissionState.granted.collectAsState()

        LaunchedEffect(Unit) {
            VoicePermissionState.refresh(context)
            voice.onPanelShown()
        }

        // miuix Overlay* 弹层需要根 Scaffold 提供 MiuixPopupHost；
        // contentWindowInsets = 0 避免二次顶开 IME insets（由 InputView 统一处理）。
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0),
        ) {
            ComposeVoicePanel(
                state = state,
                spectrum = spectrum,
                permissionGranted = permissionGranted,
                autoMode = voice.isAutoMode,
                onPressStart = { voice.startRecognition() },
                onPressEnd = { voice.stopRecognition() },
                onToggleAutoMode = { voice.setAutoMode(!voice.isAutoMode) },
                onCancel = {
                    voice.cancelSession()
                    windowManager.attachWindow(KeyboardWindow)
                },
                onBackToKeyboard = {
                    voice.onPanelHidden()
                    windowManager.attachWindow(KeyboardWindow)
                },
                onOpenSettings = {
                    org.fcitx.fcitx5.android.data.voice.VoiceRoutes.open(
                        context, org.fcitx.fcitx5.android.data.voice.VoiceRoutes.SETTINGS
                    )
                },
                onOpenModels = {
                    org.fcitx.fcitx5.android.data.voice.VoiceRoutes.open(
                        context, org.fcitx.fcitx5.android.data.voice.VoiceRoutes.MODELS
                    )
                },
                onGrantPermission = {
                    org.fcitx.fcitx5.android.data.voice.VoicePermissionHelper.requestRecordAudioPermission(context)
                },
            )
        }
    }

    override fun onAttached() {
        // 真正的初始化放 Content 的 LaunchedEffect，避免在 attach 流程里做重活
    }

    override fun onDetached() {
        voice.onPanelHidden()
        // 面板被别的窗口替换时，确保麦克风不再占用（stopRecognition 已含静音恢复）
        service.finishComposing()
    }
}
