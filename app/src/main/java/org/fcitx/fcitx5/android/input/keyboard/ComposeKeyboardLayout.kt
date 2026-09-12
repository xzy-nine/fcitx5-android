/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import org.fcitx.fcitx5.android.data.prefs.AppPrefs

/**
 * 键盘行列 → Compose 布局（`Docs/KeyboardComposePlan.md` 批次 C / D10）。
 *
 * 宽度语义与 View 侧 `BaseKeyboard` 的 `ConstraintLayout` 逐条对应：
 *
 * | View | 这里 |
 * |---|---|
 * | `matchConstraintPercentWidth = percent` | `Modifier.width(rowWidth * percent)` |
 * | `percent == 0f` 的键「占满剩余空间」 | `Modifier.weight(1f)` |
 * | 首尾键 `CHAIN_PACKED` + `horizontalBias = 0.5` | `Arrangement.Center` |
 * | 行高 `matchConstraintPercentHeight = 1/rowCount` | 每行 `Modifier.weight(1f)` |
 * | `layoutMarginLeft/Right`（expandKeypressArea） | [KeyInsets] 的单侧内缩 |
 * | 分体键盘 `leftInGroup/gapInGroup/rightInGroup` | 组内 `weight` 分配 |
 *
 * **恒为 LTR**：View 侧用的是绝对 `left/right` 约束（`leftOfParent` / `leftToRightOf`），
 * 在 RTL 语言下不镜像；Compose 的 `Row` 默认跟随 `LocalLayoutDirection`，故每行显式固定 LTR。
 * 固定 LTR 后 `start/end` 等价于 `left/right`，`KeyInsets` 的单侧内缩不会翻边。
 */

/** 键内容槽：由容器提供，拿到稳定 id、单侧内缩与尺寸修饰符。 */
typealias ComposeKeySlot = @Composable (
    keyId: Int,
    def: KeyDef,
    insets: KeyInsets,
    modifier: Modifier,
) -> Unit

/** 键 id 的行内步长：行内最多几十个键，足够避免跨行撞号（D6）。 */
private const val KeyIdRowStride = 100

/**
 * 一行键（对应 `BaseKeyboard.createKeyRow`）。
 *
 * @param widthScale 分体键盘把半行重新归一化到组内宽度，整行传 1f
 * @param allowExpand 分体键盘的半行不参与「扩展触摸区」（View 传 false）
 */
@Composable
fun ComposeKeyRow(
    row: List<KeyDef>,
    modifier: Modifier = Modifier,
    widthScale: Float = 1f,
    allowExpand: Boolean = true,
    keyIdBase: Int = 0,
    key: ComposeKeySlot,
) {
    if (row.isEmpty()) return
    val visuals = rememberKeyboardVisuals()
    val expandKeypressArea =
        remember { AppPrefs.getInstance().keyboard.expandKeypressArea.getValue() }
    val slots = remember(row, widthScale, allowExpand, expandKeypressArea) {
        computeKeyRowSlots(
            row = row,
            widthScale = widthScale,
            allowExpand = allowExpand,
            expandKeypressArea = expandKeypressArea,
        )
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(modifier = modifier) {
            val rowWidth = maxWidth
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                slots.forEachIndexed { index, slot ->
                    val def = row[index]
                    val keyWidth = rowWidth * slot.percentWidth
                    val insets = keyInsets(
                        visuals = visuals,
                        def = def,
                        keyWidth = keyWidth,
                        marginStart = slot.marginStart,
                        marginEnd = slot.marginEnd,
                    )
                    val sizeModifier = if (slot.percentWidth == 0f) {
                        // 0f = 占满剩余空间（View 里 layout_width=0 的 spread 语义）
                        Modifier.weight(1f)
                    } else {
                        Modifier.width(keyWidth)
                    }
                    key(
                        keyIdBase + index,
                        def,
                        insets,
                        sizeModifier.fillMaxHeight(),
                    )
                }
            }
        }
    }
}

/**
 * 分体键盘的一行（对应 `BaseKeyboard.createSplitRowWithGap` / `createSpaceSplitRow`）。
 *
 * 含空格行：空格键独立占中间剩余宽度，左右两半各按 `1 - gapRatio` 收缩；
 * 非空格行：整组居中占 [groupPercent]，组内左右半 + 缝隙按比例分配。
 */
