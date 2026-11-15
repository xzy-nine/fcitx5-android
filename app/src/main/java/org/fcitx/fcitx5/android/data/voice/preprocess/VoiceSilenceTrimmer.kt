/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 整段语音的静音切除与响度均衡（纯逻辑，无 Android 依赖，可单测）。
 *
 * 输入 = 一段连续的 16kHz 单声道 float 采样（整段提交路径的原始录音），输出 = 处理后的采样 +
 * 「原始音频到输出音频的映射」+ 统计。
 *
 * 处理顺序：VAD 定段 → 生成映射（首尾切除、长停顿快进压缩）→ 按映射拼装 → 响度均衡 → 整段快进。
 * 响度均衡放在时间轴改动之后、整段快进之前：门限判决与切除范围始终基于原始电平，不受增益影响，
 * 而整段快进的重采样是相邻采样的线性插值，不会抬高电平，峰值上限仍然成立。
 *
 * 输出不只是结果数组，还带上映射表（[VoiceSegment]）：调用方既能预览处理前后的差异，
 * 也能在将来按同一份映射去对齐时间戳或分段上传。
 */
package org.fcitx.fcitx5.android.data.voice.preprocess

import kotlin.math.roundToInt

/** 一次预处理的统计信息（时长单位统一为毫秒）。 */
data class VoicePreprocessStats(
    val sampleRate: Int,
    val originalSamples: Int,
    /** 最终输出的采样数（整段快进之后）。 */
    val processedSamples: Int,
    /** 拼装后、整段快进之前的采样数；原速时等于 [processedSamples]。 */
    val assembledSamples: Int,
    val frameCount: Int,
    /** 判决为语音的帧占比（不含留白）。 */
    val speechFrameRatio: Float,
    /** 检测到的语音总时长（含留白，单位毫秒）。 */
    val speechMs: Int,
    /** 语音段数量（合并与留白之后）。 */
    val segmentCount: Int,
    val noiseFloorDb: Float,
    /** 各帧电平的 98% 分位数（[VoiceVad.analyze] 的门限参考量）。 */
    val peakDb: Float,
    /** 整段采样峰值（dBFS），均衡前。 */
    val samplePeakDb: Float,
    val thresholdDb: Float,
    /** 整段电平偏低，门限只按噪声底推算。 */
    val lowLevel: Boolean,
    /** 输出被 [VoicePreprocessConfig.maxOutputMs] 截断。 */
    val truncated: Boolean,
    /** 非 null 表示本次未做切除（原因见 [VoicePreprocessFallback]）。 */
    val fallback: VoicePreprocessFallback?,
    /** 完全没有进入输出的原始时长（首尾静音与截断掉的部分）。 */
    val droppedMs: Int,
    /** 被快进压缩（缩短）的**停顿**的原始时长。 */
    val compressedSourceMs: Int,
    /** 均衡前用于测响度的语音段 RMS（dBFS）。 */
    val speechRmsDb: Float,
    /** 实际施加的响度增益（dB）。 */
    val loudnessGainDb: Float,
    /** 均衡后的整段采样峰值（dBFS）；被削顶时即为削顶电平。 */
    val outputPeakDb: Float,
    /** 增益被范围限制或有采样被削顶，未达目标响度。 */
    val loudnessLimited: Boolean,
) {

    val originalMs: Int get() = originalSamples * 1000 / sampleRate

    val processedMs: Int get() = processedSamples * 1000 / sampleRate

    /** 拼装后、整段快进之前的输出时长（毫秒）。 */
    val assembledMs: Int get() = assembledSamples * 1000 / sampleRate

    /** 整段快进省下的时长（毫秒）。 */
    val speedSavedMs: Int get() = (assembledMs - processedMs).coerceAtLeast(0)

    /** 处理省下的时长：切除、停顿压缩与整段快进共同节约的部分。 */
    val savedMs: Int get() = (originalMs - processedMs).coerceAtLeast(0)

    /** 省下的时长占比（0..1）。 */
    val savedRatio: Float
        get() = if (originalMs <= 0) 0f else savedMs.toFloat() / originalMs

    /** 均衡后的语音段 RMS；增益是整段统一的，故等于均衡前加增益。 */
    val outputSpeechRmsDb: Float get() = speechRmsDb + loudnessGainDb
}

/**
 * 原始音频到输出音频的一段映射。
 *
 * `[start, end)` 是原始区间，`[outputStart, outputStart + outputLength)` 是它在输出里的位置。
 * [outputLength] 等于原始长度表示原样保留；小于表示该段被**快进压缩**（按倍速重采样）。
 */
