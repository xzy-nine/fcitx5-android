/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.candidates.ComposeSplitCandidatesUi
import org.fcitx.fcitx5.android.input.candidates.SplitTab
import org.fcitx.fcitx5.android.input.candidates.rememberCanPageDown
import org.fcitx.fcitx5.android.input.keyboard.ComposeKey
import org.fcitx.fcitx5.android.input.keyboard.ImageLayoutSwitchKey
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.KeyboardLayoutNames
import org.fcitx.fcitx5.android.input.popup.PopupActionListener

/**
 * Picker（符号 / Emoji / 颜文字）的中栏：把 [PickerGridLayout] 的槽位铺成一个连续上下滚动的网格。
 *
 * 三栏骨架（左分类侧栏 / 中网格 / 右竖排键盘）本身在 [ComposeSplitCandidatesUi] 里，与「展开候选页」
 * **共用同一套实现**（同一份键位、同一份删除键功能）；本文件只负责 Picker 特有的部分：
 *
 * - 中栏内容 = 槽位（候选格 + 分类之间的换页空行），格子复用 [cellDef]（键面走键盘视觉桥，
 *   保留用户名下自定义主题/背景图）；
 * - 列数 = 中栏可用宽度 / 最小格子宽，再由 [PickerDensity.columnCount]（旧版固定列数）封顶；
 *   **行高固定**（不随列数/宽度变化），宽屏只是格子变宽，可见行数只由可用高度决定；
 * - 左栏激活项由「首可见槽位所属分类」反推（滚动到底特判末分类），点标签由窗口按锚点滚动。
 *
 * @param gridLayout 网格布局模型（分类 + 含换页空行的槽位 + 各分类锚点），由 `PickerWindow` 用
 *   [buildPickerGridLayout] 构建后传入：锚点同时也被窗口的「点标签滚动 / 初次复位」使用，
 *   放在窗口层构建可避免两处各算一份
 */
