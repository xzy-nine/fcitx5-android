/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

/**
 * Picker 页面密度规格（原寄生在 [PickerPageUi] 的嵌套 `enum Density`，供
 * [ComposePickerPage] / [org.fcitx.fcitx5.android.input.keyboard.ComposeNumberKeyboard]
 * 等Compose 侧复用，使后者不再依赖 View 类 [PickerPageUi]）。
 *
 * @param pageSize 每页键数
 * @param columnCount 列数
 * @param rowCount 行数
 * @param textSize 文字字号
 * @param autoScale 是否按比例缩放以适应宽度
 * @param showBackspace 是否在右下角显示退格键
 */
enum class PickerDensity(
    val pageSize: Int,
    val columnCount: Int,
    val rowCount: Int,
    val textSize: Float,
    val autoScale: Boolean,
    val showBackspace: Boolean,
) {
    // symbol: 10/10/8, backspace on bottom right
    High(28, 10, 3, 19f, false, true),

    // emoji: 7/7/6, backspace on bottom right
    Medium(20, 7, 3, 23.7f, false, true),

    // emoticon: 4/4/4, no backspace
    Low(12, 4, 3, 19f, true, false),
}
