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
 * [buildFlatCategories] 的语义：最近使用节居首（允许为空）、空分类剔除、顺序保持。
 *
 * 网格槽位（含分类之间的换页空行）与锚点不在本函数里，见 `PickerGridLayoutTest`。
 *
 * 用假的 label/条目即可：本函数与 [PickerData] 的真实符号表无关，只读 [PickerData.RecentlyUsedCategory]。
 */
class PickerPageModelFlatCategoriesTest {

    /** 只造标签：本函数不读 [PickerData] 的真实符号表，标签互不相同即可用 data class 相等性比较。 */
    private fun category(label: String) = PickerData.Category(label)

    private fun items(count: Int, prefix: String) = List(count) { "$prefix$it" }

    @Test
    fun `recent section comes first and has no popups`() {
        val result = buildFlatCategories(
            filteredCategories = listOf(category("a") to items(3, "a")),
            recent = items(2, "r"),
        )
        assertEquals(2, result.size)
        assertEquals(PickerData.RecentlyUsedCategory, result[0].category)
        assertFalse(result[0].hasPopups)
        assertEquals(2, result[0].items.size)
    }

    @Test
    fun `categories keep order and sizes`() {
        val result = buildFlatCategories(
            filteredCategories = listOf(
                category("a") to items(5, "a"),
                category("b") to items(1, "b"),
                category("c") to items(4, "c"),
            ),
            recent = items(3, "r"),
        )
        assertEquals(listOf("⟳", "a", "b", "c"), result.map { it.category.label })
        assertEquals(listOf(3, 5, 1, 4), result.map { it.items.size })
        assertTrue(result.drop(1).all { it.hasPopups })
    }

    @Test
    fun `empty categories are dropped`() {
        val empty = category("empty")
        val result = buildFlatCategories(
            filteredCategories = listOf(
                category("a") to items(2, "a"),
                empty to emptyList(),
                category("c") to items(3, "c"),
            ),
            recent = emptyList(),
        )
        assertEquals(3, result.size)
        assertTrue(result.none { it.category == empty })
    }

    @Test
    fun `empty recent section is kept as the first section`() {
        val result = buildFlatCategories(
            filteredCategories = listOf(category("a") to items(2, "a")),
            recent = emptyList(),
        )
        assertEquals(2, result.size)
        assertEquals(PickerData.RecentlyUsedCategory, result[0].category)
        assertTrue(result[0].items.isEmpty())
    }

    @Test
    fun `no categories yields only the recent section`() {
        val result = buildFlatCategories(filteredCategories = emptyList(), recent = items(1, "r"))
        assertEquals(1, result.size)
        assertEquals(1, result[0].items.size)
    }
}
