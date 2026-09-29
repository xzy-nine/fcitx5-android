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
        // 连笔写两个字：整段识别分数很低，不应被「一个字先验」吞掉
        val seg = segmenter { segment ->
            when (segment.size) {
                2 -> listOf(HandwritingCandidate("你", 0.9f))
                1 -> listOf(HandwritingCandidate("好", 0.9f))
                else -> listOf(HandwritingCandidate("?", 0.02f))
            }
        }
        val result = seg.recognize(strokes(4), listOf(0L, 30L, 30L, 30L))
        assertEquals(2, result.segments.size)
        assertEquals(listOf("你", "好"), result.segments.map { it.candidates[0].char })
    }

    @Test
    fun `segment results are cached across appended strokes`() = runBlocking {
        val requested = mutableListOf<Pair<Int, Int>>()
        val seg = HandwritingSegmenter { strokeList, _ ->
            requested += 0 to strokeList.size
            listOf(HandwritingCandidate("字", 0.9f / strokeList.size))
        }
        val two = strokes(2)
        seg.recognize(two, listOf(0L, 30L))
        // 首轮已覆盖「前两笔」的所有子段
        assertTrue("首轮应已推理 (0,2)", requested.contains(0 to 2))

        // 追加两笔：已覆盖的子段（0..2 范围内）**不得重新推理**，只允许新增涉及第 3/4 笔的段
        requested.clear()
        val four = strokes(4)
        seg.recognize(four, listOf(0L, 30L, 30L, 30L))
        val recomputed = requested.filter { it.second <= 2 }
        assertTrue(
            "已缓存子段被重复推理：$recomputed（本轮全部：$requested）",
            recomputed.isEmpty(),
        )
        assertTrue("追加笔画后应确实新增了推理：$requested", requested.isNotEmpty())
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
