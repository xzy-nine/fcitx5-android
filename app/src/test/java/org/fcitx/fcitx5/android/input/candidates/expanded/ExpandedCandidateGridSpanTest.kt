/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.expanded

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [computeGridSpanCount] 的语义：与 View 侧 `SpanHelper` 同口径——按最宽候选的「列单位需求」
 * `ceil(em / 1.5)` 反推整表列数，一行词数不被短词顶满；非法输入必须返回可用的列数而不是 0 或崩溃。
 */
class ExpandedCandidateGridSpanTest {

    @Test
    fun twoCharCandidatesMatchViewSpanCount() {
        // 2 个汉字 ≈ 2em -> 需要 2 个列单位；上限 6 / 2 = 3 列（View 侧同为一行 3 个）
        assertEquals(3, computeGridSpanCount(listOf(1.9f, 2.0f, 2.0f), maxSpan = 6))
    }

    @Test
    fun singleCharCandidatesYieldMaxSpan() {
        // 1 个汉字 ≈ 1em -> 1 个列单位，短词可以铺满上限
        assertEquals(6, computeGridSpanCount(listOf(1.0f, 0.9f, 1.0f), maxSpan = 6))
        assertEquals(12, computeGridSpanCount(listOf(0.5f, 0.6f), maxSpan = 12))
    }

    @Test
    fun wideLeadingCandidateYieldsFewerColumns() {
        // 4 个汉字 ≈ 4em -> ceil(4 / 1.5) = 3 个列单位；6 / 3 = 2 列
        assertEquals(2, computeGridSpanCount(listOf(2.0f, 4.0f, 1.0f), maxSpan = 6))
    }

    @Test
    fun widestBeyondMaxSpanFallsBackToMinSpan() {
        // 长句需要远多于上限的列单位 -> 整表 1 列，被下限兜到 2（长句仍靠缩字号放下）
        assertEquals(2, computeGridSpanCount(listOf(100f), maxSpan = 6))
        assertEquals(3, computeGridSpanCount(listOf(100f), maxSpan = 6, minSpan = 3))
    }

    @Test
    fun columnsNeverExceedMaxSpan() {
        assertEquals(6, computeGridSpanCount(listOf(1f), maxSpan = 6))
        assertEquals(12, computeGridSpanCount(listOf(1f), maxSpan = 12))
    }

    @Test
    fun emptyLeadingWidthsFallBackToMaxSpan() {
        // 首屏文本还没测量出来：先按上限铺满，等文本到达后收敛（此时还没有 item，不会看到跳变）
        assertEquals(12, computeGridSpanCount(emptyList(), maxSpan = 12))
        assertEquals(5, computeGridSpanCount(emptyList(), maxSpan = 5))
    }

    @Test
    fun invalidWidthsAreIgnored() {
        // -10 / NaN / +Inf / 0 全被过滤，只剩 1.0em -> 1 个列单位
        val widths = listOf(-10f, Float.NaN, Float.POSITIVE_INFINITY, 0f, 1f)
        assertEquals(6, computeGridSpanCount(widths, maxSpan = 6))
    }

    @Test
    fun allInvalidWidthsFallBackToMaxSpan() {
        assertEquals(4, computeGridSpanCount(listOf(0f, Float.NaN), maxSpan = 4))
    }

    @Test
    fun maxSpanBelowMinSpanDegradesToMinSpan() {
        // 偏好异常（上限 < 下限）时不崩、不返回 0
        assertEquals(3, computeGridSpanCount(listOf(4.0f), maxSpan = 1, minSpan = 3))
    }

    @Test
    fun nonPositiveMaxSpanDegradesToMinSpan() {
        assertEquals(2, computeGridSpanCount(listOf(1f), maxSpan = 0))
    }

    @Test
    fun sampleCountConstantStaysSmall() {
        // 采样条数只用于「首屏长候选」判断，调大没有收益且会让列数随滚动抖动
        assertEquals(10, ExpandedCandidateLeadingSampleCount)
    }
}
