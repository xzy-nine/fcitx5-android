/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.handwriting

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 一笔手势判定测试：涂改/圈选/回车钩/插入尖角各自命中，且常见字符笔画（横、竖、撇捺、点）
 * 不得被误判（宁缺勿滥）。
 */
class HandwritingGesturesTest {

    private val screenWidth = 1600

    private fun stroke(vararg points: Pair<Float, Float>): List<StrokePoint> =
        points.mapIndexed { index, (x, y) -> StrokePoint(x, y, index * 16L) }

    /** 折线采样（等分插值），用于构造平滑轨迹。 */
    private fun polyline(vararg points: Pair<Float, Float>, segments: Int = 24): List<StrokePoint> {
        val out = ArrayList<StrokePoint>()
        var time = 0L
        for (i in 1 until points.size) {
            val (x0, y0) = points[i - 1]
            val (x1, y1) = points[i]
            for (s in 0..segments) {
                val t = s.toFloat() / segments
                out.add(StrokePoint(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, time))
                time += 16L
            }
        }
        return out
    }

    // ------------------------------------------------------------------
    // 手势命中
    // ------------------------------------------------------------------

    @Test
    fun `scribble is detected as delete`() {
        // 宽扁来回涂抹：500px 宽、60px 高、6 次水平往返
        val points = ArrayList<Pair<Float, Float>>()
        for (i in 0..6) {
            val y = 300f + (i % 2) * 60f
            points += if (i % 2 == 0) 400f to y else 900f to y
        }
        val result = HandwritingGestures.detect(polyline(*points.toTypedArray()), screenWidth)
        assertEquals(HandwritingStrokeKind.Delete, result)
    }

    @Test
    fun `circle is detected as select`() {
        // 半径 200 的闭合圆
        val r = 200f
        val points = (0..48).map {
            val a = 2 * PI * it / 48
            StrokePoint((800 + r * cos(a)).toFloat(), (800 + r * sin(a)).toFloat(), it * 16L)
        }
        val result = HandwritingGestures.detect(points, screenWidth)
        assertEquals(HandwritingStrokeKind.Select, result)
    }

    @Test
    fun `tall narrow v is detected as insert`() {
        // 高瘦 ⋁：宽 100、高 300（≥2×宽），垂直方向一次转向
        val result = HandwritingGestures.detect(
            polyline(700f to 600f, 750f to 900f, 800f to 600f),
            screenWidth,
        )
        assertEquals(HandwritingStrokeKind.Insert, result)
    }

    @Test
    fun `downward hook ending left is detected as newline`() {
        // ⏎ 形：从右上下行，再向左横收（收笔明显在起笔左侧）
        val result = HandwritingGestures.detect(
            polyline(900f to 500f, 1000f to 800f, 600f to 820f),
            screenWidth,
        )
        assertEquals(HandwritingStrokeKind.Newline, result)
    }

    // ------------------------------------------------------------------
    // 字符笔画不得误判（宁缺勿滥）
    // ------------------------------------------------------------------

    @Test
    fun `vertical hook stays character`() {
        // 竖钩「亅」：长竖下行后向左上短收 —— 垂直方向也只有一次反转、也高瘦，
        // 但第二臂极短且收笔停在字腰，不得判成插入尖角（否则整笔被吞掉、字写不出来）
        val result = HandwritingGestures.detect(
            polyline(800f to 400f, 810f to 900f, 700f to 780f),
            screenWidth,
        )
        assertEquals(HandwritingStrokeKind.Character, result)
    }

    @Test
    fun `right falling stroke stays character`() {
        // 「捺」：斜向右下，无反转
        val result = HandwritingGestures.detect(polyline(600f to 400f, 900f to 900f), screenWidth)
        assertEquals(HandwritingStrokeKind.Character, result)
    }

    @Test
    fun `horizontal character stroke stays character`() {
        // 「一」：一笔横向 200px 长（< 屏宽 8% 的横向手势门槛之上但形状是直线，无往返、不闭合）
        val result = HandwritingGestures.detect(polyline(600f to 800f, 800f to 805f), screenWidth)
        assertEquals(HandwritingStrokeKind.Character, result)
    }

    @Test
    fun `long horizontal swipe is not a gesture either`() {
        // 横划不判手势（空格由工具箱/键盘提供；避免与「一」冲突）
        val result = HandwritingGestures.detect(polyline(300f to 800f, 1300f to 806f), screenWidth)
        assertEquals(HandwritingStrokeKind.Character, result)
    }

    @Test
    fun `vertical stroke stays character`() {
        val result = HandwritingGestures.detect(polyline(700f to 400f, 710f to 900f), screenWidth)
        assertEquals(HandwritingStrokeKind.Character, result)
    }

    @Test
    fun `left falling stroke stays character`() {
        // 撇：右上到左下（收笔在起笔左侧，但下行幅度不足且宽度大）
        val result = HandwritingGestures.detect(polyline(900f to 400f, 400f to 900f), screenWidth)
        assertEquals(HandwritingStrokeKind.Character, result)
    }

    @Test
    fun `short stroke stays character`() {
        // 点：很小的一笔
        val result = HandwritingGestures.detect(stroke(700f to 700f, 706f to 706f, 710f to 712f), screenWidth)
        assertEquals(HandwritingStrokeKind.Character, result)
    }

    @Test
    fun `small closed loop stays character`() {
        // 很小的闭合环（口字部件那种大小的圈）不足以触发圈选手势
        val r = 40f
        val points = (0..24).map {
            val a = 2 * PI * it / 24
            StrokePoint((800 + r * cos(a)).toFloat(), (800 + r * sin(a)).toFloat(), it * 16L)
        }
        assertEquals(HandwritingStrokeKind.Character, HandwritingGestures.detect(points, screenWidth))
    }
}
