/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import org.fcitx.fcitx5.android.R
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Edit

/**
 * 按钮图标的两种来源。
 *
 * 大部分按钮用内置 drawable；手写按钮沿用 miuix 图标集里的矢量图，
 * 因此这里同时支持两种，避免为了统一而把矢量图换成风格不一致的位图资源。
 */
sealed interface ToolbarIcon {
    data class Res(val id: Int) : ToolbarIcon
    data class Vector(val image: ImageVector) : ToolbarIcon
}

/**
 * [ToolbarButton] 的展示信息。
 *
 * 工具栏可停靠行与更多页按钮池共用同一套图标/文案，避免两处各写一份而漂移。
 * 行为（点击做什么）不在这里：它是窗口/服务相关的副作用，留在各自的调用方。
 */
object ToolbarButtonSpec {

    /**
     * 按钮图标。
     *
     * [splitKeyboardEnabled] 只对 [ToolbarButton.SplitKeyboard] 有意义：开启分体键盘时
     * 图标表示「切回完整键盘」，否则表示「切成分体」。
     */
    fun iconOf(button: ToolbarButton, splitKeyboardEnabled: Boolean): ToolbarIcon = when (button) {
        ToolbarButton.TextEditing -> ToolbarIcon.Res(R.drawable.ic_cursor_move)
        ToolbarButton.Clipboard -> ToolbarIcon.Res(R.drawable.ic_clipboard)
        ToolbarButton.Tune -> ToolbarIcon.Res(R.drawable.ic_baseline_tune_24)
        ToolbarButton.Voice -> ToolbarIcon.Res(R.drawable.ic_baseline_keyboard_voice_24)
        ToolbarButton.Handwriting -> ToolbarIcon.Vector(MiuixIcons.Edit)
        ToolbarButton.SplitKeyboard -> ToolbarIcon.Res(
            if (splitKeyboardEnabled) R.drawable.ic_baseline_keyboard_24
            else R.drawable.ic_split_keyboard
        )
    }

    @StringRes
    fun labelOf(button: ToolbarButton): Int = when (button) {
        ToolbarButton.TextEditing -> R.string.text_editing
        ToolbarButton.Clipboard -> R.string.clipboard
        ToolbarButton.Tune -> R.string.keyboard_tune
        ToolbarButton.Voice -> R.string.voice_input
        ToolbarButton.Handwriting -> R.string.handwriting_input
        ToolbarButton.SplitKeyboard -> R.string.split_keyboard
    }
}
