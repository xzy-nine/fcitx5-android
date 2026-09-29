/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * 竖向滚动符号列（滑块）：数字键盘与手写键盘右侧列共用。
 *
 * 每个符号一个子按钮，子按钮高度 = 滑块高度 / 外显段数 × 0.9（露出下一个按钮的一小部分提示可滑动），
 * 整列可滚动；子按钮复用 [ComposeKey]，底色/圆角/按压高亮/震动与音效与键盘其他按键一致。
 * `onEditClick` 为 null 时不出编辑按钮（符号列表由调用方固定填充）。
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Variant

/**
 * 竖向符号滑块。
 *
 * @param visibleCount 外显段数（子按钮高度 = 滑块高度 / 该值 × 0.9）
 * @param onSymbolInput 符号被点按
 * @param onEditClick 编辑按钮回调；null 表示不可自定义（不渲染编辑按钮）
 */
@Composable
fun ComposeSymbolSlider(
    symbols: List<String>,
    visibleCount: Int,
    onSymbolInput: (String) -> Unit,
    onEditClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val visuals = rememberKeyboardVisuals()
    // View: 滑块区背景用 IME 主背景色（只有子按钮保留按键底色）
    Box(modifier = modifier.background(visuals.backgroundColor)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val cellHeight = (maxHeight / visibleCount.coerceAtLeast(1)) * 0.9f
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                symbols.forEachIndexed { index, symbol ->
                    ComposeKey(
                        def = remember(symbol) { symbolCellDef(symbol) },
                        keyId = index,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(cellHeight),
                        keyActionListener = remember(symbol) {
                            KeyActionListener { _, _ -> onSymbolInput(symbol) }
                        },
                    )
                }
                if (onEditClick != null) {
                    ComposeKey(
                        def = remember { editCellDef() },
                        keyId = symbols.size,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(cellHeight),
                        keyActionListener = remember(onEditClick) {
                            KeyActionListener { _, _ -> onEditClick() }
                        },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 子按钮外观（对应 SymbolSliderKeyView.makeSymbolButton / makeEditButton）
// ---------------------------------------------------------------------------

private fun symbolCellDef(symbol: String) = KeyDef(
    KeyDef.Appearance.Text(
        displayText = symbol,
        textSize = 26f,
        textStyle = Typeface.NORMAL,
        variant = Variant.Alternative,
    ),
    // 动作只用于占位：点击由 ComposeKey 的 keyActionListener 直接回调 onSymbolInput
    setOf(KeyDef.Behavior.Press(KeyAction.CommitAction(symbol))),
    null,
)

private fun editCellDef() = KeyDef(
    KeyDef.Appearance.Image(
        src = R.drawable.ic_baseline_edit_24,
        variant = Variant.Alternative,
    ),
    setOf(KeyDef.Behavior.Press(KeyAction.CommitAction(""))),
    null,
)
