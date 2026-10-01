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

    private fun boxStroke(x0: Float, y0: Float, x1: Float, y1: Float): List<StrokePoint> =
        listOf(StrokePoint(x0, y0, 0L), StrokePoint(x1, y1, 10L))

    @Test
    fun `box gap is zero for overlapping boxes and measures separation`() {
        val a = HandwritingStrokeFx.boxOf(boxStroke(0f, 0f, 10f, 10f))
        val overlapping = HandwritingStrokeFx.boxOf(boxStroke(5f, 5f, 15f, 15f))
        val apart = HandwritingStrokeFx.boxOf(boxStroke(13f, 0f, 20f, 10f))
        assertEquals(0f, HandwritingStrokeFx.boxGap(a, overlapping), 0.001f)
        assertEquals(3f, HandwritingStrokeFx.boxGap(a, apart), 0.001f)
    }

    @Test
    fun `spatial boundary marks only clearly separated strokes`() {
        val near = HandwritingStrokeFx.spatialBoundaries(
            listOf(boxStroke(0f, 0f, 10f, 10f), boxStroke(11f, 0f, 21f, 10f)),
        )
        assertFalse("同字内相邻笔画不算换字", near[1])

        val far = HandwritingStrokeFx.spatialBoundaries(
            listOf(boxStroke(0f, 0f, 10f, 10f), boxStroke(100f, 0f, 110f, 10f)),
        )
        assertTrue("明显分开的两截算换字", far[1])
        assertFalse("第一笔之前没有边界", far[0])
    }

    @Test
    fun `character components are not treated as separate characters`() {
        // 「测」的部件：氵(左)、贝(中)、刂(右) —— 部件之间确实分离，但间距远小于笔画自身尺寸，
        // 不得判成空间硬边界（否则硬边界禁止跨边界合并 → 一个字被拆成「、冫贝刂」）
        val radical = boxStroke(0f, 0f, 25f, 60f)     // 氵：宽 25 高 60 → size 60
        val shell = boxStroke(45f, 0f, 90f, 60f)      // 贝：宽 45 高 60 → size 60
        val knife = boxStroke(105f, 0f, 125f, 60f)    // 刂：宽 20 高 60 → size 60
        val boundaries = HandwritingStrokeFx.spatialBoundaries(listOf(radical, shell, knife))
        assertFalse("氵→贝 是字内部件间距，不算换字", boundaries[1])
        assertFalse("贝→刂 是字内部件间距，不算换字", boundaries[2])
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
