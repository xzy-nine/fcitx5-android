/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.picker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [buildPickerGridLayout] 的语义：分类之间插「换页空行」、锚点指向各分类首项、
 * 空分类不产生空行也不占槽位。
 *
 * 锚点是左栏点击 → `LazyVerticalGrid.animateScrollToItem` 的唯一依据，算错就会滚到别的分类；
 * 空行槽位则决定了下个分类是否从新的一行开始，所以两条都要锁住。
 */
class PickerGridLayoutTest {

    private fun section(label: String, itemCount: Int, hasPopups: Boolean = true) =
        PickerPageModel.FlatCategory(
            category = PickerData.Category(label),
            items = List(itemCount) { "$label$it" },
            hasPopups = hasPopups,
        )

    @Test
    fun `gap slot is inserted between non-empty sections`() {
        val layout = buildPickerGridLayout(
            sections = listOf(section("a", 2), section("b", 3)),
            gapRows = 1,
        )
        // a0 a1 [gap] b0 b1 b2
        assertEquals(6, layout.slots.size)
        assertEquals(listOf(0, 3), layout.sectionStarts.toList())
        assertFalse(layout.slots[0].isGap)
        assertTrue(layout.slots[2].isGap)
        assertEquals(PickerGridSlot.NO_SECTION, layout.slots[2].section)
        assertFalse(layout.slots[3].isGap)
    }

    @Test
    fun `anchor points at the first item of each section`() {
        val sections = listOf(section("a", 3), section("b", 1), section("c", 2))
        val layout = buildPickerGridLayout(sections, gapRows = 1)
        layout.sectionStarts.forEachIndexed { index, start ->
            val slot = layout.slots[start]
            assertFalse("锚点不能落在空行上", slot.isGap)
            assertEquals(index, slot.section)
            assertEquals(0, slot.itemIndex)
        }
        // a0 a1 a2 [gap] b0 [gap] c0 c1
        assertEquals(listOf(0, 4, 6), layout.sectionStarts.toList())
        assertEquals(8, layout.slots.size)
    }

    @Test
    fun `multiple gap rows are supported`() {
        val layout = buildPickerGridLayout(
            sections = listOf(section("a", 1), section("b", 1)),
            gapRows = 2,
        )
        assertEquals(4, layout.slots.size)
        assertEquals(listOf(0, 3), layout.sectionStarts.toList())
        assertTrue(layout.slots[1].isGap)
        assertTrue(layout.slots[2].isGap)
    }

    @Test
    fun `zero gap rows keeps sections adjacent`() {
        val layout = buildPickerGridLayout(
            sections = listOf(section("a", 2), section("b", 2)),
            gapRows = 0,
        )
        assertEquals(4, layout.slots.size)
        assertEquals(listOf(0, 2), layout.sectionStarts.toList())
        assertTrue(layout.slots.none { it.isGap })
    }

    @Test
    fun `empty section adds no gap and shares the next anchor`() {
        // 「⟳最近使用」为空：不应在网格顶部留一段空行
        val layout = buildPickerGridLayout(
            sections = listOf(section("⟳", 0, hasPopups = false), section("a", 2)),
            gapRows = 1,
        )
        assertEquals(2, layout.slots.size)
        assertEquals(listOf(0, 0), layout.sectionStarts.toList())
        assertTrue(layout.slots.none { it.isGap })
    }

    @Test
    fun `slots keep section and item indices`() {
        val layout = buildPickerGridLayout(
            sections = listOf(section("a", 2), section("b", 2)),
            gapRows = 1,
        )
        val mapped = layout.slots.map { if (it.isGap) "gap" else "${it.section}:${it.itemIndex}" }
        assertEquals(listOf("0:0", "0:1", "gap", "1:0", "1:1"), mapped)
    }

    @Test
    fun `single section has no gap`() {
        val layout = buildPickerGridLayout(sections = listOf(section("a", 4)), gapRows = 1)
        assertEquals(4, layout.slots.size)
        assertEquals(listOf(0), layout.sectionStarts.toList())
    }

    @Test
    fun `no sections yields empty layout`() {
        val layout = buildPickerGridLayout(sections = emptyList(), gapRows = 1)
        assertTrue(layout.slots.isEmpty())
        assertEquals(0, layout.sectionStarts.size)
    }
}
