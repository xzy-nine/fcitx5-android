/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTextRulesTest {

    @Test
    fun `already punctuated text is left as is`() {
        assertEquals("你好。", VoiceTextRules.addPunctuation("你好。"))
        assertEquals("really?", VoiceTextRules.addPunctuation("really?"))
        assertEquals("好，", VoiceTextRules.addPunctuation("好，"))
    }

    @Test
    fun `statement gets a full stop`() {
        assertEquals("今天天气不错。", VoiceTextRules.addPunctuation("今天天气不错"))
    }

    @Test
    fun `question wording gets a question mark`() {
        assertEquals("你吃饭了吗？", VoiceTextRules.addPunctuation("你吃饭了吗"))
        assertEquals("这是什么东西？", VoiceTextRules.addPunctuation("这是什么东西"))
    }

    @Test
    fun `blank input is returned untouched`() {
        assertEquals("", VoiceTextRules.addPunctuation(""))
        assertEquals("   ", VoiceTextRules.addPunctuation("   "))
    }

    @Test
    fun `isQuestion only matches question wording`() {
        assertTrue(VoiceTextRules.isQuestion("怎么用"))
        assertFalse(VoiceTextRules.isQuestion("今天天气不错"))
    }
}
