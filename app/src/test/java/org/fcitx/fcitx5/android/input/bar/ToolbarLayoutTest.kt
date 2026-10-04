/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.bar

import org.fcitx.fcitx5.android.input.bar.ToolbarButton.Clipboard
import org.fcitx.fcitx5.android.input.bar.ToolbarButton.Handwriting
import org.fcitx.fcitx5.android.input.bar.ToolbarButton.SplitKeyboard
import org.fcitx.fcitx5.android.input.bar.ToolbarButton.TextEditing
import org.fcitx.fcitx5.android.input.bar.ToolbarButton.Tune
import org.fcitx.fcitx5.android.input.bar.ToolbarButton.Voice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具栏布局的编解码、自愈与拖动落点计算。
 *
 * 落点函数只吃原始坐标，故全部可在 JVM 单测里覆盖，包括 RTL 与等距等边界。
 */
class ToolbarLayoutTest {

    private fun rect(cx: Float, cy: Float, half: Float = 10f) =
        ToolbarLayout.SlotRect(cx - half, cy - half, cx + half, cy + half)

    // ---------------------------------------------------------------- 默认值

    @Test
    fun `default docked order matches the expected toolbar order`() {
        assertEquals(
            listOf(TextEditing, Clipboard, Tune, Voice, Handwriting),
            ToolbarLayout.DEFAULT_DOCKED
        )
    }

    @Test
    fun `default pool holds only the split keyboard button`() {
        assertEquals(listOf(SplitKeyboard), ToolbarLayout.DEFAULT_POOL)
    }

    @Test
    fun `default layout is a partition of all buttons`() {
        val all = ToolbarLayout.DEFAULT.docked + ToolbarLayout.DEFAULT.pool
        assertEquals(ToolbarLayout.ALL.toSet(), all.toSet())
        assertEquals(ToolbarLayout.ALL.size, all.size)
    }

    // ---------------------------------------------------------------- 编解码

    @Test
    fun `encode then decode round trips`() {
        val list = listOf(Tune, SplitKeyboard, TextEditing)
        assertEquals(list, ToolbarLayout.decode(ToolbarLayout.encode(list)))
    }

    @Test
    fun `decode drops unknown ids`() {
        assertEquals(listOf(Tune), ToolbarLayout.decode("tune,ghost_button,another"))
    }

    @Test
    fun `decode of a blank string yields an empty list`() {
        assertTrue(ToolbarLayout.decode("").isEmpty())
    }

    // ---------------------------------------------------------------- 自愈

    @Test
    fun `sanitize keeps a valid stored layout untouched`() {
        val docked = ToolbarLayout.encode(listOf(Tune, Clipboard))
        val pool = ToolbarLayout.encode(listOf(TextEditing, Voice, Handwriting, SplitKeyboard))
        val state = ToolbarLayout.sanitize(docked, pool)
        assertEquals(listOf(Tune, Clipboard), state.docked)
        assertEquals(listOf(TextEditing, Voice, Handwriting, SplitKeyboard), state.pool)
        assertFalse(ToolbarLayout.needsRewrite(state, docked, pool))
    }

    @Test
    fun `sanitize drops duplicates inside a list`() {
        val state = ToolbarLayout.sanitize("tune,tune,clipboard", "voice")
        assertEquals(listOf(Tune, Clipboard), state.docked)
    }

    @Test
    fun `sanitize removes a button that appears in both lists`() {
        val state = ToolbarLayout.sanitize("tune,clipboard", "clipboard,voice")
        assertEquals(listOf(Tune, Clipboard), state.docked)
        // 冲突项留在 docked，pool 里被剔除；缺失项再按默认顺序补齐
        assertEquals(listOf(Voice, TextEditing, Handwriting, SplitKeyboard), state.pool)
        assertTrue(Clipboard !in state.pool)
    }

    @Test
    fun `sanitize refills buttons missing from both lists into the pool`() {
        val state = ToolbarLayout.sanitize("tune", "voice")
        // 缺失项按默认顺序补齐，且不与已停靠项重复
        assertEquals(listOf(Tune), state.docked)
        assertEquals(
            listOf(Voice, TextEditing, Clipboard, Handwriting, SplitKeyboard),
            state.pool
        )
    }

    @Test
    fun `sanitize always covers every button exactly once`() {
        val cases = listOf(
            "" to "",
            "ghost" to "ghost",
            ToolbarLayout.encode(ToolbarLayout.ALL) to "",
            "" to ToolbarLayout.encode(ToolbarLayout.ALL),
            "split_keyboard" to "split_keyboard",
        )
        cases.forEach { (dockedRaw, poolRaw) ->
            val state = ToolbarLayout.sanitize(dockedRaw, poolRaw)
            val combined = state.docked + state.pool
            assertEquals(
                "docked=$dockedRaw pool=$poolRaw 应覆盖全集",
                ToolbarLayout.ALL.toSet(),
                combined.toSet()
            )
            assertEquals("docked=$dockedRaw pool=$poolRaw 不应重复", ToolbarLayout.ALL.size, combined.size)
            assertTrue("两列表必须互斥", state.docked.none { it in state.pool })
        }
    }

