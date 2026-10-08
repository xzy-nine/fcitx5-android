/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.voice.preprocess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * 整段预处理的端到端行为（合成音频）：静音切除的三种模式、留白、下限与兜底路径。
 *
 * 断言一律用区间而不是精确下标——判决是按 20ms 帧 + 10ms 帧移做的，边界天然有半帧级抖动。
 */
class VoiceSilenceTrimmerTest {

    private val config = VoicePreprocessConfig()

    @Test
    fun `leading and trailing silence are trimmed`() {
        val raw = concat(silence(500), tone(600), silence(700))
        val result = VoiceSilenceTrimmer.trim(raw)

        assertTrue(result.applied)
        assertEquals(1, result.keepSpans.size)
        assertEquals(1800, result.stats.originalMs)
        // 启停点 ≈ 500/1100ms，再各加 120/240ms 留白
        assertTrue("processed=${result.stats.processedMs}", result.stats.processedMs in 900..1050)
        assertTrue("removed=${result.stats.removedMs}", result.stats.removedMs in 750..900)
        assertEquals(result.processed.size, result.keepSpans.sumOf { it.length })
        assertEquals(result.keepSpans, result.speechSpans)
    }

    @Test
    fun `trim edges keeps internal pauses`() {
        val raw = concat(silence(200), tone(400), silence(800), tone(400), silence(200))
        val result = VoiceSilenceTrimmer.trim(raw)

        assertEquals(1, result.keepSpans.size)
        assertEquals(2, result.stats.segmentCount)
        // 首尾静音被切掉，中间 800ms 停顿整体保留
        assertEquals(2, result.speechSpans.size)
        assertTrue("processed=${result.stats.processedMs}", result.stats.processedMs > 1500)
    }

    @Test
    fun `remove internal mode deletes the pause`() {
        val raw = concat(silence(200), tone(400), silence(800), tone(400), silence(200))
        val result = VoiceSilenceTrimmer.trim(
            raw,
            config.copy(mode = VoiceTrimMode.RemoveInternal)
        )

        assertEquals(2, result.keepSpans.size)
        assertEquals(2, result.stats.segmentCount)
        // 停顿先被两侧留白（120+240ms）归还给语音段，真正删掉的是 800-360=440ms 左右
        assertTrue("removed=${result.stats.removedMs}", result.stats.removedMs in 350..600)
        assertTrue("processed=${result.stats.processedMs}", result.stats.processedMs < 1600)
    }

    @Test
    fun `compress internal mode keeps the configured pause`() {
        val raw = concat(silence(200), tone(400), silence(800), tone(400), silence(200))
        val result = VoiceSilenceTrimmer.trim(
            raw,
            config.copy(mode = VoiceTrimMode.CompressInternal, keptSilenceMs = 200)
        )

        // 两段语音 + 夹在中间的一小段保留停顿
        assertEquals(3, result.keepSpans.size)
        assertTrue("removed=${result.stats.removedMs}", result.stats.removedMs in 150..500)
        assertTrue("processed=${result.stats.processedMs}", result.stats.processedMs > 1600)
    }

    @Test
    fun `audio shorter than one frame is returned untouched`() {
        val raw = tone(10)
        val result = VoiceSilenceTrimmer.trim(raw)

        assertEquals(VoicePreprocessFallback.TooShort, result.stats.fallback)
        assertFalse(result.applied)
        assertSame(raw, result.processed)
        assertEquals(1, result.keepSpans.size)
        assertTrue(result.removedSpans(raw.size).isEmpty())
    }

    @Test
    fun `pure silence reports no speech and keeps the audio`() {
        val raw = silence(1000)
        val result = VoiceSilenceTrimmer.trim(raw)

        assertEquals(VoicePreprocessFallback.NoSpeech, result.stats.fallback)
        assertSame(raw, result.processed)
        val analysis = result.analysis!!
        assertTrue(analysis.lowLevel)
        // 门限严格高于整段电平，故一帧也不判为语音（全零录音不会被当成语音）
        assertTrue(analysis.thresholdDb > analysis.peakDb)
        assertEquals(0f, analysis.speechFrameRatio, 0f)
    }

