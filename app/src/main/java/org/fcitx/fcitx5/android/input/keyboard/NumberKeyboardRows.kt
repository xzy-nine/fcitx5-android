/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Variant
import org.fcitx.fcitx5.android.input.picker.PickerWindow

/**
 * 数字键盘的四行行数据（**纯 [KeyDef]，无 View 依赖**）。
 *
 * 从 [NumberKeyboard] 抽出来，好让 Compose 版（`Docs/KeyboardComposePlan.md` 批次 C2）
 * 与 View 版读**同一份**布局数据，避免两套实现随时间漂移（与 A 批 [KeyboardLayoutMath]
 * 同一个思路）。
 *
 * 注意 [row4Split]：分体模式下「!?#」键被移到了左侧历史符号面板，释放的空间给了逗号键。
 */
internal object NumberKeyboardRows {

    fun row1(): List<KeyDef> = listOf(
        NumPadKey("1", 0xffb1, 30f, 0f),
        NumPadKey("2", 0xffb2, 30f, 0f),
        NumPadKey("3", 0xffb3, 30f, 0f),
        BackspaceKey()
    )

    fun row2(): List<KeyDef> = listOf(
        NumPadKey("4", 0xffb4, 30f, 0f),
        NumPadKey("5", 0xffb5, 30f, 0f),
        NumPadKey("6", 0xffb6, 30f, 0f),
        MiniSpaceKey()
    )

    fun row3(): List<KeyDef> = listOf(
        NumPadKey("7", 0xffb7, 30f, 0f),
        NumPadKey("8", 0xffb8, 30f, 0f),
        NumPadKey("9", 0xffb9, 30f, 0f),
        NumPadKey("/", 0xffaf, 23f, 0.15f, Variant.Alternative)
    )

    fun row4(): List<KeyDef> = listOf(
        ImageLayoutSwitchKey(
            R.drawable.ic_baseline_arrow_back_24,
            TextKeyboard.Name,
            percentWidth = 0.15f,
            variant = Variant.Accent
        ),
        NumPadKey(",", 0xffac, 23f, 0.1f, Variant.Alternative),
        LayoutSwitchKey("!?#", PickerWindow.Key.Symbol.name, 0.13333f, Variant.AltForeground),
        NumPadKey("0", 0xffb0, 30f, 0.23334f),
        NumPadKey("@", 0x40, 23f, 0.13333f, Variant.AltForeground),
        NumPadKey(".", 0xffae, 23f, 0.1f, Variant.Alternative),
        ReturnKey()
    )

    fun row4Split(): List<KeyDef> = listOf(
        ImageLayoutSwitchKey(
            R.drawable.ic_baseline_arrow_back_24,
            TextKeyboard.Name,
            percentWidth = 0.15f,
            variant = Variant.Accent
        ),
        NumPadKey(",", 0xffac, 23f, 0.23333f, Variant.Alternative),
        NumPadKey("0", 0xffb0, 30f, 0.23334f),
        NumPadKey("@", 0x40, 23f, 0.13333f, Variant.AltForeground),
        NumPadKey(".", 0xffae, 23f, 0.1f, Variant.Alternative),
        ReturnKey()
    )
}
