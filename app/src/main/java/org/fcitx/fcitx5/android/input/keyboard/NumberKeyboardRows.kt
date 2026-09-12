/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Variant
import org.fcitx.fcitx5.android.input.keyboard.NumberKeyboardRows.row4Split
import org.fcitx.fcitx5.android.input.picker.PickerWindow

/**
 * 数字键盘的四行行数据（**纯 [KeyDef]，无 View 依赖**）。
 *
 * 从 [NumberKeyboard] 抽出来，好让 Compose 版（`Docs/KeyboardComposePlan.md` 批次 C2）
 * 与 View 版读**同一份**布局数据，避免两套实现随时间漂移（与 A 批 [KeyboardLayoutMath]
 * 同一个思路）。
 *
 * 注意 [row4Split]：分体模式下「!?#」键被移到了左侧历史符号面板，释放的空间给了逗号键。
 *
 * 行数据是**单一实例**（`val` 而非 `fun`）：`KeyDef` 是普通类、按 identity 判等，
 * 每次返回新列表都会让 Compose 侧 `ComposeKeyRow` 的 `remember(row, …)` 缓存失效
 * （每次重组全量重算槽位）。行内 `KeyDef` 均不可变，View 侧 `createKeyRow` 只读，
 * 两套实现共用同一份实例是安全的。
 */
internal object NumberKeyboardRows {

    val row1: List<KeyDef> = listOf(
        NumPadKey("1", 0xffb1, 30f, 0f),
        NumPadKey("2", 0xffb2, 30f, 0f),
        NumPadKey("3", 0xffb3, 30f, 0f),
        BackspaceKey()
    )

    val row2: List<KeyDef> = listOf(
        NumPadKey("4", 0xffb4, 30f, 0f),
        NumPadKey("5", 0xffb5, 30f, 0f),
        NumPadKey("6", 0xffb6, 30f, 0f),
        MiniSpaceKey()
    )

    val row3: List<KeyDef> = listOf(
        NumPadKey("7", 0xffb7, 30f, 0f),
        NumPadKey("8", 0xffb8, 30f, 0f),
        NumPadKey("9", 0xffb9, 30f, 0f),
        NumPadKey("/", 0xffaf, 23f, 0.15f, Variant.Alternative)
    )

    val row4: List<KeyDef> = listOf(
        ImageLayoutSwitchKey(
            R.drawable.ic_baseline_arrow_back_24,
            KeyboardLayoutNames.Text,
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

    val row4Split: List<KeyDef> = listOf(
        ImageLayoutSwitchKey(
            R.drawable.ic_baseline_arrow_back_24,
            KeyboardLayoutNames.Text,
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
