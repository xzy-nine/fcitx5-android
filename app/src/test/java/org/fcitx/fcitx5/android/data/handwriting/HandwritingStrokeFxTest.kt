/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.handwriting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手写叠写窗口纯逻辑测试：自适应停顿阈值、笔间间隔、已完成前缀、滑窗固化触发条件。
 */
class HandwritingStrokeFxTest {

    private fun stroke(startMs: Long, endMs: Long): List<StrokePoint> =
        listOf(StrokePoint(0f, 0f, startMs), StrokePoint(10f, 10f, endMs))

    private fun segment(start: Int, count: Int) =
        HandwritingSegmenter.Segment(start, count, listOf(HandwritingCandidate("字", 0.9f)))

    @Test
    fun `split pause is a V curve around the median stroke count`() {
        // 中位数处最快，0 笔（y(0)）与 2×中位数处同为 750ms
        assertEquals(
            HW_SPLIT_PAUSE_MIN_MS,
            HandwritingStrokeFx.splitPauseMs(HW_SPLIT_PAUSE_MEDIAN_STROKES),
        )
        assertEquals(750L, HandwritingStrokeFx.splitPauseMs(0))
        assertEquals(750L, HandwritingStrokeFx.splitPauseMs(2 * HW_SPLIT_PAUSE_MEDIAN_STROKES))
        // 关于中位数对称
        assertEquals(
            HandwritingStrokeFx.splitPauseMs(4),
            HandwritingStrokeFx.splitPauseMs(HW_SPLIT_PAUSE_MEDIAN_STROKES * 2 - 4),
        )
        // 偏离足够远时封顶
        assertEquals(HW_SPLIT_PAUSE_MAX_MS, HandwritingStrokeFx.splitPauseMs(50))
    }

    @Test
    fun `window gaps start at zero and measure stroke to stroke silence`() {
        val window = listOf(
            stroke(startMs = 0, endMs = 100),
            stroke(startMs = 250, endMs = 300),
            stroke(startMs = 1300, endMs = 1400),
        )
        assertEquals(listOf(0L, 150L, 1000L), HandwritingStrokeFx.windowGaps(window))
    }

    @Test
    fun `no character gap means nothing settled`() {
        // 单字中途被切成 3 段，但段间间隔都 < 换字门槛 → 不变淡、不固化
        val segments = listOf(segment(0, 1), segment(1, 1), segment(2, 1))
        assertEquals(0, HandwritingStrokeFx.settledStrokesBeforeCurrent(segments, listOf(0L, 120L, 90L)))
    }

    @Test
    fun `last character gap marks everything before it as settled`() {
        val segments = listOf(segment(0, 2), segment(2, 1), segment(3, 1))
        // 第 2 段起笔间隔 60ms（同字），第 3 段起笔间隔 700ms（换字）
        val gaps = listOf(0L, 50L, 60L, 700L)
        assertEquals(3, HandwritingStrokeFx.settledStrokesBeforeCurrent(segments, gaps))
    }

    @Test
    fun `gap exactly at threshold counts as character boundary`() {
        val segments = listOf(segment(0, 1), segment(1, 1))
        assertEquals(1, HandwritingStrokeFx.settledStrokesBeforeCurrent(segments, listOf(0L, HW_FADE_GAP_MS)))
        assertEquals(0, HandwritingStrokeFx.settledStrokesBeforeCurrent(segments, listOf(0L, HW_FADE_GAP_MS - 1)))
    }

    @Test
    fun `first segment boundary is not a settled prefix`() {
        // 只有一段时没有「前面已完成」的说法
        assertEquals(0, HandwritingStrokeFx.settledStrokesBeforeCurrent(listOf(segment(0, 3)), listOf(0L)))
    }

    @Test
    fun `window limit triggers settle only when exceeded`() {
        assertFalse(HandwritingStrokeFx.isWindowOverLimit(HW_RECOGNIZE_WINDOW_LIMIT))
        assertTrue(HandwritingStrokeFx.isWindowOverLimit(HW_RECOGNIZE_WINDOW_LIMIT + 1))
    }

    @Test
    fun `saturation settle requires a real character gap`() {
        val max = HandwritingSegmenter.DEFAULT_MAX_SEGMENTS
        // 段数饱和但无换字停顿（单字被切碎）→ 不固化
        assertFalse(HandwritingStrokeFx.needsSettleOnSaturation(max, 0))
        // 段数未饱和 → 不固化
        assertFalse(HandwritingStrokeFx.needsSettleOnSaturation(max - 1, 5))
        // 段数饱和且有换字停顿 → 固化已完成前缀
        assertTrue(HandwritingStrokeFx.needsSettleOnSaturation(max, 5))
    }
}
