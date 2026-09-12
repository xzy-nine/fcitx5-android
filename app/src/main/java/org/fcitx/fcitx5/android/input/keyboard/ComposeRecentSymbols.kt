/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Variant

/**
 * 横屏分体数字键盘左侧的历史符号面板（`RecentSymbolsView` 的 Compose 版，批次 C5）。
 *
 * 与 View 版逐项对应：
 *
 * | View | 这里 |
 * |---|---|
 * | 面板背景 = `theme.backgroundColor`，且无按压高亮 | `Box.background(visuals.backgroundColor)`，本身不挂手势 |
 * | 左侧「!?#」按钮固定宽 = 面板宽 / (列数+1)、高占满 | `Row` 里 `width(cellWidth)` + `fillMaxHeight()` |
 * | 右侧 4 列网格（RecyclerView GridLayoutManager） | `LazyVerticalGrid(GridCells.Fixed(4))` |
 * | 格子高度 = 列宽 × 0.6（扁矩形） | `height(cellWidth * 0.6f)` |
 * | 无历史符号时显示空态提示（居中、14dp、altKeyTextColor @50%） | 同一位置的 `BasicText` |
 * | 子按钮复用 `TextKeyView`（字号 26、Variant.Alternative） | 复用 [ComposeKey]，外观完全一致 |
 *
 * **纯 Compose 的意义**：View 版通过 `AndroidView` 嵌进 Compose 键盘时，`factory` 只跑一次，
 * 换主题不会重染；这里所有颜色都取自 [rememberKeyboardVisuals]，换主题即时生效。
 * 本文件是键盘域内最后一个 View 依赖（`RecentSymbolsView`）的替代品：View 版现已只被
 * **已断线**的 `NumberKeyboard.buildSplitLayout` 引用，生产路径（`ComposeNumberKeyboard`）
 * 全部走这里，不再有 `AndroidView` 桥（见 `Docs/View2Compose.md` §14）。
 */
@Composable
fun ComposeRecentSymbolsPanel(
    symbols: List<String>,
    onSymbolInput: (String) -> Unit,
    onLayoutSwitch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visuals = rememberKeyboardVisuals()
    BoxWithConstraints(modifier = modifier.background(visuals.backgroundColor)) {
        // 网格与按钮同宽：面板总宽 / (列数 + 1)
        val cellWidth = maxWidth / (Columns + 1)
        val cellHeight = cellWidth * CellHeightFactor
        Row(modifier = Modifier.fillMaxSize()) {
            ComposeKey(
                def = remember { layoutSwitchCellDef() },
                keyId = SwitchButtonKeyId,
                modifier = Modifier
                    .width(cellWidth)
                    .fillMaxHeight(),
                keyActionListener = remember {
                    KeyActionListener { _, _ -> onLayoutSwitch() }
                },
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                // View 侧空态 TextView 是 gravity = CENTER（横竖都居中）
                contentAlignment = Alignment.Center,
            ) {
                if (symbols.isEmpty()) {
                    EmptyHint()
                } else {
                    LazyVerticalGrid(columns = GridCells.Fixed(Columns)) {
                        // 按符号文本取 key（`RecentlyUsed` 是去重的 map，不会出现重复 key）：
                        // 点按某符号后它会移到最前，列表重排时节点跟着搬而不是原地换文本
                        itemsIndexed(symbols, key = { _, symbol -> symbol }) { index, symbol ->
                            ComposeKey(
                                def = remember(symbol) { symbolCellDef(symbol) },
                                keyId = SymbolKeyIdBase + index,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(cellHeight),
                                keyActionListener = remember(symbol) {
                                    KeyActionListener { _, _ -> onSymbolInput(symbol) }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 空态占位提示（View: `emptyHint` TextView，居中）。 */
@Composable
private fun EmptyHint() {
    val visuals = rememberKeyboardVisuals()
    val density = LocalDensity.current
    BasicText(
        text = stringResource(R.string.recent_symbols_empty_hint),
        // 竖直居中由外层 Box 的 contentAlignment 负责（BasicText 自身会把文字贴在左上角）
        modifier = Modifier.fillMaxWidth(),
        style = TextStyle(
            fontSize = with(density) { EmptyHintTextSize.dp.toSp() },
            // View: (altKeyTextColor and 0x00FFFFFF) or (0x80 shl 24) → 半透明
            color = visuals.altKeyTextColor.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
        ),
    )
}

// ---------------------------------------------------------------------------
// 子按钮外观（对应 RecentSymbolsView 里的 TextKeyView 构造）
// ---------------------------------------------------------------------------

/** 「!?#」布局切换按钮（View: `SWITCH_TEXT_SIZE` + BOLD + AltForeground）。 */
private fun layoutSwitchCellDef() = KeyDef(
    KeyDef.Appearance.Text(
        displayText = "!?#",
        textSize = SwitchTextSize,
        textStyle = Typeface.BOLD,
        variant = Variant.AltForeground,
    ),
    setOf(KeyDef.Behavior.Press(KeyAction.CommitAction(""))),
    null,
)

/** 历史符号格子（View: `TEXT_SIZE` + 键面由 `mainText.text` 逐格写入）。 */
private fun symbolCellDef(symbol: String) = KeyDef(
    KeyDef.Appearance.Text(
        displayText = symbol,
        textSize = SymbolTextSize,
        textStyle = Typeface.NORMAL,
        variant = Variant.Alternative,
    ),
    setOf(KeyDef.Behavior.Press(KeyAction.CommitAction(symbol))),
    null,
)

/** 历史符号网格列数（View: `COLUMN_COUNT`）。 */
private const val Columns = 4

/** 格子高度系数（View: `CELL_HEIGHT_FACTOR`，扁矩形紧凑排列）。 */
private const val CellHeightFactor = 0.6f

/** 符号字号（View: `TEXT_SIZE`，与数字键一致）。 */
private const val SymbolTextSize = 26f

/** 「!?#」按钮字号（View: `SWITCH_TEXT_SIZE`，适配窄列）。 */
private const val SwitchTextSize = 12f

/** 空态提示字号（View: `setTextSize(COMPLEX_UNIT_DIP, 14f)`）。 */
private const val EmptyHintTextSize = 14f

/** 键 id 基址：与数字键盘各行的 id 段（0/100/200/300）错开。 */
private const val SwitchButtonKeyId = 900
private const val SymbolKeyIdBase = 1000