    @Test
    fun `everything may be moved into the pool leaving the toolbar empty`() {
        val state = ToolbarLayout.sanitize("", ToolbarLayout.encode(ToolbarLayout.ALL))
        assertTrue(state.docked.isEmpty())
        assertEquals(ToolbarLayout.ALL.toSet(), state.pool.toSet())
    }

    @Test
    fun `needsRewrite reports an orphan id so the caller can self heal`() {
        val state = ToolbarLayout.sanitize("tune,ghost", ToolbarLayout.encode(ToolbarLayout.DEFAULT_POOL))
        assertTrue(ToolbarLayout.needsRewrite(state, "tune,ghost", ToolbarLayout.encode(ToolbarLayout.DEFAULT_POOL)))
    }

    // ---------------------------------------------------------------- 移动

    @Test
    fun `moveToDocked inserts at the requested index and leaves the pool`() {
        val state = ToolbarLayoutState(
            docked = listOf(TextEditing, Clipboard),
            pool = listOf(Tune, Voice),
        )
        val moved = ToolbarLayout.moveToDocked(state, Tune, 1)
        assertEquals(listOf(TextEditing, Tune, Clipboard), moved.docked)
        assertEquals(listOf(Voice), moved.pool)
    }

    @Test
    fun `moveToDocked appends when the index is past the end`() {
        val state = ToolbarLayoutState(docked = listOf(TextEditing), pool = listOf(Tune))
        assertEquals(listOf(TextEditing, Tune), ToolbarLayout.moveToDocked(state, Tune, 99).docked)
    }

    @Test
    fun `moveToDocked clamps a negative index to the front`() {
        val state = ToolbarLayoutState(docked = listOf(TextEditing), pool = listOf(Tune))
        assertEquals(listOf(Tune, TextEditing), ToolbarLayout.moveToDocked(state, Tune, -5).docked)
    }

    @Test
    fun `reordering inside the docked list produces the visual drop position`() {
        val state = ToolbarLayoutState(
            docked = listOf(TextEditing, Clipboard, Tune),
            pool = emptyList(),
        )
        // 索引按「先摘除自身」解释：把第一个拖到末尾 = 剩余 2 项之后 = 索引 2
        assertEquals(
            listOf(Clipboard, Tune, TextEditing),
            ToolbarLayout.moveToDocked(state, TextEditing, 2).docked
        )
        // 把最后一个拖到最前 = 索引 0
        assertEquals(
            listOf(Tune, TextEditing, Clipboard),
            ToolbarLayout.moveToDocked(state, Tune, 0).docked
        )
    }

    @Test
    fun `reordering inside the docked list is lossless`() {
        val state = ToolbarLayoutState(
            docked = listOf(TextEditing, Clipboard, Tune, Voice),
            pool = listOf(Handwriting, SplitKeyboard),
        )
        // Clipboard 摘除后剩余 [TextEditing, Tune, Voice]，插到索引 1 即回到原位
        assertEquals(state.docked, ToolbarLayout.moveToDocked(state, Clipboard, 1).docked)
        // 空操作不应影响池子
        assertEquals(state.pool, ToolbarLayout.moveToDocked(state, Clipboard, 1).pool)
    }

    @Test
    fun `moveToPool inserts at the requested index and leaves the toolbar`() {
        val state = ToolbarLayoutState(
            docked = listOf(TextEditing, Clipboard),
            pool = listOf(Tune),
        )
        val moved = ToolbarLayout.moveToPool(state, TextEditing, 1)
        assertEquals(listOf(Clipboard), moved.docked)
        assertEquals(listOf(Tune, TextEditing), moved.pool)
    }

    @Test
    fun `moveToPool keeps the partition intact for any index`() {
        var state = ToolbarLayout.DEFAULT
        state = ToolbarLayout.moveToPool(state, TextEditing, 0)
        state = ToolbarLayout.moveToPool(state, Clipboard, 99)
        state = ToolbarLayout.moveToDocked(state, Clipboard, 0)
        val combined = state.docked + state.pool
        assertEquals(ToolbarLayout.ALL.toSet(), combined.toSet())
        assertEquals(ToolbarLayout.ALL.size, combined.size)
    }

    // ---------------------------------------------------------------- 横向落点

    @Test
    fun `horizontal insertion into an empty row is always zero`() {
        assertEquals(0, ToolbarLayout.insertionIndexByCenterX(emptyList(), x = 500f, rtl = false))
    }

