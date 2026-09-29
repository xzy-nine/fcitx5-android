/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.handwriting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 特征工程契约测试：模型输入是固定长度 (200,5)，归一化方式必须与训练侧一致，
 * 否则模型输出会静默变差（不报错）。
 */
class HandwritingPreprocessTest {

    private fun pt(x: Float, y: Float, t: Long = 0L) = StrokePoint(x, y, t)

    @Test
    fun `sequence normalizes by bounding box and marks pen up`() {
        val strokes = listOf(
            listOf(pt(0f, 0f), pt(10f, 0f)),
            listOf(pt(0f, 10f), pt(10f, 10f)),
        )
        val seq = HandwritingPreprocess.strokesToSequence(strokes)

        assertEquals(4, seq.length)
        assertEquals(HandwritingPreprocess.FIXED_LEN * 5, seq.data.size)

        // 点 0：(0,0)，首点增量恒为 0，笔内 pen_down=1
        assertEquals(0f, seq.data[0], 1e-6f)
        assertEquals(0f, seq.data[1], 1e-6f)
        assertEquals(0f, seq.data[2], 1e-6f)
        assertEquals(0f, seq.data[3], 1e-6f)
        assertEquals(1f, seq.data[4], 1e-6f)

        // 点 1：(1,0)，dx=1，且是第一笔末点 → pen_down=0
        assertEquals(1f, seq.data[5], 1e-6f)
        assertEquals(0f, seq.data[6], 1e-6f)
        assertEquals(1f, seq.data[7], 1e-6f)
        assertEquals(0f, seq.data[8], 1e-6f)
        assertEquals(0f, seq.data[9], 1e-6f)

        // 点 2：第二笔起点 (0,1)，笔间增量归零
        assertEquals(0f, seq.data[10], 1e-6f)
        assertEquals(1f, seq.data[11], 1e-6f)
        assertEquals(0f, seq.data[12], 1e-6f)
        assertEquals(0f, seq.data[13], 1e-6f)
        assertEquals(1f, seq.data[14], 1e-6f)

        // 点 3：第二笔末点 (1,1)，pen_down=0
        assertEquals(1f, seq.data[15], 1e-6f)
        assertEquals(1f, seq.data[16], 1e-6f)
        assertEquals(0f, seq.data[19], 1e-6f)
    }

    @Test
    fun `single point does not divide by zero`() {
        val seq = HandwritingPreprocess.strokesToSequence(listOf(listOf(pt(5f, 5f))))
        assertEquals(1, seq.length)
        assertEquals(0f, seq.data[0], 1e-6f)
        assertEquals(0f, seq.data[1], 1e-6f)
        assertEquals(0f, seq.data[2], 1e-6f)
        assertEquals(1f, seq.data[4], 1e-6f)
    }

    @Test
    fun `empty strokes yield empty sequence`() {
        val seq = HandwritingPreprocess.strokesToSequence(emptyList())
        assertEquals(0, seq.length)
        assertTrue(seq.data.all { it == 0f })
    }

    @Test
    fun `oversized sequence is truncated to fixed length`() {
        val long = (0 until 500).map { pt(it.toFloat(), it.toFloat(), it.toLong()) }
        val seq = HandwritingPreprocess.strokesToSequence(listOf(long))
        assertEquals(HandwritingPreprocess.FIXED_LEN, seq.length)
        assertEquals(HandwritingPreprocess.FIXED_LEN * 5, seq.data.size)
    }

    @Test
    fun `long stroke is resampled to max points per stroke`() {
        val long = (0 until 100).map { pt(it.toFloat(), 0f) }
        val simplified = HandwritingPreprocess.simplifyStrokes(listOf(long))
        assertEquals(1, simplified.size)
        assertEquals(HandwritingPreprocess.MAX_POINTS_PER_STROKE, simplified[0].size)
        // 首末点必须保留（等距重采样）
        assertEquals(0f, simplified[0].first().x, 1e-6f)
        assertEquals(99f, simplified[0].last().x, 1e-6f)
    }

    @Test
    fun `short stroke is kept as is`() {
        val short = (0 until 3).map { pt(it.toFloat(), 0f) }
        val simplified = HandwritingPreprocess.simplifyStrokes(listOf(short))
        assertEquals(short, simplified[0])
    }

    @Test
    fun `mask marks valid prefix only`() {
        val mask = HandwritingPreprocess.buildMask(3)
        assertEquals(HandwritingPreprocess.FIXED_LEN, mask.size)
        assertTrue(mask[0] && mask[1] && mask[2])
        assertTrue(!mask[3])
        assertTrue(!mask[HandwritingPreprocess.FIXED_LEN - 1])
    }
}
