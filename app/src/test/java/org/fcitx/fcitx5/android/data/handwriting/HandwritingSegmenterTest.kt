/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.handwriting

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 叠写切分（DP）行为测试：注入假单字模型，验证「全局最优切分」「一个字先验」
 * 「时间偏置」「段缓存」四件事。
 */
class HandwritingSegmenterTest {

    private fun strokes(count: Int): List<List<StrokePoint>> =
        (0 until count).map { index ->
            listOf(
                StrokePoint(0f, index.toFloat(), 0L),
                StrokePoint(10f, index.toFloat(), 10L),
            )
        }

    private fun segmenter(
        predict: suspend (List<List<StrokePoint>>) -> List<HandwritingCandidate>,
    ) = HandwritingSegmenter { segment, _ -> predict(segment) }

    @Test
    fun `single character without pause stays one segment`() = runBlocking {
        val seg = segmenter { listOf(HandwritingCandidate("张", 0.9f)) }
        val result = seg.recognize(strokes(3), listOf(0L, 60L, 70L))
        assertEquals(1, result.segments.size)
        assertEquals("张", result.segments[0].candidates[0].char)
        assertEquals(3, result.segments[0].strokeCount)
    }

    @Test
    fun `no pause and high merged score forces single character`() = runBlocking {
        // 前两笔单独看像「弓」，但整段看是「张」且分数更高 → 应合并为一个字
        val seg = segmenter { segment ->
            if (segment.size >= 3) listOf(HandwritingCandidate("张", 0.95f))
            else listOf(HandwritingCandidate("弓", 0.92f))
        }
        val result = seg.recognize(strokes(3), listOf(0L, 50L, 40L))
        assertEquals(1, result.segments.size)
        assertEquals("张", result.segments[0].candidates[0].char)
    }

    @Test
    fun `significant pause splits into two segments`() = runBlocking {
        val seg = segmenter { segment ->
            if (segment.size >= 3) listOf(HandwritingCandidate("张", 0.95f))
            else listOf(HandwritingCandidate("弓", 0.92f))
        }
        // 第 3 笔与上一笔之间停顿 800ms → 显著换字边界
        val result = seg.recognize(strokes(3), listOf(0L, 40L, 800L))
        assertEquals(2, result.segments.size)
        assertEquals(2, result.segments[0].strokeCount)
        assertEquals(1, result.segments[1].strokeCount)
    }

    @Test
    fun `cursive multi character without pause keeps dp split when merged score is low`() = runBlocking {
        // 用一个「位置敏感」的假模型：只有 [0,2) 是一个真字（0.95），[0,3)/[0,4) 跨字不成字（0.02），
        // 其余段一律 0.40 的弱分。这样单字先验（合并段 0.02 < 0.35）不成立，
        // 且任何把 [0,2) 与后两笔合并或拆碎的方案都会更低分 —— 最优切分应为 2|2。
        val seg = HandwritingSegmenter { segment, _ ->
            val size = segment.size
            val score = when {
                size == 2 && segment.first().first().y == 0f -> 0.95f
                size >= 3 && segment.first().first().y == 0f -> 0.02f
                else -> 0.40f
            }
            listOf(HandwritingCandidate("字", score))
        }
        val result = seg.recognize(strokes(4), listOf(0L, 30L, 30L, 30L))
        assertEquals(2, result.segments.size)
        assertEquals(2, result.segments[0].strokeCount)
        assertEquals(2, result.segments[1].strokeCount)
        assertEquals("字", result.segments[0].candidates[0].char)
    }

    @Test
    fun `segment cache survives appended strokes`() = runBlocking {
        val seg = HandwritingSegmenter { strokeList, _ ->
            listOf(HandwritingCandidate("字", 0.9f / strokeList.size))
        }
        seg.recognize(strokes(2), listOf(0L, 30L))
        val firstRound = cacheKeys(seg)
        assertTrue("首轮应缓存 2 笔内的所有子段：$firstRound", firstRound.isNotEmpty())

        // 追加两笔：首轮键必须全部保留（键是 (start,end) 编码，与总笔数无关）
        seg.recognize(strokes(4), listOf(0L, 30L, 30L, 30L))
        val secondRound = cacheKeys(seg)
        assertTrue(
            "首轮缓存应跨轮保留：首轮=$firstRound，第二轮=$secondRound",
            secondRound.containsAll(firstRound),
        )
        assertTrue("追加笔画应新增段缓存：$secondRound", secondRound.size > firstRound.size)
    }

    /** 读私有段缓存键（仅测试用）：key = start*1000 + end。 */
    private fun cacheKeys(seg: HandwritingSegmenter): Set<Long> {
        val field = HandwritingSegmenter::class.java.getDeclaredField("segmentCache")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return (field.get(seg) as Map<Long, *>).keys.toSet()
    }

    @Test
    fun `reset clears segment cache`() = runBlocking {
        var calls = 0
        val seg = segmenter { _ ->
            calls += 1
            listOf(HandwritingCandidate("字", 0.5f))
        }
        seg.recognize(strokes(2), listOf(0L, 30L))
        seg.reset()
        calls = 0
        seg.recognize(strokes(2), listOf(0L, 30L))
        assertTrue("reset 后应重新推理", calls > 0)
    }

    @Test
    fun `trimmed window shifts cache keys`() = runBlocking {
        var calls = 0
        val seg = segmenter { _ ->
            calls += 1
            listOf(HandwritingCandidate("字", 0.5f))
        }
        // 先算 (0,3) 这段
        seg.recognize(strokes(3), listOf(0L, 30L, 30L))
        // 头部裁掉 1 笔后，原 (1,3) 应平移为 (0,2) 命中缓存
        seg.onStrokesTrimmed(1)
        calls = 0
        seg.recognize(strokes(2), listOf(0L, 30L))
        assertTrue("裁剪后应命中平移缓存（实际推理 $calls 次）", calls < 3)
    }

    @Test
    fun `empty strokes yield empty result`() = runBlocking {
        val seg = segmenter { listOf(HandwritingCandidate("字", 0.9f)) }
        val result = seg.recognize(emptyList())
        assertEquals(0, result.segments.size)
    }
}
