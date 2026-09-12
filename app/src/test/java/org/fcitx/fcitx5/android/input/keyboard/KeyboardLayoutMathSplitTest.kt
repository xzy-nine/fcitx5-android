/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [computeSplitRowSpec] 的半键突出（[SplitRowSpec.halfKeyInGroup]）钳制：
 * 突出量不得超过缝隙的一半，否则 `gapInGroup - 2 * halfKeyInGroup` 会变成负百分比
 * （`gapInGroup = 0` 时尤其如此），ConstraintLayout 不接受负的 `matchConstraintPercentWidth`。
 */
class KeyboardLayoutMathSplitTest {

    private fun key(percentWidth: Float = 0.1f) = KeyDef(
        KeyDef.Appearance.Text(
            displayText = "k",
            textSize = 12f,
            percentWidth = percentWidth,
        ),
        behaviors = emptySet(),
    )

    private fun row(count: Int, percentWidth: Float = 0.1f) = List(count) { key(percentWidth) }

    @Test
    fun zeroGapOddRowClampsHalfKeyToZero() {
        // 5 键奇数行（左右共享中间键，protrude），gapRatio = 0：
        // 不钳制时 halfKeyInGroup > 0，缝隙宽度 = -2 * halfKeyInGroup < 0
        val spec = computeSplitRowSpec(row(5), gapRatio = 0f, groupPercent = 0.9f)
        assertEquals(0f, spec.halfKeyInGroup, 1e-6f)
        assertTrue("gap 宽度不得为负", spec.gapInGroup - 2f * spec.halfKeyInGroup >= 0f)
        // 三个组内占比之和仍为 1
        assertEquals(1f, spec.leftInGroup + spec.gapInGroup + spec.rightInGroup, 1e-6f)
    }

    @Test
    fun smallGapOddRowClampsHalfKeyToHalfGap() {
        // 缝隙很小但奇数行仍要突出：halfKeyInGroup 被钳到 gapInGroup / 2，缝隙正好被吃光但不穿帮
        val spec = computeSplitRowSpec(row(5), gapRatio = 0.02f, groupPercent = 0.9f)
        assertEquals(spec.gapInGroup / 2f, spec.halfKeyInGroup, 1e-6f)
        assertEquals(0f, spec.gapInGroup - 2f * spec.halfKeyInGroup, 1e-6f)
    }

    @Test
    fun evenRowHasNoHalfKey() {
        // 偶数行不共享中间键，无突出
        val spec = computeSplitRowSpec(row(6), gapRatio = 0.1f, groupPercent = 0.9f)
        assertEquals(0f, spec.halfKeyInGroup, 1e-6f)
    }
}
