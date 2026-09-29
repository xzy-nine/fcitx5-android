/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.handwriting

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.fcitx.fcitx5.android.R
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * custom: IME 手写面板的覆盖层宿主。
 *
 * 与语音面板同样**不做窗口切换**：键盘窗口保持 attach，工具栏可见可用，
 * 面板本身是挂在窗口容器之上的 Compose 覆盖层（见 `InputView.handwritingPanelView`）。
 * 不可见时不组合内容，宿主 View 的 GONE 由 [HandwritingInputComponent.panelVisibleListener]
 * 直接控制（只翻转 Compose 内部的 LocalView 会让 GONE 的父级永远隐藏面板）。
 */
@Composable
fun HandwritingPanelHost(
    handwriting: HandwritingInputComponent,
    onBackToKeyboard: () -> Unit,
) {
    val visible by handwriting.panelVisible.collectAsState()
    if (!visible) return

    val state by handwriting.state.collectAsState()

    val status = when {
        state.modelMissing -> stringResource(R.string.handwriting_model_missing)
        !state.modelReady -> stringResource(R.string.handwriting_state_loading)
        state.recognizing -> stringResource(R.string.handwriting_state_recognizing)
        else -> stringResource(R.string.handwriting_state_idle)
    }

    ComposeHandwritingPanel(
        modelReady = state.modelReady,
        statusText = state.error ?: status,
        candidates = state.candidates,
        onStrokesChanged = { strokes, gaps -> handwriting.onStrokesChanged(strokes, gaps) },
        onFinalize = { handwriting.finalizeActive() },
        onUndoActive = { handwriting.undoActive() },
        onSelectCandidate = { handwriting.selectCandidate(it) },
        onClear = { handwriting.clearError() },
        onBackToKeyboard = onBackToKeyboard,
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background),
    )
}
