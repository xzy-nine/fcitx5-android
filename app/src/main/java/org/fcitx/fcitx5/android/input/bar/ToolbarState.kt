/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Compose 工具栏视觉配置（颜色源：MiuixTheme.colorScheme）
 */
@Immutable
data class ToolbarVisuals(
    val barColor: Color,
    val iconColor: Color,
    val textColor: Color,
)

/**
 * Compose 工具栏回调
 */
data class ToolbarCallbacks(
    val onMenuClick: () -> Unit,
    val onHideKeyboard: () -> Unit,
    val onUndo: () -> Unit,
    val onRedo: () -> Unit,
    val onCursorMove: () -> Unit,
    val onClipboard: () -> Unit,
    val onSplitKeyboardToggle: () -> Unit,
    val splitKeyboardEnabled: Boolean,
    val onMore: () -> Unit,
    val onTune: () -> Unit,
    val onTitleBack: () -> Unit,
    val onClipboardSuggestionClick: () -> Unit,
    val onClipboardSuggestionLongClick: () -> Unit,
    val onNumberRowCollapse: () -> Unit,
    val onNumberRowShow: () -> Unit,
)

/**
 * Idle 子状态
 */
enum class IdleSubState {
    Empty,
    Toolbar,
    Clipboard,
    NumberRow,
    InlineSuggestion,
}

/**
 * Title 数据
 */
@Immutable
data class TitleData(
    val title: String,
    val showTitle: Boolean,
)
