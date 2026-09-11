/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.expanded

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [computeGridSpanCount] 的边界语义：长候选在前要收敛列数，短词多则被上限钳制，
 * 非法输入（NaN / 负值 / 空列表）必须返回可用的列数而不是 0 或崩溃。
 */
class ExpandedCandidateGridSpanTest {

    private val padding = 40f

    @Test
    fun wideLeadingCandidateYieldsFewerColumns() {
        // 1080 / (300 + 40) = 3.17 -> 3 列，保证最宽那条长候选整条放得下
        assertEquals(3, computeGridSpanCount(1080f, listOf(120f, 300f, 90f), padding))
    }

    @Test
    fun shortCandidatesYieldMoreColumns() {
        // 1080 / (80 + 40) = 9 列
        assertEquals(9, computeGridSpanCount(1080f, listOf(60f, 80f, 70f), padding))
    }

    @Test
    fun columnsAreClampedToMaxSpan() {
        // 理论 27 列，被 maxSpan 钳到 12
        assertEquals(12, computeGridSpanCount(1080f, listOf(10f), padding, maxSpan = 12))
        assertEquals(6, computeGridSpanCount(1080f, listOf(10f), padding, maxSpan = 6))
    }

    @Test
    fun tooNarrowAvailableWidthFallsBackToMinSpan() {
        // 最宽候选比可用宽度还宽：0 列 -> 取下限，而不是 0 或 1
        assertEquals(2, computeGridSpanCount(100f, listOf(300f), padding))
        assertEquals(3, computeGridSpanCount(100f, listOf(300f), padding, minSpan = 3))
    }

    @Test
    fun emptyLeadingWidthsFallBackToMaxSpan() {
        // 首屏文本还没测量出来：先按上限铺满，等文本到达后收敛（此时还没有 item，不会看到跳变）
        assertEquals(12, computeGridSpanCount(1080f, emptyList(), padding))
        assertEquals(5, computeGridSpanCount(1080f, emptyList(), padding, maxSpan = 5))
    }

    @Test
    fun invalidWidthsAreIgnored() {
        val widths = listOf(-10f, Float.NaN, Float.POSITIVE_INFINITY, 0f, 80f)
        assertEquals(9, computeGridSpanCount(1080f, widths, padding))
    }

    @Test
    fun invalidAvailableWidthFallsBackToMinSpan() {
        assertEquals(2, computeGridSpanCount(0f, listOf(80f), padding))
        assertEquals(2, computeGridSpanCount(-5f, listOf(80f), padding))
        assertEquals(2, computeGridSpanCount(Float.NaN, listOf(80f), padding))
        assertEquals(4, computeGridSpanCount(0f, listOf(80f), padding, minSpan = 4))
    }

    @Test
    fun negativePaddingIsTreatedAsZero() {
        // padding 异常时按 0 处理：1080 / 80 = 13 -> 钳到 12
        assertEquals(12, computeGridSpanCount(1080f, listOf(80f), -100f))
    }

    @Test
    fun maxSpanBelowMinSpanDegradesToMinSpan() {
        assertEquals(3, computeGridSpanCount(1080f, listOf(300f), padding, minSpan = 3, maxSpan = 1))
    }

    @Test
    fun sampleCountConstantStaysSmall() {
        // 采样条数只用于「首屏长候选」判断，调大没有收益且会让列数随滚动抖动
        assertEquals(10, ExpandedCandidateLeadingSampleCount)
    }
}