data class VoiceSegment(
    val start: Int,
    val end: Int,
    val outputStart: Int,
    val outputLength: Int,
) {

    val sourceLength: Int get() = end - start

    val compressed: Boolean get() = outputLength < sourceLength

    val source: VoiceSpan get() = VoiceSpan(start, end)

    companion object {
        /** 原样保留的一段：输出长度等于原始长度。 */
        fun verbatim(start: Int, end: Int, outputStart: Int): VoiceSegment =
            VoiceSegment(start, end, outputStart, end - start)
    }
}

/**
 * 预处理结果。
 *
 * [segments] 是原始音频到输出的完整映射（按原始时间升序、原始区间互不重叠、输出区间首尾相接），
 * 按它拼装即得 [processed]（没有任何改动时 [processed] 直接复用入参数组，不做拷贝）。
 * [analysis] 为 null 表示音频过短、未做帧分析；否则可用于展示电平曲线与门限。
 */
data class VoicePreprocessResult(
    val processed: FloatArray,
    val segments: List<VoiceSegment>,
    val speechSpans: List<VoiceSpan>,
    val analysis: VoiceVadAnalysis?,
    val stats: VoicePreprocessStats,
) {

    /** 是否按参数完成了处理（false = 走了兜底，音频原样或为空）。 */
    val applied: Boolean get() = stats.fallback == null

    /** 保留了内容的原始区间（含被快进压缩的停顿），按时间升序合并相邻区间。 */
    val keepSpans: List<VoiceSpan> = mergeSpans(segments.map { it.source })

    /** 被快进压缩的原始区间（停顿），供可视化与统计。 */
    val compressedSpans: List<VoiceSpan> = segments.filter { it.compressed }.map { it.source }

    /** 整段里完全没有进入输出的区间（[keepSpans] 在整段上的补集）。 */
    fun droppedSpans(sampleCount: Int): List<VoiceSpan> {
        if (sampleCount <= 0) return emptyList()
        if (keepSpans.isEmpty()) return listOf(VoiceSpan(0, sampleCount))
        val dropped = ArrayList<VoiceSpan>(keepSpans.size + 1)
        var cursor = 0
        for (span in keepSpans) {
            if (span.start > cursor) dropped.add(VoiceSpan(cursor, span.start))
            cursor = maxOf(cursor, span.end)
        }
        if (cursor < sampleCount) dropped.add(VoiceSpan(cursor, sampleCount))
        return dropped
    }
}

object VoiceSilenceTrimmer {

    /**
     * 按 [config] 切除 [samples] 里的静音并做响度均衡。
     *
     * 不修改入参；[samples] 为空或短于一帧时原样返回并标记 [VoicePreprocessFallback.TooShort]；
     * 未检测到语音时按 [VoicePreprocessConfig.emptyPolicy] 决定原样返回还是返回空音频。
     * 兜底路径不做响度均衡：没有语音段可供测响度。
     */
    fun trim(
        samples: FloatArray,
        config: VoicePreprocessConfig = VoicePreprocessConfig(),
    ): VoicePreprocessResult {
        if (samples.size < config.frameSamples) {
            return passthrough(
                samples,
                config,
                analysis = null,
                speech = emptyList(),
                fallback = VoicePreprocessFallback.TooShort,
            )
        }

        val analysis = VoiceVad.analyze(samples, config)
        val speech = VoiceVad.speechSpans(samples.size, analysis, config)
        if (speech.isEmpty()) {
            return passthrough(
                samples,
                config,
                analysis = analysis,
                speech = emptyList(),
                fallback = VoicePreprocessFallback.NoSpeech,
            )
        }

        var segments = buildSegments(speech, config)
        var truncated = false
        if (config.maxOutputMs > 0) {
            // 上限按最终输出时长计，而截断发生在拼装域，故预算按倍速放大
            val budget = (config.samplesOf(config.maxOutputMs) * config.effectiveFastForwardRate)
                .roundToInt()
            val capped = truncate(segments, budget)
            truncated = capped.second
            segments = capped.first
        }
        if (segments.isEmpty()) {
            return passthrough(
                samples,
                config,
                analysis = analysis,
                speech = speech,
                fallback = VoicePreprocessFallback.NoSpeech,
            )
        }

        val assembled = assemble(samples, segments)
        val kept = mergeSpans(segments.map { it.source })
        val loaded = VoiceLoudnessNormalizer.normalize(assembled, intersect(speech, kept), config)
        val processed = speedUp(loaded.samples, config.effectiveFastForwardRate)
        val keptSamples = kept.sumOf { it.length }
        val compressedSamples = segments.filter { it.compressed }.sumOf { it.sourceLength }
        val stats = VoicePreprocessStats(
            sampleRate = config.sampleRate,
            originalSamples = samples.size,
            processedSamples = processed.size,
            assembledSamples = assembled.size,
            frameCount = analysis.frameCount,
            speechFrameRatio = analysis.speechFrameRatio,
            speechMs = config.msOf(speech.sumOf { it.length }),
            segmentCount = speech.size,
            noiseFloorDb = analysis.noiseFloorDb,
            peakDb = analysis.peakDb,
            samplePeakDb = loaded.inputPeakDb,
            thresholdDb = analysis.thresholdDb,
            lowLevel = analysis.lowLevel,
            truncated = truncated,
            fallback = null,
            droppedMs = config.msOf(samples.size - keptSamples),
            compressedSourceMs = config.msOf(compressedSamples),
            speechRmsDb = loaded.speechRmsDb,
            loudnessGainDb = loaded.gainDb,
            outputPeakDb = VoiceLoudnessNormalizer.dbOf(VoiceWaveform.peakAmplitude(processed)),
            loudnessLimited = loaded.limited,
        )
        return VoicePreprocessResult(
            processed = processed,
            segments = segments,
            speechSpans = speech,
            analysis = analysis,
            stats = stats,
        )
    }

