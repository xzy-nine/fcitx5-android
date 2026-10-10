/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.voice.preprocess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * 非流式路径的音频缓冲：分块拼接顺序、整段预处理、以及取走之后的写入隔离。
 */
class VoiceUtteranceBufferTest {

    /** 全程语音的配置：不做切除，便于逐点比对拼接结果。 */
    private val flat = VoicePreprocessConfig(normalizeLoudness = false)

    @Test
    fun `chunks are concatenated in the order they arrive`() {
        val buffer = VoiceUtteranceBuffer(flat)
        val first = tone(100, amp = 0.3f)
        val second = tone(100, amp = 0.5f)
        buffer.append(first)
        buffer.append(second)

        val result = buffer.drain()!!
        assertEquals(first.size + second.size, result.processed.size)
        // 全段都是语音，故原样保留：前半段来自第一块，后半段来自第二块
        assertTrue(result.processed.copyOfRange(0, first.size).contentEquals(first))
        assertTrue(result.processed.copyOfRange(first.size, first.size + second.size).contentEquals(second))
    }

    @Test
    fun `whole buffer is preprocessed as one utterance`() {
        val buffer = VoiceUtteranceBuffer(VoicePreprocessConfig(normalizeLoudness = false))
        buffer.append(silence(400))
        buffer.append(tone(600))
        buffer.append(silence(400))

        val result = buffer.drain()!!
        assertTrue(result.applied)
        // 首尾静音被切掉：输出明显短于原始的 1400ms
        assertTrue("processed=${result.stats.processedMs}", result.stats.processedMs < 1000)
        assertEquals(1400, result.stats.originalMs)
        assertNull(result.stats.fallback)
    }

    @Test
    fun `empty buffer yields no result`() {
        val buffer = VoiceUtteranceBuffer(flat)
        assertTrue(buffer.isEmpty)
        assertNull(buffer.drain())
    }

    @Test
    fun `writes after drain are ignored`() {
        val buffer = VoiceUtteranceBuffer(flat)
        buffer.append(tone(100))
        assertFalse(buffer.isEmpty)

        val first = buffer.drain()!!
        // 取走后再追加的音频不得混进结果，也不得让缓冲重新变成非空
        buffer.append(tone(100, amp = 0.9f))
        assertTrue(buffer.isEmpty)
        assertNull(buffer.drain())
        assertEquals(1600, first.processed.size)
    }

    @Test
    fun `empty chunks do not create audio`() {
        val buffer = VoiceUtteranceBuffer(flat)
        buffer.append(FloatArray(0))
        assertTrue(buffer.isEmpty)
        assertNull(buffer.drain())
    }

    @Test
    fun `voice input preset matches the agreed parameters`() {
        val config = VoicePreprocessConfig.VOICE_INPUT

        assertEquals(9f, config.snrMarginDb, 0f)
        assertEquals(160, config.minSpeechMs)
        assertEquals(180, config.mergeGapMs)
        assertEquals(60, config.keptSilenceMs)
        assertEquals(60, config.leadPadMs)
        assertEquals(100, config.tailPadMs)
        assertEquals(1.25f, config.fastForwardRate, 0f)
        assertTrue(config.normalizeLoudness)
        assertEquals(-20f, config.targetRmsDb, 0f)
        assertEquals(-1f, config.peakCeilingDb, 0f)
        // 压缩后保留必须短于合并阈值，否则停顿压缩失去意义
        assertTrue(config.effectiveKeptSilenceMs < config.mergeGapMs)
        assertEquals(60, config.effectiveKeptSilenceMs)
        assertEquals(1.25f, config.effectiveFastForwardRate, 0f)
    }
}

private const val TEST_SAMPLE_RATE = 16000

private fun testSamples(ms: Int): Int = TEST_SAMPLE_RATE * ms / 1000

private fun silence(ms: Int): FloatArray = FloatArray(testSamples(ms))

private fun tone(ms: Int, amp: Float = 0.3f, freq: Float = 200f): FloatArray {
    val count = testSamples(ms)
    return FloatArray(count) { i -> (amp * sin(2.0 * PI * freq * i / TEST_SAMPLE_RATE)).toFloat() }
}
