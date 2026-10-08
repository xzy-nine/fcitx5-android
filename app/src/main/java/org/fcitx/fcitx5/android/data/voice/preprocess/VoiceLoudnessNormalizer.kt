/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 整段语音的响度均衡（纯逻辑，无 Android 依赖，可单测）。
 *
 * 目的：麦克风增益、持机距离与说话音量都会让同一句话的采集电平差出一二十 dB，而识别模型
 * 对输入电平敏感——太小的幅度会被当成背景，太大的会削顶。这里按**语音段**的 RMS 把整段
 * 拉到一个固定响度，使同一批音频的电平一致。
 *
 * 只做整段统一增益（不逐帧压缩），因此不改变波形形状与信噪比；削顶只可能发生在增益之后
 * 仍然超过峰值上限的个别采样上。
 */
package org.fcitx.fcitx5.android.data.voice.preprocess

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 响度均衡结果。
 *
 * [samples] 在无需改动时与入参是同一实例（调用方不得原地修改）。
 */
data class VoiceLoudnessResult(
    val samples: FloatArray,
    /** 用于测响度的区间（语音段）内的 RMS（dBFS）；[VoiceLoudnessNormalizer.MIN_LEVEL_DB] 表示区间内无信号。 */
    val speechRmsDb: Float,
    /** 实际施加的增益（dB）；0 表示未改动。 */
    val gainDb: Float,
    /** 均衡前整段采样峰值（dBFS）。 */
    val inputPeakDb: Float,
    /** 均衡后整段采样峰值（dBFS）。 */
    val outputPeakDb: Float,
    /** 增益被 [VoicePreprocessConfig.maxBoostDb]/[VoicePreprocessConfig.maxCutDb] 限制，或有采样被削顶。 */
    val limited: Boolean,
)

object VoiceLoudnessNormalizer {

    /** 视为「无信号」的电平（dBFS）。 */
    const val MIN_LEVEL_DB = -100f

    /** 小于该增益视为无需改动，避免为不到 0.05dB 的差异复制整段音频。 */
    private const val GAIN_EPSILON_DB = 0.05f

    /**
     * 按 [config] 的响度参数均衡 [samples]。
     *
     * [levelRanges] 是用于测量响度的区间（按时间升序、互不重叠），调用方传**语音段与保留区间的交集**：
     * 语音段之间的静音不参与测响度，否则静音占比会拖动增益。
     * 关闭均衡、或区间内无有效信号时原样返回。
     */
    fun normalize(
        samples: FloatArray,
        levelRanges: List<VoiceSpan>,
        config: VoicePreprocessConfig,
    ): VoiceLoudnessResult {
        val speechRmsDb = rmsDb(samples, levelRanges)
        val inputPeakDb = dbOf(peakAmplitude(samples))
        if (!config.normalizeLoudness || speechRmsDb <= MIN_LEVEL_DB) {
            return VoiceLoudnessResult(samples, speechRmsDb, 0f, inputPeakDb, inputPeakDb, false)
        }

        val desiredDb = config.targetRmsDb - speechRmsDb
        val gainDb = desiredDb.coerceIn(-config.maxCutDb, config.maxBoostDb)
        val clamped = abs(gainDb - desiredDb) > GAIN_EPSILON_DB
        if (abs(gainDb) <= GAIN_EPSILON_DB) {
            return VoiceLoudnessResult(samples, speechRmsDb, 0f, inputPeakDb, inputPeakDb, clamped)
        }

        val gain = 10f.pow(gainDb / 20f)
        val ceiling = 10f.pow(config.peakCeilingDb / 20f).coerceIn(0f, 1f)
        val out = FloatArray(samples.size)
        var clipped = false
        var outputPeak = 0f
        for (i in samples.indices) {
            val value = samples[i] * gain
            val limitedValue = when {
                value > ceiling -> {
                    clipped = true
                    ceiling
                }

                value < -ceiling -> {
                    clipped = true
                    -ceiling
                }

                else -> value
            }
            out[i] = limitedValue
            val amplitude = abs(limitedValue)
            if (amplitude > outputPeak) outputPeak = amplitude
        }
        return VoiceLoudnessResult(
            samples = out,
            speechRmsDb = speechRmsDb,
            gainDb = gainDb,
            inputPeakDb = inputPeakDb,
            outputPeakDb = dbOf(outputPeak),
            limited = clamped || clipped,
        )
    }

    /** 若干区间内的 RMS（dBFS）；区间为空或落在边界外时返回 [MIN_LEVEL_DB]。 */
    fun rmsDb(samples: FloatArray, ranges: List<VoiceSpan>): Float {
        var sum = 0.0
        var count = 0
        for (range in ranges) {
            val from = range.start.coerceIn(0, samples.size)
            val to = range.end.coerceIn(from, samples.size)
            for (i in from until to) {
                val value = samples[i]
                sum += value.toDouble() * value
            }
            count += to - from
        }
        if (count <= 0) return MIN_LEVEL_DB
        val rms = sqrt(sum / count)
        return if (rms <= 0.0) MIN_LEVEL_DB else (20.0 * log10(rms)).toFloat()
    }

    /** 线性幅度 → dBFS；0 返回 [MIN_LEVEL_DB]。 */
    fun dbOf(amplitude: Float): Float =
        if (amplitude <= 0f) MIN_LEVEL_DB else (20f * log10(amplitude)).coerceAtLeast(MIN_LEVEL_DB)

    /** 整段采样峰值（绝对值最大者）。 */
    private fun peakAmplitude(samples: FloatArray): Float {
        var peak = 0f
        for (sample in samples) {
            val amplitude = abs(sample)
            if (amplitude > peak) peak = amplitude
        }
        return peak
    }
}