    /** 兜底结果：按 [VoicePreprocessConfig.emptyPolicy] 保留原音频或返回空音频。 */
    private fun passthrough(
        samples: FloatArray,
        config: VoicePreprocessConfig,
        analysis: VoiceVadAnalysis?,
        speech: List<VoiceSpan>,
        fallback: VoicePreprocessFallback,
    ): VoicePreprocessResult {
        val drop = fallback == VoicePreprocessFallback.NoSpeech &&
                config.emptyPolicy == VoiceEmptyPolicy.Drop
        val segments = if (drop || samples.isEmpty()) {
            emptyList()
        } else {
            listOf(VoiceSegment.verbatim(0, samples.size, 0))
        }
        val processed = if (drop) FloatArray(0) else samples
        val stats = VoicePreprocessStats(
            sampleRate = config.sampleRate,
            originalSamples = samples.size,
            processedSamples = processed.size,
            assembledSamples = processed.size,
            frameCount = analysis?.frameCount ?: 0,
            speechFrameRatio = analysis?.speechFrameRatio ?: 0f,
            speechMs = config.msOf(speech.sumOf { it.length }),
            segmentCount = speech.size,
            noiseFloorDb = analysis?.noiseFloorDb ?: 0f,
            peakDb = analysis?.peakDb ?: 0f,
            samplePeakDb = VoiceLoudnessNormalizer.dbOf(VoiceWaveform.peakAmplitude(samples)),
            thresholdDb = analysis?.thresholdDb ?: 0f,
            lowLevel = analysis?.lowLevel ?: false,
            truncated = false,
            fallback = fallback,
            droppedMs = 0,
            compressedSourceMs = 0,
            speechRmsDb = VoiceLoudnessNormalizer.MIN_LEVEL_DB,
            loudnessGainDb = 0f,
            outputPeakDb = VoiceLoudnessNormalizer.dbOf(VoiceWaveform.peakAmplitude(samples)),
            loudnessLimited = false,
        )
        return VoicePreprocessResult(
            processed = processed,
            segments = segments,
            speechSpans = speech,
            analysis = analysis,
            stats = stats,
        )
    }

    /**
     * 语音段 → 映射表。
     *
     * 首尾静音不进入映射表（即被切掉）；段间停顿短于 [VoicePreprocessConfig.effectiveKeptSilenceMs]
     * 的原样保留，更长的则**快进压缩**到该时长（按倍速重采样，保留停顿的连贯与节奏，不做硬拼接）；
     * 该值为 0 时停顿整段不进入输出。
     */
    private fun buildSegments(
        speech: List<VoiceSpan>,
        config: VoicePreprocessConfig,
    ): List<VoiceSegment> {
        val keptSilence = config.samplesOf(config.effectiveKeptSilenceMs).coerceAtLeast(0)
        val segments = ArrayList<VoiceSegment>(speech.size * 2)
        var cursor = 0
        speech.forEachIndexed { index, span ->
            if (index > 0) {
                val gapStart = speech[index - 1].end
                val gap = span.start - gapStart
                if (gap > 0) {
                    val outputLength = if (gap <= keptSilence) gap else keptSilence
                    if (outputLength > 0) {
                        segments.add(VoiceSegment(gapStart, span.start, cursor, outputLength))
                        cursor += outputLength
                    }
                }
            }
            segments.add(VoiceSegment.verbatim(span.start, span.end, cursor))
            cursor += span.length
        }
        return segments
    }

