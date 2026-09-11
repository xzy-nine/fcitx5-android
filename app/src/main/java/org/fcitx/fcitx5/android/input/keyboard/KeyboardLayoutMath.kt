/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * 键盘网格的纯布局计算：**无 View、无 Compose 依赖**，全部宽度都是「父容器宽度的百分比」
 * （与 `ConstraintLayout.LayoutParams.matchConstraintPercentWidth` 同口径）。
 *
 * 从 [BaseKeyboard] 抽出，目的有二：
 *
 * 1. Compose 实现（`Docs/KeyboardComposePlan.md` 批次 A/B）复用**同一份数字**，
 *    避免 View / Compose 两套实现随时间漂移；
 * 2. 这些算式不碰 `ConstraintLayout`，可以直接单测。
 *
 * 本文件的算式与 `BaseKeyboard` 原实现**逐字对应**，抽出的唯一目的是可复用，
 * 不改变任何数值（批次 A 的验收标准就是「零行为变化」）。
 * 行分组宽度见 [SplitKeyboardLayoutMath]（`computeRowGroupPercents` 等），此处不重复。
 */

/**
 * 一行里单个键的水平槽位。
 *
 * 注意 [marginStart] / [marginEnd] 是**按该键自身宽度归一化**的比例，不是父容器比例——
 * 与 `KeyView.layoutMarginLeft/Right` 同口径（`KeyView.onLayout` 里 `leftMargin = w * 比例`）。
 */
data class KeySlot(
    /** 对应 `matchConstraintPercentWidth` */
    val percentWidth: Float,
    /** 对应 `layoutMarginLeft`，0f 表示无边距 */
    val marginStart: Float = 0f,
    /** 对应 `layoutMarginRight`，0f 表示无边距 */
    val marginEnd: Float = 0f,
)

/**
 * 计算普通行的键槽位，对应 `BaseKeyboard.createKeyRow`。
 *
 * [expandKeypressArea] 为真且该行总宽 < 1 时，把两侧剩余空间均分给首尾键，并让它们
 * **内容内缩**（视觉宽度不变、触摸区变宽）——原实现是通过 `layoutMarginLeft/Right` 实现的。
 *
 * @param widthScale 分体键盘用来把半行重新归一化到组内宽度，整行布局传 1f
 * @param allowExpand 分体键盘的半行不参与扩展（原实现传 `false`）
 */
fun computeKeyRowSlots(
    row: List<KeyDef>,
    widthScale: Float = 1f,
    allowExpand: Boolean = true,
    expandKeypressArea: Boolean = false,
): List<KeySlot> {
    if (row.isEmpty()) return emptyList()
    val slots = row.map { def ->
        val base = def.appearance.percentWidth
        KeySlot(percentWidth = if (base == 0f) 0f else base * widthScale)
    }
    if (!allowExpand || !expandKeypressArea) return slots

    // 0f 表示「占满剩余空间」，按 1f 计入总宽（与原实现一致）
    var totalWidth = 0f
    slots.forEach { totalWidth += if (it.percentWidth != 0f) it.percentWidth else 1f }
    if (totalWidth >= 1f) return slots

    val free = (1f - totalWidth) / 2f
    // 归一化分母用的是**扩展前**的首/尾键宽度（原实现 :382-385 先取值再改宽度）
    val firstScaled = slots.first().percentWidth
    val lastScaled = slots.last().percentWidth
    val list = slots.toMutableList()
    list[0] = list[0].copy(
        percentWidth = firstScaled + free,
        marginStart = if (firstScaled + free > 0f) free / (firstScaled + free) else 0f,
    )
    // 单键行时 first 与 last 是同一个槽位，原实现会让 percentWidth 累加两次，
    // 这里保持同样的行为（读的是已经被加过一次的当前值）。
    val lastIndex = list.lastIndex
    list[lastIndex] = list[lastIndex].copy(
        percentWidth = list[lastIndex].percentWidth + free,
        marginEnd = if (lastScaled + free > 0f) free / (lastScaled + free) else 0f,
    )
    return list
}

/**
 * 分体键盘里「非空格行」的组内宽度分配，对应 `BaseKeyboard.createSplitRowWithGap`。
 *
 * 三个组内占比（[leftInGroup] + [gapInGroup] + [rightInGroup]）在同一行里之和为 1，
 * 直接作为 `matchConstraintPercentWidth` 使用。
 *
 * 奇数行在 `splitRowAtMiddle` 里左右共享中间键，[halfKeyInGroup] 让边界键向缝隙各突出
 * 半个键，形成分体键盘的阶梯错列。
 */
data class SplitRowSpec(
    val leftRow: List<KeyDef>,
    val rightRow: List<KeyDef>,
    /** 整组占父容器的比例 */
    val groupPercent: Float,
    val leftInGroup: Float,
    val gapInGroup: Float,
    val rightInGroup: Float,
    val leftScale: Float,
    val rightScale: Float,
    val halfKeyInGroup: Float,
)

