/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyDef

/**
 * 数字行布局数据（原寄生在 [NumberRow] 的 `companion Layout`，供 Compose 版
 * [ComposeNumberRow] 复用，使后者不再依赖 View 类 [NumberRow] / `BaseKeyboard`）。
 *
 * 10 个数字键等宽，每键 `percentWidth = 0.1f`；Compose 侧以 `Modifier.weight(1f)` 等价。
 */
val NumberRowLayout: List<List<KeyDef>> = listOf(
    listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").map { digit ->
        KeyDef(
            KeyDef.Appearance.Text(
                displayText = digit,
                textSize = 21f,
                border = KeyDef.Appearance.Border.Off,
                margin = false
            ),
            setOf(
                KeyDef.Behavior.Press(KeyAction.SymAction(KeySym(digit.codePointAt(0))))
            ),
            arrayOf(KeyDef.Popup.Preview(digit))
        )
    }
)
