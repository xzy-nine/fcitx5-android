/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 整段音频的语音活动检测（纯逻辑，无 Android 依赖，可单测）。
 *
 * 与流式 VAD 的取舍：这里拿得到**整段**音频，所以噪声底用全局低分位数估计（一遍统计），
 * 门限随录音环境自适应，不需要「先跑几百毫秒学噪声」的热身期；代价是必须等录音结束才能判决，
 * 因此只服务于整段提交的识别路径。
 *
 * 判决链条：
 *   1. 定长帧（[VoicePreprocessConfig.frameMs]，帧移 [VoicePreprocessConfig.hopMs]）算 RMS→dBFS；
 *   2. 噪声底 = 各帧 dBFS 的 5% 分位数，峰值 = 98% 分位数；
 *   3. 门限 = 噪声底 + 余量，夹在「噪声底 + 迟滞带」与「峰值下 12dB」之间，
 *      且不高于绝对下限 [VoicePreprocessConfig.minSpeechDb]；整段电平本身低于该下限时只用相对门限；
 *   4. 逐帧带迟滞判决（3dB）得到语音掩码；
 *   5. 合并间隔小于 [VoicePreprocessConfig.mergeGapMs] 的相邻段、丢弃有效语音短于
 *      [VoicePreprocessConfig.minSpeechMs] 的孤立段、两端按 lead/tail 留白扩边。
 */
package org.fcitx.fcitx5.android.data.voice.preprocess

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10

/** 一段样本区间：`[start, end)` 半开区间，单位是采样点。 */
data class VoiceSpan(val start: Int, val end: Int) {

    init {
        require(end >= start) { "VoiceSpan end($end) < start($start)" }
    }

    val length: Int get() = end - start

    val isEmpty: Boolean get() = end <= start

    /** 与另一段之间的空缺（采样点）；重叠或相接返回 0。 */
    fun gapTo(other: VoiceSpan): Int = (other.start - end).coerceAtLeast(0)

    fun shift(delta: Int): VoiceSpan = VoiceSpan(start + delta, end + delta)

    /** 收缩到 `[0, limit)`，可能变成空段。 */
    fun clamp(limit: Int): VoiceSpan = VoiceSpan(start.coerceIn(0, limit), end.coerceIn(0, limit))
}

/** 帧级分析结果；[speechMask] 为带迟滞的逐帧判决（未合并、未加留白）。 */
data class VoiceVadAnalysis(
    val frames: List<VoiceSpan>,
    val frameDbfs: FloatArray,
    val frameZeroCrossing: FloatArray,
    val noiseFloorDb: Float,
    val peakDb: Float,
    val thresholdDb: Float,
    val speechMask: BooleanArray,
    /** 整段电平低于绝对下限，门限只按噪声底推算。 */
    val lowLevel: Boolean,
) {

    val frameCount: Int get() = frames.size

    /** 判决为语音的帧占比（只在整段范围内统计，不代表时长占比）。 */
    val speechFrameRatio: Float
        get() = if (frameCount == 0) 0f
        else speechMask.count { it }.toFloat() / frameCount
}

object VoiceVad {

    /** 迟滞带宽度（dB）：起判比门限高一半、收判比门限低一半，避免帧间抖动。 */
    private const val HYSTERESIS_DB = 3f

    /** 门限相对峰值的最小差距（dB）：动态范围被压缩的录音不会被切得过狠。 */
    private const val MIN_PEAK_MARGIN_DB = 12f

    /** dBFS 下限，用于夹住极安静帧的数值。 */
    private const val MIN_DBFS = -100f

    /** 噪声底分位数：取整段最安静的 5% 帧。 */
    private const val NOISE_FLOOR_PERCENTILE = 0.05f

    /** 峰值分位数：取整段最响的 2% 帧，忽略单个爆点。 */
    private const val PEAK_PERCENTILE = 0.98f

    /** 过零统计的死区：低于它视为直流/抖动，不计入过零。 */
    private const val ZERO_CROSSING_EPS = 1e-4f

