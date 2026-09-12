/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.expanded

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [computePageTargetIndex] 的语义：两个方向都是**一整屏行数**的位移。
 * 重点锁住「上翻不能退化成一行」这个回归点，以及上/下翻可往返、贴顶贴底不越界。
 */
class ExpandedCandidatePageTargetTest {

    /** 模拟行对齐的表格：视口顶行 = [topRow]，视口内完全可见 [fullyVisibleRows] 行。 */
    private fun page(
        forward: Boolean,
        topRow: Int,
        fullyVisibleRows: Int,
        columns: Int,
        totalItemsCount: Int,
    ): Int {
        val firstVisibleIndex = topRow * columns
        // 最后一个完全可见项 = 最后一行完全可见行的末项
        val lastVisibleIndex = (topRow + fullyVisibleRows) * columns - 1
        return computePageTargetIndex(
            firstVisibleIndex = firstVisibleIndex,
            lastVisibleIndex = lastVisibleIndex,
            columns = columns,
            totalItemsCount = totalItemsCount,
            forward = forward,
        )
    }

    @Test
    fun pageUpMovesAFullPageNotOneRow() {
        // 3 列、一屏 4 行、当前顶行是第 8 行（视口显示第 8~11 行）
        val target = page(forward = false, topRow = 8, fullyVisibleRows = 4, columns = 3, totalItemsCount = 300)
        // 目标 = 第 4 行行首（往前整整 4 行 = 一屏）
        assertEquals(4 * 3, target)
        // 旧实现（第一个完全可见项 - 1）会落到上一行末项，也就是「只滚一行」
        assertTrue("上翻必须多于一行的位移", target < 8 * 3 - 3)
    }

    @Test
    fun pageDownMovesAFullPage() {
        // 与上翻对称：最后一个完全可见行的下一行行首
        val target = page(forward = true, topRow = 4, fullyVisibleRows = 4, columns = 3, totalItemsCount = 300)
        assertEquals(8 * 3, target)
    }

    @Test
    fun pageUpAndPageDownRoundTrip() {
        // 第 4~7 行完全可见 -> 下翻到第 8 行；第 8~11 行完全可见 -> 上翻应回到第 4 行
        val down = page(forward = true, topRow = 4, fullyVisibleRows = 4, columns = 3, totalItemsCount = 300)
        val downTopRow = down / 3
        val up = page(forward = false, topRow = downTopRow, fullyVisibleRows = 4, columns = 3, totalItemsCount = 300)
        assertEquals(4 * 3, up)
    }

    @Test
    fun pageUpClampsAtTop() {
        // 第一行就完全可见且不足一屏，上翻只能停在第一项
        assertEquals(0, page(forward = false, topRow = 0, fullyVisibleRows = 3, columns = 3, totalItemsCount = 300))
        assertEquals(0, page(forward = false, topRow = 1, fullyVisibleRows = 4, columns = 3, totalItemsCount = 300))
    }

    @Test
    fun pageDownClampsAtLastItem() {
        // 末行不满（只剩两个 item）时，下翻钳到最后一项而不是越界
        assertEquals(
            25,
            computePageTargetIndex(
                firstVisibleIndex = 24,
                lastVisibleIndex = 25,
                columns = 3,
                totalItemsCount = 26,
                forward = true,
            ),
        )
    }

    @Test
    fun singleFullyVisibleRowMovesExactlyOneRow() {
        // 一屏只放得下一行（格子很高）时，「一页」就是一行
        assertEquals(6, computePageTargetIndex(5, 5, columns = 1, totalItemsCount = 100, forward = true))
        assertEquals(4, computePageTargetIndex(5, 5, columns = 1, totalItemsCount = 100, forward = false))
    }

    @Test
    fun emptyListReturnsZero() {
        assertEquals(0, computePageTargetIndex(0, 0, columns = 3, totalItemsCount = 0, forward = true))
        assertEquals(0, computePageTargetIndex(0, 0, columns = 3, totalItemsCount = 0, forward = false))
    }

    @Test
    fun nonPositiveColumnsDegradesToOneColumn() {
        assertEquals(6, computePageTargetIndex(5, 5, columns = 0, totalItemsCount = 100, forward = true))
        assertEquals(4, computePageTargetIndex(5, 5, columns = 0, totalItemsCount = 100, forward = false))
    }

    @Test
    fun negativeIndicesAreClamped() {
        // 异常/瞬态下标不应产生越界结果
        assertEquals(0, computePageTargetIndex(-3, -1, columns = 3, totalItemsCount = 100, forward = true))
        assertEquals(0, computePageTargetIndex(-3, -1, columns = 3, totalItemsCount = 100, forward = false))
    }

    @Test
    fun partialTopRowStillPagesByRows() {
        // 顶行被裁掉一部分时，第一个完全可见行是下一行：上翻落在「该行往前一屏行数」的行首
        val target = computePageTargetIndex(
            firstVisibleIndex = 4 * 3, // 第一个完全可见项在第 4 行
            lastVisibleIndex = 7 * 3 + 2, // 第 7 行完全可见
            columns = 3,
            totalItemsCount = 300,
            forward = false,
        )
        assertEquals(0, target)
    }
}
