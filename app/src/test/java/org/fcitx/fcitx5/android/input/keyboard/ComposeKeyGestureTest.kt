/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SwipeAccumulator] 的取模累加语义必须与 View 侧 `CustomGestureView.consumeSwipe` 逐字一致，
 * 否则同一手势在 View / Compose 两套实现下会滑出不同的格数。
 */
class ComposeKeyGestureTest {

    @Test
    fun accumulatesRemainderAcrossMoves() {
        val acc = SwipeAccumulator(thresholdX = 10f, thresholdY = 10f)
        acc.start(0f, 0f)
        // 12 -> 1 格，余 2
        assertEquals(1, acc.consumeX(12f))
        assertEquals(1, acc.totalX)
        // 18 - 12 + 2 = 8 -> 不足一格
        assertEquals(0, acc.consumeX(18f))
        assertEquals(1, acc.totalX)
        // 24 - 18 + 8 = 14 -> 1 格，余 4
        assertEquals(1, acc.consumeX(24f))
        assertEquals(2, acc.totalX)
    }

    @Test
    fun supportsNegativeDirection() {
        val acc = SwipeAccumulator(thresholdX = 10f, thresholdY = 10f)
        acc.start(0f, 0f)
        // -25 -> -2 格（整数除法向零取整），余 -5
        assertEquals(-2, acc.consumeX(-25f))
        assertEquals(-2, acc.totalX)
        // -30 - (-25) + (-5) = -10 -> -1 格，余 0
        assertEquals(-1, acc.consumeX(-30f))
        assertEquals(-3, acc.totalX)
    }

    @Test
    fun disabledThresholdNeverTriggers() {
        val acc = SwipeAccumulator(thresholdX = 800f, thresholdY = 800f)
        acc.start(0f, 0f)
        assertEquals(0, acc.consumeX(400f))
        assertEquals(0, acc.consumeX(799f))
        assertEquals(0, acc.totalX)
    }

    @Test
    fun axesAreIndependent() {
        val acc = SwipeAccumulator(thresholdX = 10f, thresholdY = 36f)
        acc.start(0f, 0f)
        assertEquals(1, acc.consumeX(10f))
        assertEquals(0, acc.consumeY(10f))
        assertEquals(1, acc.totalX)
        assertEquals(0, acc.totalY)
        assertEquals(1, acc.consumeY(36f))
        assertEquals(1, acc.totalY)
    }

    @Test
    fun startResetsTotals() {
        val acc = SwipeAccumulator(thresholdX = 10f, thresholdY = 10f)
        acc.start(0f, 0f)
        acc.consumeX(30f)
        assertEquals(3, acc.totalX)
        acc.start(0f, 0f)
        assertEquals(0, acc.totalX)
        assertEquals(0, acc.totalY)
    }

    /** 等价于 `CustomGestureView.pointInView`，含 `touchSlop` 边界。 */
    @Test
    fun pointInKeyBoundsIncludesTouchSlop() {
        val w = 100f
        val h = 50f
        val slop = 8f
        assertTrue(isPointInKeyBounds(0f, 0f, w, h, slop))
        // 上/左边界：-slop 以内仍算命中（闭区间）
        assertTrue(isPointInKeyBounds(-slop, -slop, w, h, slop))
        assertFalse(isPointInKeyBounds(-slop - 0.1f, 0f, w, h, slop))
        // 下/右边界：宽度本身仍在 slop 内，w + slop 才算移出（View: `x < width + touchSlop`）
        assertTrue(isPointInKeyBounds(w, h, w, h, slop))
        assertFalse(isPointInKeyBounds(w + slop, 0f, w, h, slop))
        assertFalse(isPointInKeyBounds(0f, h + slop, w, h, slop))
    }
}