    /**
     * 逐帧分析整段音频。
     *
     * 帧覆盖到最后一个采样点：帧移推进后若剩余不足一帧，额外补一帧对齐到段尾，
     * 避免尾音落在最后一帧之外（帧内样本不足时按 0 补齐）。
     */
    fun analyze(samples: FloatArray, config: VoicePreprocessConfig): VoiceVadAnalysis {
        val starts = frameStarts(samples.size, config)
        val count = starts.size
        val frames = ArrayList<VoiceSpan>(count)
        val dbfs = FloatArray(count)
        val zcr = FloatArray(count)

        for (i in 0 until count) {
            val start = starts[i]
            val length = minOf(config.frameSamples, samples.size - start).coerceAtLeast(0)
            frames.add(VoiceSpan(start, start + length))
            var sum = 0.0
            var crossings = 0
            var previous = 0f
            for (k in 0 until length) {
                val v = samples[start + k]
                sum += v.toDouble() * v
                if (k > 0) {
                    val rising = v >= 0f
                    val wasRising = previous >= 0f
                    if (rising != wasRising &&
                        (abs(v) > ZERO_CROSSING_EPS || abs(previous) > ZERO_CROSSING_EPS)
                    ) {
                        crossings++
                    }
                }
                previous = v
            }
            val meanSquare = if (length > 0) sum / length else 0.0
            dbfs[i] = (10.0 * log10(meanSquare + 1e-12)).toFloat().coerceIn(MIN_DBFS, 0f)
            zcr[i] = if (length > 1) crossings.toFloat() / (length - 1) else 0f
        }

        val sorted = dbfs.clone().also { it.sort() }
        val noiseFloor = percentile(sorted, NOISE_FLOOR_PERCENTILE)
        val peak = percentile(sorted, PEAK_PERCENTILE)
        val lowLevel = peak <= config.minSpeechDb
        val relative = noiseFloor + config.snrMarginDb
        // 上限：门限不得高于「峰值下 MIN_PEAK_MARGIN_DB」，否则动态范围被压缩的录音一段都判不出来；
        // 下限：门限不得低于「噪声底 + 迟滞带」，否则毫无动态范围的整段（数字静音、恒定底噪）会全帧判成语音
        val capped = minOf(relative, peak - MIN_PEAK_MARGIN_DB)
            .coerceAtLeast(noiseFloor + HYSTERESIS_DB)
        val threshold = if (lowLevel) capped else maxOf(capped, config.minSpeechDb)

        val mask = BooleanArray(count)
        val on = threshold + HYSTERESIS_DB / 2f
        val off = threshold - HYSTERESIS_DB / 2f
        var speaking = false
        for (i in 0 until count) {
            if (speaking) {
                if (dbfs[i] < off) speaking = false
            } else {
                if (dbfs[i] > on) speaking = true
            }
            mask[i] = speaking
        }

        return VoiceVadAnalysis(
            frames = frames,
            frameDbfs = dbfs,
            frameZeroCrossing = zcr,
            noiseFloorDb = noiseFloor,
            peakDb = peak,
            thresholdDb = threshold,
            speechMask = mask,
            lowLevel = lowLevel,
        )
    }