    /** 按输出长度从尾部截断映射表；截断压缩段时按比例缩短其原始区间。 */
    private fun truncate(
        segments: List<VoiceSegment>,
        maxSamples: Int,
    ): Pair<List<VoiceSegment>, Boolean> {
        if (maxSamples <= 0) return segments to false
        val result = ArrayList<VoiceSegment>(segments.size)
        var used = 0
        var truncated = false
        for (segment in segments) {
            if (used >= maxSamples) {
                truncated = true
                break
            }
            val room = maxSamples - used
            if (segment.outputLength <= room) {
                result.add(segment)
                used += segment.outputLength
            } else {
                val ratio = room.toFloat() / segment.outputLength
                val sourceLength = (segment.sourceLength * ratio).toInt().coerceAtLeast(1)
                result.add(
                    VoiceSegment(
                        start = segment.start,
                        end = (segment.start + sourceLength).coerceAtMost(segment.end),
                        outputStart = segment.outputStart,
                        outputLength = room,
                    )
                )
                truncated = true
                break
            }
        }
        return result to truncated
    }

    /** 按映射表拼装；整段原样保留时直接复用入参数组，避免整段拷贝。 */
    private fun assemble(samples: FloatArray, segments: List<VoiceSegment>): FloatArray {
        if (segments.size == 1) {
            val only = segments[0]
            if (only.outputLength == samples.size && only.start == 0 && only.end == samples.size) {
                return samples
            }
        }
        val output = FloatArray(segments.sumOf { it.outputLength })
        for (segment in segments) {
            if (segment.outputLength == segment.sourceLength) {
                samples.copyInto(output, segment.outputStart, segment.start, segment.end)
            } else {
                resample(
                    samples = samples,
                    start = segment.start,
                    end = segment.end,
                    output = output,
                    outputStart = segment.outputStart,
                    outputLength = segment.outputLength,
                )
            }
        }
        return output
    }

    /**
     * 整段快进：按 [rate] 缩短整段时长（1 = 原速）。整段长度不变时直接复用入参数组。
     *
     * 倍速作用于**全部保留音频**，与只压缩停顿的映射表无关；音高随倍速升高，语音内容不变。
     */
    private fun speedUp(samples: FloatArray, rate: Float): FloatArray {
        if (rate <= 1f || samples.isEmpty()) return samples
        val outputLength = (samples.size / rate).roundToInt().coerceIn(1, samples.size)
        if (outputLength >= samples.size) return samples
        val output = FloatArray(outputLength)
        resample(
            samples = samples,
            start = 0,
            end = samples.size,
            output = output,
            outputStart = 0,
            outputLength = outputLength,
        )
        return output
    }

    /**
     * 把 `[start, end)` 变速写进 [output] 的 `[outputStart, outputStart + outputLength)`。
     *
     * 源位置 = 输出下标 × 源长度 ÷ 输出长度（即倍速 = 源长度 ÷ 输出长度），并在相邻两个采样之间
     * **线性插值**：等价于变速播放，音高随倍速升高，波形保持连续——只取整下标（丢采样）会在
     * 频谱上折出锯齿。线性插值本身带高频滚降，不再另加抗混叠滤波，以免削掉辅音的高频能量。
     */
    private fun resample(
        samples: FloatArray,
        start: Int,
        end: Int,
        output: FloatArray,
        outputStart: Int,
        outputLength: Int,
    ) {
        val sourceLength = end - start
        if (outputLength <= 0 || sourceLength <= 0) return
        val lastSourceIndex = sourceLength - 1
        for (index in 0 until outputLength) {
            val position = index.toDouble() * sourceLength / outputLength
            val base = position.toInt().coerceAtMost(lastSourceIndex)
            val next = (base + 1).coerceAtMost(lastSourceIndex)
            val fraction = (position - base).toFloat()
            val lower = samples[start + base]
            val upper = samples[start + next]
            output[outputStart + index] = lower + (upper - lower) * fraction
        }
    }

    /** 两组升序区间的交集。 */
    private fun intersect(first: List<VoiceSpan>, second: List<VoiceSpan>): List<VoiceSpan> {
        if (first.isEmpty() || second.isEmpty()) return emptyList()
        val result = ArrayList<VoiceSpan>(first.size)
        var i = 0
        var j = 0
        while (i < first.size && j < second.size) {
            val start = maxOf(first[i].start, second[j].start)
            val end = minOf(first[i].end, second[j].end)
            if (start < end) result.add(VoiceSpan(start, end))
            if (first[i].end <= second[j].end) i++ else j++
        }
        return result
    }
}

/** 合并相接或重叠的区间（映射表里相邻的原文区间在语义上是连续的）。 */
private fun mergeSpans(spans: List<VoiceSpan>): List<VoiceSpan> {
    if (spans.size <= 1) return spans
    val merged = ArrayList<VoiceSpan>(spans.size)
    var current = spans.first()
    for (index in 1 until spans.size) {
        val next = spans[index]
        current = if (next.start <= current.end) {
            VoiceSpan(current.start, maxOf(current.end, next.end))
        } else {
            merged.add(current)
            next
        }
    }
    merged.add(current)
    return merged
}
