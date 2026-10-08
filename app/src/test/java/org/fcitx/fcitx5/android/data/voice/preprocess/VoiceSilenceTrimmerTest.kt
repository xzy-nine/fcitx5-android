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
 * 整段预处理的端到端行为（合成音频）：首尾切除、长停顿快进压缩、留白、响度均衡与兜底路径。
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
        assertTrue("saved=${result.stats.savedMs}", result.stats.savedMs in 750..900)
        assertEquals(result.processed.size, result.keepSpans.sumOf { it.length })
        assertEquals(result.keepSpans, result.speechSpans)
    }

    @Test
    fun `long internal pause is fast-forwarded instead of cut`() {
        val raw = concat(silence(200), tone(400), silence(800), tone(400), silence(200))
        val result = VoiceSilenceTrimmer.trim(raw)

        // 两段语音 + 一段被快进的停顿；停顿内容仍在，故保留区间连成一整段
        assertEquals(3, result.segments.size)
        assertEquals(1, result.keepSpans.size)
        assertEquals(1, result.compressedSpans.size)
        assertEquals(result.processed.size, result.segments.sumOf { it.outputLength })
        assertEquals(2, result.stats.segmentCount)
        // 快进结果固定为「压缩后保留停顿」的时长，且确实短于来源
        val compressed = result.segments.single { it.compressed }
        assertEquals(config.samplesOf(config.effectiveKeptSilenceMs), compressed.outputLength)
        assertTrue("src=${compressed.sourceLength}", compressed.sourceLength > compressed.outputLength)
        // 停顿先被两侧留白（120+240ms）吃掉一部分，剩下的才参与压缩
        assertEquals(config.msOf(compressed.sourceLength), result.stats.compressedSourceMs)
        assertEquals(
            config.msOf(raw.size - result.keepSpans.sumOf { it.length }),
            result.stats.droppedMs
        )
    }

    @Test
    fun `kept silence is clamped below the pause merge threshold`() {
        // 保留时长不小于合并阈值时失去意义，生效值统一夹到阈值以下
        assertEquals(299, config.copy(mergeGapMs = 300, keptSilenceMs = 300).effectiveKeptSilenceMs)
        assertEquals(0, config.copy(mergeGapMs = 300, keptSilenceMs = 0).effectiveKeptSilenceMs)
        assertEquals(config.keptSilenceMs, config.effectiveKeptSilenceMs)

        // 夹取走的是流水线：停顿按 299ms 快进，而不是配置里的 300ms
        val raw = concat(silence(200), tone(400), silence(800), tone(400), silence(200))
        val clamped = VoiceSilenceTrimmer.trim(raw, config.copy(keptSilenceMs = config.mergeGapMs))
        assertTrue("compressed=${clamped.stats.compressedSourceMs}", clamped.stats.compressedSourceMs > 0)
        assertEquals(config.samplesOf(299), clamped.segments.single { it.compressed }.outputLength)
    }

    @Test
    fun `raising the merge threshold keeps the pause verbatim`() {
        val raw = concat(silence(200), tone(400), silence(800), tone(400), silence(200))

        // 停顿合并阈值高于停顿本身 → 两段语音并成一段，停顿原样保留（等同只做首尾切除）
        val keepAll = VoiceSilenceTrimmer.trim(raw, config.copy(mergeGapMs = 1200, keptSilenceMs = 1000))
        assertEquals(1, keepAll.keepSpans.size)
        assertEquals(1, keepAll.segments.size)
        assertTrue(keepAll.compressedSpans.isEmpty())
        assertEquals(0, keepAll.stats.compressedSourceMs)

        // 保留 0 → 停顿整段不进输出
        val dropAll = VoiceSilenceTrimmer.trim(raw, config.copy(keptSilenceMs = 0))
        assertTrue(dropAll.compressedSpans.isEmpty())
        assertEquals(0, dropAll.stats.compressedSourceMs)
        assertTrue(dropAll.segments.all { !it.compressed })
        assertTrue("dropped=${dropAll.stats.droppedMs}", dropAll.stats.droppedMs > 400)

        // 从「原样保留」到「整段移除」单调减少输出长度
        val compressed = VoiceSilenceTrimmer.trim(raw, config.copy(keptSilenceMs = 200))
        assertTrue(
            "${keepAll.stats.processedMs} > ${compressed.stats.processedMs} > ${dropAll.stats.processedMs}",
            keepAll.stats.processedMs > compressed.stats.processedMs &&
                    compressed.stats.processedMs > dropAll.stats.processedMs
        )
    }

    @Test
    fun `whole utterance fast-forward shortens the output uniformly`() {
        val raw = concat(silence(200), tone(2000), silence(200))
        val base = VoiceSilenceTrimmer.trim(raw)
        val fast = VoiceSilenceTrimmer.trim(raw, config.copy(fastForwardRate = 2f))

        // 拼装结果一致，差别只在整段时长按倍速等比缩短
        assertEquals(base.stats.assembledMs, fast.stats.assembledMs)
        assertEquals(base.stats.assembledMs, base.stats.processedMs)
        assertEquals(base.stats.processedMs / 2.0, fast.stats.processedMs.toDouble(), 2.0)
        assertEquals(base.stats.processedMs / 2.0, fast.stats.speedSavedMs.toDouble(), 2.0)
        // 均衡在快进之前完成，响度指标不受倍速影响
        assertEquals(base.stats.outputSpeechRmsDb, fast.stats.outputSpeechRmsDb, 0.01f)
    }

    @Test
    fun `pause fast-forward interpolates instead of dropping samples`() {
        // 停顿里放一段严格单调上升的弱信号（远低于门限，仍算停顿），据此分辨插值与丢样本
        val raw = concat(silence(200), tone(400), ramp(790), tone(400), silence(200))
        val result = VoiceSilenceTrimmer.trim(raw, config.copy(normalizeLoudness = false))
        val compressed = result.segments.single { it.compressed }
        val out = result.processed.copyOfRange(
            compressed.outputStart,
            compressed.outputStart + compressed.outputLength
        )
        val sourceLength = compressed.sourceLength
        val outputLength = compressed.outputLength

        // 逐点等于「源位置 = 下标 × 源长度 ÷ 输出长度」处的线性插值
        for (index in 0 until outputLength) {
            val position = index.toDouble() * sourceLength / outputLength
            val base = position.toInt().coerceAtMost(sourceLength - 1)
            val next = (base + 1).coerceAtMost(sourceLength - 1)
            val fraction = (position - base).toFloat()
            val lower = raw[compressed.start + base]
            val upper = raw[compressed.start + next]
            assertEquals(
                "index=$index",
                lower + (upper - lower) * fraction,
                out[index],
                1e-6f
            )
        }
        // 倍速不是整数，故第二个输出点落在源的两点之间：丢样本只会取其中之一
        val step = sourceLength.toDouble() / outputLength
        val base = step.toInt()
        val fraction = (step - base).toFloat()
        assertTrue("step=$step", fraction > 0f && fraction < 1f)
        val lower = raw[compressed.start + base]
        val upper = raw[compressed.start + base + 1]
        assertTrue("${out[1]} vs $lower/$upper", out[1] > lower && out[1] < upper)
    }

    @Test
    fun `fast-forward rate is clamped to the supported range`() {
        assertEquals(1f, config.effectiveFastForwardRate, 0f)
        assertEquals(2f, config.copy(fastForwardRate = 3f).effectiveFastForwardRate, 0f)
        assertEquals(1f, config.copy(fastForwardRate = 0.5f).effectiveFastForwardRate, 0f)
    }

    @Test
    fun `the output limit applies to the sped-up duration`() {
        val raw = concat(silence(200), tone(4000), silence(200))
        val result = VoiceSilenceTrimmer.trim(
            raw,
            config.copy(maxOutputMs = 1000, fastForwardRate = 2f)
        )

        assertTrue(result.stats.truncated)
        assertTrue("processed=${result.stats.processedMs}", result.stats.processedMs <= 1000)
        // 上限按最终时长计，故拼装时长可以是它的两倍
        assertTrue("assembled=${result.stats.assembledMs}", result.stats.assembledMs > 1000)
    }

    @Test
    fun `audio shorter than one frame is returned untouched`() {        val raw = tone(10)
        val result = VoiceSilenceTrimmer.trim(raw)

        assertEquals(VoicePreprocessFallback.TooShort, result.stats.fallback)
        assertFalse(result.applied)
        assertSame(raw, result.processed)
        assertEquals(1, result.keepSpans.size)
        assertTrue(result.droppedSpans(raw.size).isEmpty())
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
        assertEquals(raw.size, result.droppedSpans(raw.size).sumOf { it.length })
    }

    @Test
    fun `quiet recording is judged with the relative threshold`() {
        val raw = concat(silence(400, amp = 0.0005f), tone(600, amp = 0.002f), silence(400, amp = 0.0005f))
        val result = VoiceSilenceTrimmer.trim(raw)

        assertTrue(result.analysis!!.lowLevel)
        assertEquals(1, result.stats.segmentCount)
        assertTrue("saved=${result.stats.savedMs}", result.stats.savedMs > 300)
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
    fun `dropped spans complement the kept spans`() {
        val raw = concat(silence(500), tone(600), silence(700))
        val result = VoiceSilenceTrimmer.trim(raw)

        val dropped = result.droppedSpans(raw.size)
        assertEquals(raw.size, dropped.sumOf { it.length } + result.keepSpans.sumOf { it.length })
        assertEquals(0, dropped.first().start)
        assertEquals(raw.size, dropped.last().end)
    }

    @Test
    fun `kept spans stay ordered and disjoint`() {
        val raw = concat(
            silence(300), tone(300), silence(600), tone(300), silence(600), tone(300), silence(300)
        )
        val result = VoiceSilenceTrimmer.trim(raw, config.copy(keptSilenceMs = 0))

        assertEquals(3, result.keepSpans.size)
        result.keepSpans.zipWithNext().forEach { (a, b) ->
            assertTrue("$a -> $b", a.end <= b.start)
        }
        assertEquals(result.processed.size, result.keepSpans.sumOf { it.length })
    }

    @Test
    fun `quiet speech is levelled up to the target loudness`() {
        val raw = concat(silence(300), tone(600, amp = 0.02f), silence(300))
        val result = VoiceSilenceTrimmer.trim(raw)
        val stats = result.stats

        assertTrue("rms=${stats.speechRmsDb}", stats.speechRmsDb < -30f)
        assertTrue("gain=${stats.loudnessGainDb}", stats.loudnessGainDb > 8f)
        assertEquals(config.targetRmsDb, stats.outputSpeechRmsDb, 0.5f)
        // 电平确实被抬起来了，而不只是统计数字变了
        assertTrue("peak=${stats.outputPeakDb}", stats.outputPeakDb > stats.samplePeakDb + 10f)
        assertTrue("peak=${stats.outputPeakDb}", stats.outputPeakDb <= config.peakCeilingDb)
        assertFalse(stats.loudnessLimited)
    }

    @Test
    fun `loud speech is attenuated to the target loudness`() {
        val raw = concat(silence(200), tone(600, amp = 0.5f), silence(200))
        val result = VoiceSilenceTrimmer.trim(raw)
        val stats = result.stats

        assertTrue("gain=${stats.loudnessGainDb}", stats.loudnessGainDb < -6f)
        assertEquals(config.targetRmsDb, stats.outputSpeechRmsDb, 0.5f)
        assertTrue("peak=${stats.outputPeakDb}", stats.outputPeakDb < stats.samplePeakDb)
    }

    @Test
    fun `levelling can be turned off`() {
        val raw = concat(silence(300), tone(600, amp = 0.02f), silence(300))
        val result = VoiceSilenceTrimmer.trim(raw, config.copy(normalizeLoudness = false))
        val stats = result.stats

        assertEquals(0f, stats.loudnessGainDb, 0f)
        assertEquals(stats.speechRmsDb, stats.outputSpeechRmsDb, 0.001f)
        assertTrue("rms=${stats.speechRmsDb}", stats.speechRmsDb < -30f)
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

/** 严格单调上升的弱信号（远低于门限）：用来观察变速是插值还是丢样本。 */
private fun ramp(ms: Int, from: Float = 0.00001f, to: Float = 0.0002f): FloatArray {
    val count = testSamples(ms)
    return FloatArray(count) { i -> from + (to - from) * i / count }
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
