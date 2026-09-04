/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Variant

/**
 * 数字键盘左侧纵向符号滑块键的定义。
 *
 * 该键本身不通过 [KeyDef.Behavior]/[KeyDef.Popup] 处理触控与弹窗，
 * 全部由 [SymbolSliderKeyView] 自绘并自行处理（点按分段输入、上下滑动换页、
 * 底部编辑键触发弹窗 Activity 编辑符号）。
 */
class SymbolSliderKey(
    val symbols: Array<String> = DefaultSymbols,
    val visibleCount: Int = 3,
    percentWidth: Float = 0.15f,
    variant: Variant = Variant.Alternative,
) : KeyDef(
    Appearance.Text(
        displayText = "＋", // 仅作为占位外观，实际绘制由 SymbolSliderKeyView 完成
        textSize = 16f,
        percentWidth = percentWidth,
        variant = variant,
        viewId = R.id.button_symbol_slider
    ),
    emptySet()
) {
    companion object {
        /** 默认符号集：`+ - * / =` */
        val DefaultSymbols = arrayOf("+", "-", "*", "/", "=")
    }
}