@Composable
fun ComposeSplitKeyRow(
    row: List<KeyDef>,
    gapRatio: Float,
    groupPercent: Float,
    modifier: Modifier = Modifier,
    keyIdBase: Int = 0,
    key: ComposeKeySlot,
) {
    if (row.isEmpty()) return
    val visuals = rememberKeyboardVisuals()

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(modifier = modifier) {
            val rowWidth = maxWidth
            if (rowContainsSpaceKey(row)) {
                val spec = remember(row, gapRatio) { computeSpaceSplitSpec(row, gapRatio) }
                if (spec == null) {
                    ComposeKeyRow(row, Modifier.fillMaxSize(), keyIdBase = keyIdBase, key = key)
                    return@BoxWithConstraints
                }
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(spec.leftWidth.weightSafe())) {
                        ComposeKeyRow(
                            row = spec.leftRow,
                            modifier = Modifier.fillMaxSize(),
                            widthScale = spec.leftScale,
                            allowExpand = false,
                            keyIdBase = keyIdBase,
                            key = key,
                        )
                    }
                    val spaceInsets = keyInsets(visuals, spec.spaceDef, rowWidth * spec.spaceWidth)
                    key(
                        keyIdBase + spec.leftRow.size,
                        spec.spaceDef,
                        spaceInsets,
                        Modifier
                            .weight(spec.spaceWidth.weightSafe())
                            .fillMaxHeight(),
                    )
                    Box(Modifier.weight(spec.rightWidth.weightSafe())) {
                        ComposeKeyRow(
                            row = spec.rightRow,
                            modifier = Modifier.fillMaxSize(),
                            widthScale = spec.rightScale,
                            allowExpand = false,
                            keyIdBase = keyIdBase + spec.leftRow.size + 1,
                            key = key,
                        )
                    }
                }
            } else {
                val spec = remember(row, gapRatio, groupPercent) {
                    computeSplitRowSpec(row, gapRatio, groupPercent)
                }
                Row(
                    modifier = Modifier
                        .width(rowWidth * spec.groupPercent)
                        .fillMaxHeight()
                        .align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 左右半各向缝隙突出 halfKeyInGroup（奇数行的「共享中间键」错列）
                    Box(Modifier.weight((spec.leftInGroup + spec.halfKeyInGroup).weightSafe())) {
                        ComposeKeyRow(
                            row = spec.leftRow,
                            modifier = Modifier.fillMaxSize(),
                            widthScale = spec.leftScale,
                            allowExpand = false,
                            keyIdBase = keyIdBase,
                            key = key,
                        )
                    }
                    // View 里缝隙 = gapInGroup - 2 * halfKeyInGroup，可能为负（此时会被钳到 0），
                    // 这里同样钳零：差额由相邻键在 weight 归一化里分摊，观感与「向缝隙突出」一致。
                    Box(
                        Modifier.weight(
                            (spec.gapInGroup - 2f * spec.halfKeyInGroup).weightSafe()
                        )
                    )
                    Box(Modifier.weight((spec.rightInGroup + spec.halfKeyInGroup).weightSafe())) {
                        ComposeKeyRow(
                            row = spec.rightRow,
                            modifier = Modifier.fillMaxSize(),
                            widthScale = spec.rightScale,
                            allowExpand = false,
                            keyIdBase = keyIdBase + spec.leftRow.size,
                            key = key,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 整块键盘：行等高，`split` 为真时整块走分体布局。
 *
 * 数字键盘那种「滑块 + 自定义行列」的排布（`NumberKeyboard`）不套这里，
 * 直接用它自己的 Row/Column 组合 [ComposeKeyRow] / [ComposeSplitKeyRow]。
 */
@Composable
fun ComposeKeyboardRows(
    rows: List<List<KeyDef>>,
    modifier: Modifier = Modifier,
    split: Boolean = false,
    gapRatio: Float = 0f,
    key: ComposeKeySlot,
) {
    val groupPercents = remember(rows, split, gapRatio) {
        if (split) computeRowGroupPercents(rows, gapRatio) else emptyList()
    }
    Column(modifier = modifier.fillMaxSize()) {
        rows.forEachIndexed { rowIndex, row ->
            val rowModifier = Modifier
                .fillMaxWidth()
                .weight(1f)
            if (split) {
                ComposeSplitKeyRow(
                    row = row,
                    gapRatio = gapRatio,
                    groupPercent = groupPercents.getOrNull(rowIndex) ?: 1f,
                    modifier = rowModifier,
                    keyIdBase = rowIndex * KeyIdRowStride,
                    key = key,
                )
            } else {
                ComposeKeyRow(
                    row = row,
                    modifier = rowModifier,
                    keyIdBase = rowIndex * KeyIdRowStride,
                    key = key,
                )
            }
        }
    }
}

/**
 * 键的视觉内缩：默认内缩（或特殊形状键的零内缩）+ `expandKeypressArea` 的单侧内缩。
 *
 * View 侧 `KeyView.onSizeChanged` 只给特殊形状键算内缩，而 `layoutMarginLeft/Right`
 * 是加在 `appearanceView` 上的，所以特殊形状键**同样**吃 `expandKeypressArea`。
 */
private fun keyInsets(
    visuals: KeyboardVisuals,
    def: KeyDef,
    keyWidth: androidx.compose.ui.unit.Dp,
    marginStart: Float = 0f,
    marginEnd: Float = 0f,
): KeyInsets {
    val base = if (visuals.usesSpecialKeyShape(def)) {
        KeyInsets.Zero
    } else {
        visuals.defaultInsets(def.appearance.margin)
    }
    return base
        .plusStart(keyWidth * marginStart)
        .plusEnd(keyWidth * marginEnd)
}

/** `Row` 的 `weight` 需要正数；0 或负（分体缝隙被钳零后）时用极小值占位。 */
private fun Float.weightSafe(): Float = if (this > 0f) this else 0.0001f