@Composable
internal fun ComposePickerSplitUi(
    gridLayout: PickerGridLayout,
    density: PickerDensity,
    bordered: Boolean,
    gridState: LazyGridState,
    gridScrollEnabled: Boolean,
    returnDrawable: Int,
    keyActionListener: KeyActionListener?,
    popupActionListener: PopupActionListener?,
    policy: PickerPolicy?,
    onCategorySelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sections = gridLayout.sections
    val border = if (bordered) {
        KeyDef.Appearance.Border.On
    } else {
        KeyDef.Appearance.Border.Off
    }
    // 密度 → 格子最小宽度（决定列数）+ 固定行高
    val cellSpec = remember(density) { density.cellSpec() }
    val minCellWidth = cellSpec.minWidth
    val columnCap = density.columnCount.coerceAtLeast(1)
    val gapHeight = cellSpec.height * PickerCategoryGapRows

    val canPageDown = rememberCanPageDown(gridState)
    // 反向联动：首可见槽位所属分类 → 左栏高亮；滚动到底时特判为末分类
    // （末分类通常无法贴顶，靠「不再能下翻且不在顶部」判定才点得亮）
    val activeCategory by remember(gridLayout) {
        derivedStateOf {
            if (sections.isEmpty()) return@derivedStateOf 0
            val info = gridState.layoutInfo
            // 尚未布局 / 内容为空：按顶部分类处理，避免闪现「末分类」高亮
            if (info.totalItemsCount <= 0) return@derivedStateOf 0
            val atTop = gridState.firstVisibleItemIndex == 0 &&
                gridState.firstVisibleItemScrollOffset == 0
            if (!canPageDown && !atTop) return@derivedStateOf sections.lastIndex
            val first = info.visibleItemsInfo.firstOrNull()?.index ?: 0
            gridLayout.sectionStarts.indexOfLast { it <= first }.coerceAtLeast(0)
        }
    }
    val tabs = remember(gridLayout, activeCategory) {
        sections.mapIndexed { index, section ->
            SplitTab(text = section.category.label, active = index == activeCategory)
        }
    }
    // 「返回主键盘」只在 Picker 侧出现（展开候选页由预编辑清空自动回退）
    val backToKeyboardKey = remember {
        ImageLayoutSwitchKey(
            R.drawable.ic_baseline_arrow_back_24,
            KeyboardLayoutNames.Text,
            variant = KeyDef.Appearance.Variant.Accent,
        )
    }

    ComposeSplitCandidatesUi(
        tabs = tabs,
        gridState = gridState,
        returnDrawable = returnDrawable,
        keyActionListener = keyActionListener,
        popupActionListener = popupActionListener,
        onTabSelected = onCategorySelected,
        columns = { availableWidth: Dp ->
            (availableWidth / minCellWidth).toInt().coerceIn(1, columnCap)
        },
        modifier = modifier,
        gridScrollEnabled = gridScrollEnabled,
        backToKeyboardKeyDef = backToKeyboardKey,
    ) {
        items(
            count = gridLayout.slots.size,
            key = { index ->
                val slot = gridLayout.slots[index]
                if (slot.isGap) {
                    "gap-$index"
                } else {
                    "$index-${sections[slot.section].items[slot.itemIndex]}"
                }
            },
            // 换页空行占满一整行（下一个分类因此从新的一行开始）
            span = { index ->
                if (gridLayout.slots[index].isGap) GridItemSpan(maxLineSpan) else GridItemSpan(1)
            },
        ) { index ->
            val slot = gridLayout.slots[index]
            if (slot.isGap) {
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(gapHeight),
                )
                return@items
            }
            val section = sections[slot.section]
            val raw = section.items[slot.itemIndex]
            // 「⟳最近使用」节：不做 transform、不挂弹层
            val sectionPolicy = if (section.hasPopups) policy else null
            val display = sectionPolicy?.transform(raw) ?: raw
            // 长按弹层必须按项记忆：`KeyDef.Popup.Keyboard.Explicit`（emoji 肤色）是**引用相等**
            // 的普通类，每次重组都会新建实例 → 下面 remember 重跑 → 新 KeyDef → 新 behaviors Set
            // → `ComposeKey` 的手势节点重启（等价 ACTION_CANCEL）→ 手势 finally 里的
            // `dismissPreview()` 发 DismissAction 把刚弹出的浮窗关掉，表现为「长按浮窗只闪一下」。
            val popupKeyboard = remember(raw, sectionPolicy) { sectionPolicy?.popup(raw) }
            val def = remember(raw, display, border, density, popupKeyboard) {
                cellDef(
                    display = display,
                    raw = raw,
                    density = density,
                    border = border,
                    popupKeyboard = popupKeyboard,
                )
            }
            ComposeKey(
                def = def,
                // 网格按下标分配 keyId（0..N），与左栏 0x8000 / 右栏 0x9000 段错开
                keyId = index,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(cellSpec.height),
                keyActionListener = keyActionListener,
                popupActionListener = if (section.hasPopups) {
                    popupActionListener
                } else {
                    null
                },
                autoScale = density.autoScale,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 密度映射
// ---------------------------------------------------------------------------

/**
 * 格子尺寸规格：**最小宽度**（决定列数）+ **固定行高**（与列数/宽度无关）。
 *
 * 行高刻意不按「格子宽 ÷ 纵横比」算：那样列数越少（宽屏封顶后）格子越宽越高，
 * 一屏只能挤下两行。固定行高后宽屏只是格子变宽，可见行数只由可用高度决定。
 */
private class PickerCellSpec(val minWidth: Dp, val height: Dp)

/**
 * 密度 → 格子规格：符号 32dp / Emoji 44dp / 颜文字 76dp 宽 × 36dp 高。
 *
 * 行高取「最窄格子的宽度」（颜文字按其宽扁比例 76/2.2 ≈ 35dp，取整 36dp）：
 * 窄屏时格子仍是原设计的方形观感，宽屏时变成宽扁格，行数不被列数吃掉。
 * 列数不取 [PickerDensity.columnCount]，而是按可用宽度反推后用其封顶，
 * 故只保留字号/缩放（[cellDef] 仍读 [PickerDensity.textSize] / [PickerDensity.autoScale]）。
 */
private fun PickerDensity.cellSpec(): PickerCellSpec = when (this) {
    PickerDensity.High -> PickerCellSpec(minWidth = 32.dp, height = 32.dp)
    PickerDensity.Medium -> PickerCellSpec(minWidth = 44.dp, height = 44.dp)
    PickerDensity.Low -> PickerCellSpec(minWidth = 76.dp, height = 36.dp)
}