    /**
     * 语音掩码 → 语音段（[sampleCount] 为整段样本数）。
     *
     * 顺序固定为「连续帧成段 → 合并近邻段 → 丢弃过短段 → 两端留白 → 留白后再次合并」：
     * 合并放在丢弃之前，是为了让爆破音/塞音这类**短促但贴着词**的能量块被并入相邻语音段，
     * 而不是因为自身过短被当成噪声丢掉。
     */
    fun speechSpans(
        sampleCount: Int,
        analysis: VoiceVadAnalysis,
        config: VoicePreprocessConfig,
    ): List<VoiceSpan> {
        if (sampleCount <= 0 || analysis.frameCount == 0) return emptyList()

        // 1. 连续为真的帧 → 原始段
        val runs = ArrayList<VoiceSpan>()
        var runStart = -1
        for (i in 0 until analysis.frameCount) {
            if (analysis.speechMask[i]) {
                if (runStart < 0) runStart = i
            } else if (runStart >= 0) {
                runs.add(frameRange(analysis, runStart, i - 1, sampleCount))
                runStart = -1
            }
        }
        if (runStart >= 0) {
            runs.add(frameRange(analysis, runStart, analysis.frameCount - 1, sampleCount))
        }
        if (runs.isEmpty()) return emptyList()

        // 2. 合并近邻段（间隔 < 合并阈值），同时累计每段的**有效语音**样本数
        val mergeGap = config.samplesOf(config.mergeGapMs)
        val merged = ArrayList<VoiceSpan>(runs.size)
        val voicedSamples = ArrayList<Int>(runs.size)
        var currentStart = runs[0].start
        var currentEnd = runs[0].end
        var currentVoiced = runs[0].length
        for (i in 1 until runs.size) {
            val run = runs[i]
            if (run.start - currentEnd < mergeGap) {
                currentEnd = run.end
                currentVoiced += run.length
            } else {
                merged.add(VoiceSpan(currentStart, currentEnd))
                voicedSamples.add(currentVoiced)
                currentStart = run.start
                currentEnd = run.end
                currentVoiced = run.length
            }
        }
        merged.add(VoiceSpan(currentStart, currentEnd))
        voicedSamples.add(currentVoiced)

        // 3. 丢弃有效语音过短的孤立段（噪声脉冲、点击声）
        val minVoiced = config.samplesOf(config.minSpeechMs)
        val kept = ArrayList<VoiceSpan>(merged.size)
        for (i in merged.indices) {
            if (voicedSamples[i] >= minVoiced) kept.add(merged[i])
        }
        if (kept.isEmpty()) return emptyList()

        // 4. 两端留白 + 5. 扩边后重叠的段再次合并
        val lead = config.samplesOf(config.leadPadMs)
        val tail = config.samplesOf(config.tailPadMs)
        val padded = ArrayList<VoiceSpan>(kept.size)
        for (span in kept) {
            val expanded = VoiceSpan(span.start - lead, span.end + tail).clamp(sampleCount)
            if (expanded.isEmpty) continue
            val last = padded.lastOrNull()
            if (last != null && expanded.start <= last.end) {
                padded[padded.size - 1] = VoiceSpan(last.start, maxOf(last.end, expanded.end))
            } else {
                padded.add(expanded)
            }
        }
        return padded
    }

    private fun frameRange(
        analysis: VoiceVadAnalysis,
        firstFrame: Int,
        lastFrame: Int,
        sampleCount: Int,
    ): VoiceSpan {
        val start = analysis.frames[firstFrame].start
        val end = analysis.frames[lastFrame].end
        return VoiceSpan(start, end).clamp(sampleCount)
    }

    /** 帧起始点序列；见 [analyze] 的尾部对齐说明。 */
    private fun frameStarts(sampleCount: Int, config: VoicePreprocessConfig): List<Int> {
        if (sampleCount <= 0) return emptyList()
        val frame = config.frameSamples
        val hop = config.hopSamples
        val starts = ArrayList<Int>()
        var start = 0
        while (start + frame <= sampleCount) {
            starts.add(start)
            start += hop
        }
        if (starts.isEmpty()) {
            starts.add(0)
        } else if (starts.last() + frame < sampleCount) {
            starts.add((sampleCount - frame).coerceAtLeast(0))
        }
        return starts
    }

    /** 线性插值分位数；[sorted] 必须已升序。 */
    private fun percentile(sorted: FloatArray, fraction: Float): Float {
        if (sorted.isEmpty()) return MIN_DBFS
        val position = fraction.coerceIn(0f, 1f) * (sorted.size - 1)
        val low = floor(position).toInt()
        val high = ceil(position).toInt()
        if (low == high) return sorted[low]
        return sorted[low] + (sorted[high] - sorted[low]) * (position - low)
    }
}
