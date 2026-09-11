/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.keyboard.ImageLayoutSwitchKey
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.ReturnKey
import org.fcitx.fcitx5.android.input.keyboard.SpaceKey
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard

/**
 * Picker 底部内嵌键盘的行数据（纯 [KeyDef]，无 View 依赖）。
 *
 * 从 `PickerLayout.Keyboard` 抽出，让 Compose 版 Picker（批次 D-3）复用同一份布局数据，
 * 避免两套实现漂移（与 `NumberKeyboardRows` 同一个手法）。
 *
 * @param switchKey 由各 Picker 预设传入（符号页 / 表情页 / 颜文字页各自的切换键）
 */
internal fun pickerKeyboardRow(switchKey: KeyDef): List<KeyDef> = listOf(
    ImageLayoutSwitchKey(
        R.drawable.ic_baseline_arrow_back_24,
        TextKeyboard.Name,
        percentWidth = 0.15f,
        variant = KeyDef.Appearance.Variant.Accent
    ),
    PickerPunctuationKey(","),
    switchKey,
    SpaceKey(),
    PickerPunctuationKey("."),
    ReturnKey()
)

/** Picker 内嵌键盘的标点键（原 `PickerLayout.Keyboard.PunctuationKey`）。 */
internal class PickerPunctuationKey(val symbol: String) : KeyDef(
    Appearance.Text(
        displayText = symbol,
        textSize = 23f,
        percentWidth = 0.1f,
        variant = Appearance.Variant.Alternative
    ),
    setOf(
        Behavior.Press(KeyAction.FcitxKeyAction(symbol))
    )
)
