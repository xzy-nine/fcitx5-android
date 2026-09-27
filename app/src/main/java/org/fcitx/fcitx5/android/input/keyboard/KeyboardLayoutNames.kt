/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * 键盘布局名常量（原寄生在 View 类 [TextKeyboard] / [NumberKeyboard] 的 `companion.Name`，
 * 供 [KeyboardWindow] 等 Compose 侧与布局数据文件复用，使后者不再 import View 键盘类）。
 *
 * - [TextLayoutName] 对应 QWERTY 文本布局（[TextKeyboard]）；
 * - [NumberLayoutName] 对应数字/符号布局（[NumberKeyboard]）。
 *
 * `LayoutSwitchKey` / `KeyboardWindow.currentLayout` 等以字符串标识布局，取值于此。
 */
object KeyboardLayoutNames {
    const val Text = "Text"
    const val Number = "Number"
}
