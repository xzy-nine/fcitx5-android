/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.voice.preprocess

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/**
 * 响度均衡的单元行为：目标响度、增益上下限、峰值上限与「不改入参」约定。
 *
 * 验证一律回到输出采样上重新测一次 RMS，而不是只看统计字段（避免统计与实际增益脱节）。
 */
class VoiceLoudnessNormalizerTest {

    private val config = VoicePreprocessConfig()

    @Test
    fun `levelling brings the output to the target loudness`() {
        val samples = tone(400, amp = 0.02f)
        val ranges = listOf(VoiceSpan(0, samples.size))

        val result = VoiceLoudnessNormalizer.normalize(samples, ranges, config)

        assertTrue("rms=${result.speechRmsDb}", result.speechRmsDb < -30f)
        assertTrue("gain=${result.gainDb}", result.gainDb > 8f)
        assertEquals(config.targetRmsDb, result.speechRmsDb + result.gainDb, 0.01f)
        // 输出采样重新测得的 RMS 与目标一致
        assertEquals(config.targetRmsDb, VoiceLoudnessNormalizer.rmsDb(result.samples, ranges), 0.5f)
        assertFalse(result.limited)
        assertTrue("peak=${result.outputPeakDb}", result.outputPeakDb <= config.peakCeilingDb)
    }

    @Test
    fun `boost is clamped to the configured range`() {
        val samples = tone(400, amp = 0.0005f)
        val ranges = listOf(VoiceSpan(0, samples.size))

        val result = VoiceLoudnessNormalizer.normalize(samples, ranges, config)

        assertEquals(config.maxBoostDb, result.gainDb, 0.01f)
        assertTrue(result.limited)
        assertTrue("rms=${result.speechRmsDb}", result.speechRmsDb < -60f)
    }

    @Test
    fun `cut is clamped to the configured range`() {
        val samples = tone(400, amp = 0.9f)
        val ranges = listOf(VoiceSpan(0, samples.size))

        val result = VoiceLoudnessNormalizer.normalize(samples, ranges, config)

        assertEquals(-config.maxCutDb, result.gainDb, 0.01f)
        assertTrue(result.limited)
    }

    @Test
    fun `a stray spike is clipped instead of lowering the whole level`() {
        val samples = tone(400, amp = 0.02f)
        samples[samples.size / 2] = 0.9f
        val ranges = listOf(VoiceSpan(0, samples.size))

        val result = VoiceLoudnessNormalizer.normalize(samples, ranges, config)

        // 增益仍按语音响度给足，只有越界的那一两个采样被削顶
        assertTrue("gain=${result.gainDb}", result.gainDb > 8f)
        assertEquals(config.peakCeilingDb, result.outputPeakDb, 0.1f)
        val ceiling = 10f.pow(config.peakCeilingDb / 20f)
        assertEquals(1, result.samples.count { abs(it) >= ceiling - 1e-4f })
        assertTrue(result.limited)
    }

    @Test
    fun `levelling can be turned off`() {
        val samples = tone(400, amp = 0.02f)
        val ranges = listOf(VoiceSpan(0, samples.size))

        val result = VoiceLoudnessNormalizer.normalize(
            samples,
            ranges,
            config.copy(normalizeLoudness = false),
        )

        assertSame(samples, result.samples)
        assertEquals(0f, result.gainDb, 0f)
        assertEquals(result.inputPeakDb, result.outputPeakDb, 0f)
    }

    @Test
    fun `a silent range yields no gain`() {
        val samples = FloatArray(16000)
        val result = VoiceLoudnessNormalizer.normalize(
            samples,
            listOf(VoiceSpan(0, samples.size)),
            config,
        )

        assertEquals(VoiceLoudnessNormalizer.MIN_LEVEL_DB, result.speechRmsDb, 0.01f)
        assertEquals(0f, result.gainDb, 0f)
        assertSame(samples, result.samples)
    }

    @Test
    fun `empty ranges are not a signal`() {
        val samples = tone(200)
        val result = VoiceLoudnessNormalizer.normalize(samples, emptyList(), config)

        assertEquals(VoiceLoudnessNormalizer.MIN_LEVEL_DB, result.speechRmsDb, 0.01f)
        assertEquals(0f, result.gainDb, 0f)
        assertSame(samples, result.samples)
    }

    @Test
    fun `the input array is never modified`() {
        val samples = tone(400, amp = 0.02f)
        val before = samples.copyOf()
        val ranges = listOf(VoiceSpan(0, samples.size))

        val result = VoiceLoudnessNormalizer.normalize(samples, ranges, config)

        assertNotSame(samples, result.samples)
        assertArrayEquals(before, samples, 0f)
    }

    @Test
    fun `ranges outside the audio are ignored`() {
        val samples = tone(200, amp = 0.05f)
        val inside = VoiceLoudnessNormalizer.rmsDb(samples, listOf(VoiceSpan(0, samples.size)))

        val beyond = VoiceLoudnessNormalizer.rmsDb(samples, listOf(VoiceSpan(samples.size, samples.size + 100)))
        assertEquals(VoiceLoudnessNormalizer.MIN_LEVEL_DB, beyond, 0.01f)
        assertTrue(inside > VoiceLoudnessNormalizer.MIN_LEVEL_DB)
    }
}

private const val TEST_SAMPLE_RATE = 16000

/** 类语音信号：正弦波，RMS = amp / √2。 */
private fun tone(ms: Int, amp: Float = 0.3f, freq: Float = 200f): FloatArray {
    val count = TEST_SAMPLE_RATE * ms / 1000
    return FloatArray(count) { i -> (amp * sin(2.0 * PI * freq * i / TEST_SAMPLE_RATE)).toFloat() }
}