    @Test
    fun `horizontal insertion at the edges`() {
        val centers = listOf(100f, 200f, 300f)
        assertEquals(0, ToolbarLayout.insertionIndexByCenterX(centers, x = -50f, rtl = false))
        assertEquals(3, ToolbarLayout.insertionIndexByCenterX(centers, x = 9999f, rtl = false))
    }

    @Test
    fun `horizontal insertion decides by which half of the nearest slot was hit`() {
        val centers = listOf(100f, 200f, 300f)
        // 偏左半 → 插在该槽之前
        assertEquals(1, ToolbarLayout.insertionIndexByCenterX(centers, x = 180f, rtl = false))
        // 偏右半 → 插在该槽之后
        assertEquals(2, ToolbarLayout.insertionIndexByCenterX(centers, x = 220f, rtl = false))
    }

    @Test
    fun `horizontal insertion exactly on a center goes before that slot`() {
        val centers = listOf(100f, 200f)
        assertEquals(1, ToolbarLayout.insertionIndexByCenterX(centers, x = 200f, rtl = false))
        assertEquals(0, ToolbarLayout.insertionIndexByCenterX(centers, x = 100f, rtl = false))
    }

    @Test
    fun `horizontal insertion mirrors in an RTL layout`() {
        // centers 按列表顺序；RTL 下列表第 0 项画在最右，故「中心左侧」= 插在其后
        val centers = listOf(300f, 200f, 100f)
        // 落在最右槽（列表第 0 项）右侧 = 插到列表最前
        assertEquals(0, ToolbarLayout.insertionIndexByCenterX(centers, x = 320f, rtl = true))
        // 落在最右槽中心左侧 = 插到列表第 0 项之后
        assertEquals(1, ToolbarLayout.insertionIndexByCenterX(centers, x = 280f, rtl = true))
        // 反向对照：同样的坐标在 LTR 下给出相反结果
        assertEquals(1, ToolbarLayout.insertionIndexByCenterX(centers, x = 320f, rtl = false))
        assertEquals(0, ToolbarLayout.insertionIndexByCenterX(centers, x = 280f, rtl = false))
    }

    @Test
    fun `horizontal insertion result is never out of range`() {
        val centers = listOf(100f, 200f, 300f)
        (-500..800 step 7).forEach { x ->
            val index = ToolbarLayout.insertionIndexByCenterX(centers, x.toFloat(), rtl = false)
            assertTrue("x=$x -> $index", index in 0..centers.size)
        }
    }

    // ---------------------------------------------------------------- 网格落点

    @Test
    fun `grid insertion into an empty pool is always zero`() {
        assertEquals(0, ToolbarLayout.insertionIndexByCellBounds(emptyList(), x = 10f, y = 10f))
    }

    @Test
    fun `grid insertion picks the nearest cell`() {
        // 两行两列：索引顺序为行主序
        val cells = listOf(
            rect(50f, 50f), rect(150f, 50f),
            rect(50f, 150f), rect(150f, 150f),
        )
        assertEquals(0, ToolbarLayout.insertionIndexByCellBounds(cells, x = 40f, y = 45f))
        assertEquals(1, ToolbarLayout.insertionIndexByCellBounds(cells, x = 130f, y = 45f))
        assertEquals(2, ToolbarLayout.insertionIndexByCellBounds(cells, x = 40f, y = 160f))
        assertEquals(3, ToolbarLayout.insertionIndexByCellBounds(cells, x = 145f, y = 200f))
    }

    @Test
    fun `grid insertion decides by which half of the nearest cell was hit`() {
        val cells = listOf(rect(50f, 50f), rect(150f, 50f))
        // 落在第一格偏右 → 插在其后
        assertEquals(1, ToolbarLayout.insertionIndexByCellBounds(cells, x = 58f, y = 50f))
        // 落在第一格偏左 → 插在其前
        assertEquals(0, ToolbarLayout.insertionIndexByCellBounds(cells, x = 42f, y = 50f))
    }

    @Test
    fun `grid insertion still resolves a point outside every cell`() {
        val cells = listOf(rect(50f, 50f), rect(150f, 50f))
        // 越出网格左上角 → 最近的是第一格，且落在其左侧
        assertEquals(0, ToolbarLayout.insertionIndexByCellBounds(cells, x = -400f, y = -400f))
        // 越出网格右下角 → 最近的是最后一格，且落在其右侧
        assertEquals(2, ToolbarLayout.insertionIndexByCellBounds(cells, x = 900f, y = 900f))
    }

    @Test
    fun `grid insertion result is never out of range`() {
        val cells = listOf(rect(50f, 50f), rect(150f, 50f), rect(50f, 150f))
        (-200..400 step 11).forEach { x ->
            (-200..400 step 13).forEach { y ->
                val index = ToolbarLayout.insertionIndexByCellBounds(cells, x.toFloat(), y.toFloat())
                assertTrue("($x,$y) -> $index", index in 0..cells.size)
            }
        }
    }
}
