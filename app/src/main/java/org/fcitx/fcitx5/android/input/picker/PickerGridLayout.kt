/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android contributors
 */
package org.fcitx.fcitx5.android.input.picker

/**
 * Picker 中栏单网格的「槽位」布局（纯计算，可单测，与 `ExpandedCandidatePageTarget` 同类）。
 *
 * 网格里放的**不只是候选**：不同分类之间要「换页」——插入 [GAP] 行空行，让下一个分类
 * 从新的一行开始（而不是紧贴上一个分类的最后一行继续排）。因此「第 i 个分类的第 j 个候选」
 * 与「网格第 k 个槽位」不是同一个下标空间，锚点（点左栏标签滚到哪里）也必须按槽位算。
 *
 * - [PickerGridSlot.section] = 候选所属分类下标；空行占位为 [-NO_SECTION]
 * - [PickerGridSlot.itemIndex] = 候选在该分类内的下标；空行占位为负
 * - [PickerGridLayout.sectionStarts] = 各分类**首项**的槽位下标，即左栏标签的滚动锚点
 *   （锚点指向首项而非空行，点标签后该分类正好贴顶）
 */
internal class PickerGridSlot(
    val section: Int,
    val itemIndex: Int,
) {
    /** 是否分类之间的空行占位（无内容、不可点）。 */
    val isGap: Boolean get() = itemIndex < 0

    companion object {
        /** 空行占位不隶属任何分类。 */
        const val NO_SECTION = -1
    }
}

internal class PickerGridLayout(
    /** 参与排布的分类（顺序即展示顺序）。 */
    val sections: List<PickerPageModel.FlatCategory>,
    val slots: List<PickerGridSlot>,
    /** 各分类首项在 [slots] 中的下标（长度与分类数一致）。 */
    val sectionStarts: IntArray,
)

/** 分类之间的换页空行数（改这里即可调整间隔）。 */
internal const val PickerCategoryGapRows = 1

/**
 * 把分类列表铺成网格槽位：`分类0 的候选 → GAP 行空行 → 分类1 的候选 → …`。
 *
 * 空行只在**前后两个分类都有内容**时插入：空分类（可能出现的「⟳最近使用」为空）不该
 * 在网格顶部/中间留下一段无意义的空白。
 *
 * @param sections 平铺分类（见 [PickerPageModel.flatCategories]）
 * @param gapRows 分类之间的空行数（0 = 紧接排布，不换页）
 * @return [PickerGridLayout]；分类为空时 [PickerGridLayout.slots] 也为空
 */
internal fun buildPickerGridLayout(
    sections: List<PickerPageModel.FlatCategory>,
    gapRows: Int = PickerCategoryGapRows,
): PickerGridLayout {
    val gap = gapRows.coerceAtLeast(0)
    val slots = ArrayList<PickerGridSlot>(
        sections.sumOf { it.items.size } + gap * sections.size.coerceAtLeast(0)
    )
    val starts = IntArray(sections.size)
    sections.forEachIndexed { sectionIndex, section ->
        if (sectionIndex > 0 && gap > 0 &&
            sections[sectionIndex - 1].items.isNotEmpty() && section.items.isNotEmpty()
        ) {
            repeat(gap) { slots.add(PickerGridSlot(PickerGridSlot.NO_SECTION, -1)) }
        }
        // 锚点指向该分类首项（空分类则与下一个分类同锚点，不会指向空行）
        starts[sectionIndex] = slots.size
        section.items.forEachIndexed { itemIndex, _ ->
            slots.add(PickerGridSlot(sectionIndex, itemIndex))
        }
    }
    return PickerGridLayout(sections, slots, starts)
}
