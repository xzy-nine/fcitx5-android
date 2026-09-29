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

    /** 指定 y 的一组笔画（内容不同即代表写了不同的字）。 */
    private fun strokesAt(vararg ys: Float): List<List<StrokePoint>> =
        ys.map { y ->
            listOf(
                StrokePoint(0f, y, 0L),
                StrokePoint(10f, y, 10L),
            )
        }

    /** 假模型：输出只取决于笔画内容，因此可以据「结果是否符合当前笔画」判断是否命中脏缓存。 */
    private fun label(segment: List<List<StrokePoint>>): String =
        segment.flatMap { it }.joinToString("") { it.y.toInt().toString() }

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
        // 先算整窗，再按「头部裁掉 1 笔」滑窗
        val all = strokes(3)
        seg.recognize(all, listOf(0L, 30L, 30L))
        calls = 0
        seg.onStrokesTrimmed(1)
        // 裁剪后窗口里留下的是原第 2、3 笔：原 (1,3) 应平移为 (0,2) 命中缓存
        seg.recognize(all.drop(1), listOf(0L, 30L))
        assertTrue("裁剪后应命中平移缓存（实际推理 $calls 次）", calls < 3)
    }

    // ------------------------------------------------------------------
    // 回归：画布清窗/撤销后段缓存不得跨窗口复用（「写完一个字后写什么都是同一个字」）
    // ------------------------------------------------------------------

    @Test
    fun `new window after canvas clear does not reuse previous inference`() = runBlocking {
        // 假模型输出仅取决于笔画内容，因此「结果是否对应当前笔画」即可判定缓存是否串字
        val seg = segmenter { segment -> listOf(HandwritingCandidate(label(segment), 0.9f)) }

        // 第一个字：两笔（y=0,1）
        val first = seg.recognize(strokesAt(0f, 1f), listOf(0L, 30L))
        assertEquals("0011", first.segments[0].candidates[0].char)

        // 画布停顿清窗后写第二个字：两笔，下标与上一个字完全相同，但内容不同（y=5,6）
        val second = seg.recognize(strokesAt(5f, 6f), listOf(0L, 30L))
        assertEquals("5566", second.segments[0].candidates[0].char)
    }

    @Test
    fun `new single stroke window after canvas clear is re-inferred`() = runBlocking {
        val seg = segmenter { segment -> listOf(HandwritingCandidate(label(segment), 0.9f)) }

        val first = seg.recognize(strokesAt(0f), listOf(0L))
        assertEquals("00", first.segments[0].candidates[0].char)

        // 单笔窗口：key (0,1) 与上一个字相同，若只按下标命中就会一直返回上一个字
        val second = seg.recognize(strokesAt(7f), listOf(0L))
        assertEquals("77", second.segments[0].candidates[0].char)
    }

    @Test
    fun `undo then redraw a different stroke is re-inferred`() = runBlocking {
        val seg = segmenter { segment -> listOf(HandwritingCandidate(label(segment), 0.9f)) }

        seg.recognize(strokesAt(0f, 1f), listOf(0L, 30L))
        // 撤销第二笔后重画成另一笔：下标仍是 (1,2)，内容由 y=1 变成 y=4
        val redrawn = seg.recognize(strokesAt(0f, 4f), listOf(0L, 30L))
        assertEquals("0044", redrawn.segments[0].candidates[0].char)
    }

    @Test
    fun `appending strokes still reuses cached segments`() = runBlocking {
        val computed = mutableListOf<String>()
        val seg = segmenter { segment ->
            computed += label(segment)
            listOf(HandwritingCandidate("字", 0.5f))
        }
        val all = strokesAt(0f, 1f)
        seg.recognize(all, listOf(0L, 30L))
        val firstRound = cacheKeys(seg)
        assertEquals(listOf("00", "0011", "11"), computed)

        // 尾部追加一笔（y=4，仍在同一空间邻域内）：已有段应命中内容校验缓存
        computed.clear()
        seg.recognize(all + strokesAt(4f), listOf(0L, 30L, 30L))
        assertEquals(
            "已有段应命中内容校验缓存，只推理含新笔的段",
            listOf("001144", "1144", "44"),
            computed,
        )
        assertTrue("首轮缓存键应保留：$firstRound", cacheKeys(seg).containsAll(firstRound))
    }

    @Test
    fun `spatially separated strokes are recognized as separate characters`() = runBlocking {
        // 两截在画布上明显分开（间距 90px、笔尺寸 10px）：必须切成两段、分别送识别，
        // 即使假模型给整段极高分也不合并
        val calls = mutableListOf<Int>()
        val seg = HandwritingSegmenter { segment, _ ->
            calls += segment.size
            listOf(HandwritingCandidate("字", 0.99f))
        }
        val left = listOf(StrokePoint(0f, 0f, 0L), StrokePoint(10f, 10f, 10L))
        val right = listOf(StrokePoint(100f, 0f, 20L), StrokePoint(110f, 10f, 30L))
        val result = seg.recognize(listOf(left, right), listOf(0L, 20L))
        assertEquals(2, result.segments.size)
        assertEquals(listOf(1, 1), calls)
    }

    @Test
    fun `empty strokes yield empty result`() = runBlocking {
        val seg = segmenter { listOf(HandwritingCandidate("字", 0.9f)) }
        val result = seg.recognize(emptyList())
        assertEquals(0, result.segments.size)
    }
}
