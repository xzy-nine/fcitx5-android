/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.candidates.horizontal

import org.fcitx.fcitx5.android.core.CandidateActionContext
import org.fcitx.fcitx5.android.core.CandidateSelectionTarget
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.sameSelectionTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 候选栏的点击要凭 [CandidateWord.selectionTarget] 通过引擎的时效判定，而候选文本经常不变：
 * 引擎在候选内容看起来完全相同时也会失效一次候选（键盘释放、分页模式切换、方案选项变化）。
 *
 * 只按文本判定「有没有变化」会让候选栏停在过期上下文上，点击不再上屏，直到下一次文本变化
 * 才恢复。这里锁定承载该判定的原语：列表相等性包含上下文、重推只覆盖头部。
 */
class CandidateListSelectionTest {

    private fun word(text: String, version: Long, index: Int = 0) =
        CandidateWord("1 ", text, "").apply {
            selectionTarget = CandidateSelectionTarget(CandidateActionContext(7, version), index, false)
        }

    private fun list(vararg words: CandidateWord) = CandidateList(arrayOf(*words))

    @Test
    fun identicalTextWithANewerSelectionVersionIsADifferentList() {
        val old = list(word("你", 1))
        val fresh = list(word("你", 2))
        // 展示内容完全相同，但候选栏据上下文决定点击是否有效，故不等。
        assertTrue(old.toList().map { it.text } == fresh.toList().map { it.text })
        assertNotEquals(old, fresh)
    }

    @Test
    fun identicalTextAndVersionStayEqual() {
        assertEquals(list(word("你", 1)), list(word("你", 1)))
    }

    @Test
    fun pushedHeadUpdatesTheContextWithoutDroppingLoadedTail() {
        val loaded = list(word("你", 1, 0), word("好", 1, 1), word("的", 1, 2))
        val pushed = arrayOf(word("你", 2, 0))

        val merged = loaded.replaceHead(pushed)

        assertEquals(3, merged.size)
        assertEquals(CandidateActionContext(7, 2), merged[0].selectionTarget?.context)
        assertEquals("好", merged[1].text)
        assertEquals(CandidateActionContext(7, 1), merged[1].selectionTarget?.context)
        assertEquals("的", merged[2].text)
    }

    @Test
    fun pushedHeadReplacesTheListWhenItIsAtLeastAsLong() {
        val loaded = list(word("你", 1, 0))
        val pushed = arrayOf(word("你", 2, 0), word("好", 2, 1))

        val merged = loaded.replaceHead(pushed)

        assertEquals(2, merged.size)
        assertEquals(CandidateActionContext(7, 2), merged[1].selectionTarget?.context)
    }

    @Test
    fun aPushedPrefixMatchesOnTextButNotOnContext() {
        val loaded = list(word("你", 1, 0), word("好", 1, 1))
        // 前缀判定只看展示内容：它决定是否需要重置滚动，与上下文无关。
        assertTrue(loaded.startsWith(arrayOf(word("你", 2, 0))))
        // 上下文比较用于判定这一次推送是否真的带来了新目标，要求整段长度一致。
        assertFalse(list(word("你", 1)).toList().toTypedArray().sameSelectionTargets(arrayOf(word("你", 2))))
        assertTrue(list(word("你", 1)).toList().toTypedArray().sameSelectionTargets(arrayOf(word("你", 1))))
    }
}
