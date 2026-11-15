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
    val onMore: () -> Unit,
    val onTitleBack: () -> Unit,
    val onClipboardSuggestionClick: () -> Unit,
    val onClipboardSuggestionLongClick: () -> Unit,
    val onNumberRowCollapse: () -> Unit,
    val onNumberRowShow: () -> Unit,
    /** custom: 更多页打开中（顶栏换成可停靠行 + 返回/编辑，收起键盘不显示）。 */
    val morePageVisible: Boolean = false,
    /**
     * custom: 语音面板打开中。
     *
     * 语音面板是覆盖层、不切窗口，但它同样把顶栏切进了 Title 态；若不特殊处理，
     * 左侧菜单键会被返回键取代、可停靠行整行消失 —— 而返回键与菜单键位置重合，
     * 表现为「按钮看着没了、点下去却触发了别的」。这里与更多页同规：
     * 保留可停靠行，左侧放返回。
     */
    val voicePageVisible: Boolean = false,
    /** custom: 布局编辑模式中（左侧返回隐藏，右侧为「完成」）。 */
    val editing: Boolean = false,
    /** custom: 是否显示撤销/重做两个固定槽位。 */
    val showUndoRedo: Boolean = true,
    /** custom: 可停靠按钮是否可用（不可用时置灰但仍占位）。 */
    val buttonUsable: (ToolbarButton) -> Boolean = { true },
    /** custom: 工具栏可停靠行里的按钮被点击。 */
    val onButtonClick: (ToolbarButton) -> Unit = {},
    /** custom: 进入/退出布局编辑模式。 */
    val onEditToggle: () -> Unit = {},
    /** custom: 更多页左侧的返回（回主键盘）。 */
    val onBackToKeyboard: () -> Unit = {},
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
