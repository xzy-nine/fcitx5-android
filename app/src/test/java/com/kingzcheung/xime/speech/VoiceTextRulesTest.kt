/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Kingz Cheung
 *
 * 见仓库根 NOTICE.md。
 */
package com.kingzcheung.xime.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音上屏规则（部分结果 → 最终结果的增量/替换、标点启发式）。
 *
 * 这些规则决定「会不会重复上屏 / 会不会丢字」，是从 Xime 移植时必须守住的语义，
 * 因此抽成纯函数并覆盖。
 */
class VoiceTextRulesTest {

    @Test
    fun `empty partial replaces`() {
        assertEquals(
            VoiceCommitPlan.Replace("你好。"),
            VoiceTextRules.planCommit("你好。", "")
        )
    }

    @Test
    fun `final extends partial appends only the remainder`() {
        assertEquals(
            VoiceCommitPlan.Append("世界。"),
            VoiceTextRules.planCommit("你好世界。", "你好")
        )
    }

    @Test
    fun `final equals partial finishes only`() {
        assertEquals(
            VoiceCommitPlan.FinishOnly,
            VoiceTextRules.planCommit("你好。", "你好。")
        )
    }

    @Test
    fun `final diverging from partial replaces whole text`() {
        assertEquals(
            VoiceCommitPlan.Replace("你好啊。"),
            VoiceTextRules.planCommit("你好啊。", "尼号")
        )
    }

    @Test
    fun `punctuation is appended for a plain sentence`() {
        assertEquals("今天天气不错。", VoiceTextRules.addPunctuation("今天天气不错"))
    }

    @Test
    fun `punctuation asks for question-like text`() {
        assertEquals("你在干什么？", VoiceTextRules.addPunctuation("你在干什么"))
        assertEquals("吃饭了吗？", VoiceTextRules.addPunctuation("吃饭了吗"))
    }

    @Test
    fun `short text gets a comma`() {
        assertEquals("好的，", VoiceTextRules.addPunctuation("好的"))
    }

    @Test
    fun `existing sentence end is not duplicated`() {
        assertEquals("你好。", VoiceTextRules.addPunctuation("你好。"))
        assertEquals("真的吗？", VoiceTextRules.addPunctuation("真的吗？"))
    }

    @Test
    fun `clean partial drops spaces and blank input stays blank`() {
        assertEquals("你好世界", VoiceTextRules.cleanPartial("你 好 世 界"))
        assertTrue(VoiceTextRules.cleanPartial("   ").isEmpty())
    }
}
