/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.handwriting

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 手写笔迹纯几何/时间逻辑测试：笔画包围盒与盒间距、自适应停顿阈值。
 */
class HandwritingStrokeFxTest {

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
    fun `box of empty stroke degenerates to origin`() {
        val box = HandwritingStrokeFx.boxOf(emptyList())
        assertEquals(0f, box.minX, 0.001f)
        assertEquals(0f, box.maxY, 0.001f)
        assertEquals(1f, box.size, 0.001f)
    }

    @Test
    fun `pause threshold bottoms out at the median stroke count`() {
        // 谷底 = 下限
        assertEquals(HW_SPLIT_PAUSE_MIN_MS, HandwritingStrokeFx.splitPauseMs(HW_SPLIT_PAUSE_MEDIAN_STROKES))
        // 偏离中位数越远阈值越高，且不超过上限
        assertEquals(750L, HandwritingStrokeFx.splitPauseMs(0))
        assertEquals(HW_SPLIT_PAUSE_MAX_MS, HandwritingStrokeFx.splitPauseMs(100))
    }
}