fun computeSplitRowSpec(
    row: List<KeyDef>,
    gapRatio: Float,
    groupPercent: Float,
): SplitRowSpec {
    val (leftRow, rightRow) = splitRowAtMiddle(row)
    val leftRaw = splitRowWidthPercent(leftRow)
    val rightRaw = splitRowWidthPercent(rightRow)
    val totalRaw = (leftRaw + rightRaw).coerceAtLeast(1e-6f)
    val scale = (groupPercent / totalRaw).coerceAtLeast(0f)
    val leftWidth = leftRaw * scale
    val rightWidth = rightRaw * scale
    val groupTotal = (leftWidth + rightWidth + gapRatio).coerceAtMost(1f)
    val protrude = row.size % 2 == 1
    return SplitRowSpec(
        leftRow = leftRow,
        rightRow = rightRow,
        groupPercent = groupTotal,
        leftInGroup = if (groupTotal > 0f) leftWidth / groupTotal else 0f,
        gapInGroup = if (groupTotal > 0f) gapRatio / groupTotal else 0f,
        rightInGroup = if (groupTotal > 0f) rightWidth / groupTotal else 0f,
        leftScale = if (leftRaw > 0f) 1f / leftRaw else 1f,
        rightScale = if (rightRaw > 0f) 1f / rightRaw else 1f,
        halfKeyInGroup = if (protrude && groupTotal > 0f) 0.05f * scale / groupTotal else 0f,
    )
}

/**
 * 分体键盘里「含空格行」的宽度分配，对应 `BaseKeyboard.createSpaceSplitRow`。
 *
 * 与 [SplitRowSpec] 的差别：空格键**不参与** `splitRowAtMiddle` 均分，而是独立占据
 * 中间剩余宽度，左右两侧各按 `1 - gapRatio` 收缩。
 */
data class SpaceSplitSpec(
    val leftRow: List<KeyDef>,
    val spaceDef: KeyDef,
    val rightRow: List<KeyDef>,
    val leftWidth: Float,
    val spaceWidth: Float,
    val rightWidth: Float,
    val leftScale: Float,
    val rightScale: Float,
)

/** 该行没有空格键时返回 null（调用方回退到 [computeKeyRowSlots]）。 */
fun computeSpaceSplitSpec(row: List<KeyDef>, gapRatio: Float): SpaceSplitSpec? {
    // 按 viewId 判空格键（键面变换会重建 KeyDef、丢掉子类类型），见 isSpaceKeyDef
    val spaceIndex = row.indexOfFirst { isSpaceKeyDef(it) }
    if (spaceIndex < 0) return null
    val leftRow = row.subList(0, spaceIndex)
    val rightRow = row.subList(spaceIndex + 1, row.size)
    val leftRaw = splitRowWidthPercent(leftRow)
    val rightRaw = splitRowWidthPercent(rightRow)
    val ratio = (1f - gapRatio).coerceAtLeast(0f)
    val leftWidth = (leftRaw * ratio).coerceAtLeast(0f)
    val rightWidth = (rightRaw * ratio).coerceAtLeast(0f)
    return SpaceSplitSpec(
        leftRow = leftRow,
        spaceDef = row[spaceIndex],
        rightRow = rightRow,
        leftWidth = leftWidth,
        spaceWidth = (1f - leftWidth - rightWidth).coerceAtLeast(0f),
        rightWidth = rightWidth,
        leftScale = if (leftRaw > 0f) 1f / leftRaw else 1f,
        rightScale = if (rightRaw > 0f) 1f / rightRaw else 1f,
    )
}

/**
 * 空白比例偏好（百分比整数，`split_keyboard_blank_ratio`）→ 缝隙比例。
 * 对应 `BaseKeyboard.splitGapRatio()`（改名以避免与同名成员函数混淆）。
 */
fun gapRatioFromBlankPercent(blankRatioPercent: Int): Float =
    (blankRatioPercent / 100f).coerceIn(0f, 0.9f)

/**
 * 宽高比超过阈值才允许分体键盘。高度未知（<= 0，旋转/配置变更时的瞬时回调）时返回 false。
 * 对应 `BaseKeyboard.isSplitAllowed()`（改名以避免与同名成员函数混淆）。
 */
fun isSplitAllowedByRatio(width: Int, height: Int, threshold: Float): Boolean =
    height > 0 && (width.toFloat() / height.toFloat()) > threshold

/**
 * 每行高度占比。原实现用百分比高度（而非 `0dp + 均分`）来保证「重建发生在 layout pass 中」
 * 时最后一行不会塌成 0 高度（见 `BaseKeyboard` :159-163 的注释）。
 */
fun rowHeightPercent(rowCount: Int): Float = if (rowCount <= 0) 0f else 1f / rowCount