    @Test
    fun `drop policy returns empty audio when no speech is found`() {
        val raw = silence(1000)
        val result = VoiceSilenceTrimmer.trim(raw, config.copy(emptyPolicy = VoiceEmptyPolicy.Drop))

        assertEquals(VoicePreprocessFallback.NoSpeech, result.stats.fallback)
        assertTrue(result.processed.isEmpty())
        assertTrue(result.keepSpans.isEmpty())
        assertEquals(0, result.stats.processedMs)
        assertEquals(raw.size, result.removedSpans(raw.size).sumOf { it.length })
    }

    @Test
    fun `quiet recording is judged with the relative threshold`() {
        val raw = concat(silence(400, amp = 0.0005f), tone(600, amp = 0.002f), silence(400, amp = 0.0005f))
        val result = VoiceSilenceTrimmer.trim(raw)

        assertTrue(result.analysis!!.lowLevel)
        assertEquals(1, result.stats.segmentCount)
        assertTrue("removed=${result.stats.removedMs}", result.stats.removedMs > 300)
    }

    @Test
    fun `isolated short burst is not speech`() {
        // 20ms 脉冲低于最小语音段 160ms
        val raw = concat(silence(500), tone(20), silence(500))
        val result = VoiceSilenceTrimmer.trim(raw)

        assertEquals(VoicePreprocessFallback.NoSpeech, result.stats.fallback)
        assertSame(raw, result.processed)
    }

    @Test
    fun `output limit truncates from the tail`() {
        val raw = concat(silence(200), tone(2000), silence(200))
        val result = VoiceSilenceTrimmer.trim(raw, config.copy(maxOutputMs = 1000))

        assertTrue(result.stats.truncated)
        assertTrue(result.applied)
        assertTrue("processed=${result.stats.processedMs}", result.stats.processedMs <= 1000)
        assertTrue(result.processed.size <= config.samplesOf(1000))
    }

    @Test
    fun `removed spans complement the kept spans`() {
        val raw = concat(silence(500), tone(600), silence(700))
        val result = VoiceSilenceTrimmer.trim(raw)

        val removed = result.removedSpans(raw.size)
        assertEquals(raw.size, removed.sumOf { it.length } + result.keepSpans.sumOf { it.length })
        assertEquals(0, removed.first().start)
        assertEquals(raw.size, removed.last().end)
    }

    @Test
    fun `kept spans stay ordered and disjoint`() {
        val raw = concat(
            silence(300), tone(300), silence(600), tone(300), silence(600), tone(300), silence(300)
        )
        val result = VoiceSilenceTrimmer.trim(
            raw,
            config.copy(mode = VoiceTrimMode.RemoveInternal)
        )

        assertEquals(3, result.keepSpans.size)
        result.keepSpans.zipWithNext().forEach { (a, b) ->
            assertTrue("$a -> $b", a.end <= b.start)
        }
        assertEquals(result.processed.size, result.keepSpans.sumOf { it.length })
    }
}

private const val TEST_SAMPLE_RATE = 16000

private fun testSamples(ms: Int): Int = TEST_SAMPLE_RATE * ms / 1000

/** 静音：默认全零；给定幅度时用交替直流电平，便于构造低于绝对门限的弱底噪。 */
private fun silence(ms: Int, amp: Float = 0f): FloatArray =
    FloatArray(testSamples(ms)) { if (amp == 0f) 0f else if (it % 2 == 0) amp else -amp }

/** 类语音信号：正弦波，RMS = amp / √2。 */
private fun tone(ms: Int, amp: Float = 0.3f, freq: Float = 200f): FloatArray {
    val count = testSamples(ms)
    return FloatArray(count) { i -> (amp * sin(2.0 * PI * freq * i / TEST_SAMPLE_RATE)).toFloat() }
}

private fun concat(vararg parts: FloatArray): FloatArray {
    val out = FloatArray(parts.sumOf { it.size })
    var offset = 0
    for (part in parts) {
        part.copyInto(out, offset)
        offset += part.size
    }
    return out
}
