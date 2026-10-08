/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 整段语音的静音切除（纯逻辑，无 Android 依赖，可单测）。
 *
 * 输入 = 一段连续的 16kHz 单声道 float 采样（整段提交路径的原始录音），
 * 输出 = 切除后的采样 + 「原始音频里保留了哪些区间」+ 统计。
 *
 * 保留区间（[VoicePreprocessResult.keepSpans]）而不是只给一个结果数组，是为了让调用方
 * 既能预览处理前后的差异，也能在将来按同一份区间去对齐时间戳/分段上传。
 */
package org.fcitx.fcitx5.android.data.voice.preprocess

/** 一次预处理的统计信息（时长单位统一为毫秒）。 */
data class VoicePreprocessStats(
    val sampleRate: Int,
    val originalSamples: Int,
    val processedSamples: Int,
    val frameCount: Int,
    /** 判决为语音的帧占比（不含留白）。 */
    val speechFrameRatio: Float,
    /** 检测到的语音总时长（含留白，单位毫秒）。 */
    val speechMs: Int,
    /** 语音段数量（合并与留白之后）。 */
    val segmentCount: Int,
    val noiseFloorDb: Float,
    val peakDb: Float,
    val thresholdDb: Float,
    /** 整段电平偏低，门限只按噪声底推算。 */
    val lowLevel: Boolean,
    /** 输出被 [VoicePreprocessConfig.maxOutputMs] 截断。 */
    val truncated: Boolean,
    /** 非 null 表示本次未做切除（原因见 [VoicePreprocessFallback]）。 */
    val fallback: VoicePreprocessFallback?,
) {

    val originalMs: Int get() = originalSamples * 1000 / sampleRate

    val processedMs: Int get() = processedSamples * 1000 / sampleRate

    val removedMs: Int get() = (originalMs - processedMs).coerceAtLeast(0)

    /** 省下的时长占比（0..1）。 */
    val savedRatio: Float
        get() = if (originalMs <= 0) 0f else removedMs.toFloat() / originalMs
}

/**
 * 预处理结果。
 *
 * [keepSpans] 是**原始音频**上的保留区间（按时间升序、互不重叠）；把它们按序拼接即得
 * [processed]（当没有任何切除时 [processed] 直接复用入参数组，不做拷贝）。
 * [analysis] 为 null 表示音频过短、未做帧分析；否则可用于展示电平曲线与门限。
 */
data class VoicePreprocessResult(
    val processed: FloatArray,
    val keepSpans: List<VoiceSpan>,
    val speechSpans: List<VoiceSpan>,
    val analysis: VoiceVadAnalysis?,
    val stats: VoicePreprocessStats,
) {

    /** 是否按参数完成了切除（false = 走了兜底，音频原样或为空）。 */
    val applied: Boolean get() = stats.fallback == null

    /** 被切除的区间（[keepSpans] 在整段上的补集），供可视化使用。 */
    fun removedSpans(sampleCount: Int): List<VoiceSpan> {
        if (sampleCount <= 0) return emptyList()
        if (keepSpans.isEmpty()) return listOf(VoiceSpan(0, sampleCount))
        val removed = ArrayList<VoiceSpan>(keepSpans.size + 1)
        var cursor = 0
        for (span in keepSpans) {
            if (span.start > cursor) removed.add(VoiceSpan(cursor, span.start))
            cursor = maxOf(cursor, span.end)
        }
        if (cursor < sampleCount) removed.add(VoiceSpan(cursor, sampleCount))
        return removed
    }
}

object VoiceSilenceTrimmer {

    /**
     * 按 [config] 切除 [samples] 里的静音。
     *
     * 不修改入参；[samples] 为空或短于一帧时原样返回并标记 [VoicePreprocessFallback.TooShort]；
     * 未检测到语音时按 [VoicePreprocessConfig.emptyPolicy] 决定原样返回还是返回空音频。
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

        var keep = buildKeepSpans(speech, config)
        var truncated = false
        if (config.maxOutputMs > 0) {
            val capped = truncate(keep, config.samplesOf(config.maxOutputMs))
            truncated = capped.second
            keep = capped.first
        }
        if (keep.isEmpty()) {
            return passthrough(
                samples,
                config,
                analysis = analysis,
                speech = speech,
                fallback = VoicePreprocessFallback.NoSpeech,
            )
        }

        val processed = concat(samples, keep)
        val speechSamples = speech.sumOf { it.length }
        val stats = VoicePreprocessStats(
            sampleRate = config.sampleRate,
            originalSamples = samples.size,
            processedSamples = processed.size,
            frameCount = analysis.frameCount,
            speechFrameRatio = analysis.speechFrameRatio,
            speechMs = config.msOf(speechSamples),
            segmentCount = speech.size,
            noiseFloorDb = analysis.noiseFloorDb,
            peakDb = analysis.peakDb,
            thresholdDb = analysis.thresholdDb,
            lowLevel = analysis.lowLevel,
            truncated = truncated,
            fallback = null,
        )
        return VoicePreprocessResult(
            processed = processed,
            keepSpans = keep,
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
        val keep = if (drop || samples.isEmpty()) emptyList()
        else listOf(VoiceSpan(0, samples.size))
        val processed = if (drop) FloatArray(0) else samples
        val stats = VoicePreprocessStats(
            sampleRate = config.sampleRate,
            originalSamples = samples.size,
            processedSamples = processed.size,
            frameCount = analysis?.frameCount ?: 0,
            speechFrameRatio = analysis?.speechFrameRatio ?: 0f,
            speechMs = config.msOf(speech.sumOf { it.length }),
            segmentCount = speech.size,
            noiseFloorDb = analysis?.noiseFloorDb ?: 0f,
            peakDb = analysis?.peakDb ?: 0f,
            thresholdDb = analysis?.thresholdDb ?: 0f,
            lowLevel = analysis?.lowLevel ?: false,
            truncated = false,
            fallback = fallback,
        )
        return VoicePreprocessResult(
            processed = processed,
            keepSpans = keep,
            speechSpans = speech,
            analysis = analysis,
            stats = stats,
        )
    }

    /**
     * 语音段 → 保留区间。
     *
     * - [VoiceTrimMode.TrimEdges]：只保留「首段起点 … 末段终点」一整段，内部停顿不动；
     * - [VoiceTrimMode.CompressInternal]：段间长停顿保留尾部 [VoicePreprocessConfig.keptSilenceMs]
     *   （贴着后一段，语流更自然）；
     * - [VoiceTrimMode.RemoveInternal]：段间长停顿整段删除。
     */
    private fun buildKeepSpans(
        speech: List<VoiceSpan>,
        config: VoicePreprocessConfig,
    ): List<VoiceSpan> = when (config.mode) {
        VoiceTrimMode.TrimEdges -> listOf(VoiceSpan(speech.first().start, speech.last().end))

        VoiceTrimMode.CompressInternal, VoiceTrimMode.RemoveInternal -> {
            val keptSilence = if (config.mode == VoiceTrimMode.CompressInternal) {
                config.samplesOf(config.keptSilenceMs)
            } else {
                0
            }
            val spans = ArrayList<VoiceSpan>(speech.size * 2)
            speech.forEachIndexed { index, span ->
                if (index > 0) {
                    val gapStart = speech[index - 1].end
                    val gap = span.start - gapStart
                    val keep = minOf(gap, keptSilence)
                    if (keep > 0) spans.add(VoiceSpan(span.start - keep, span.start))
                }
                spans.add(span)
            }
            spans
        }
    }

    /** 从尾部截断到 [maxSamples]；返回新的区间表与是否发生了截断。 */
    private fun truncate(spans: List<VoiceSpan>, maxSamples: Int): Pair<List<VoiceSpan>, Boolean> {
        if (maxSamples <= 0) return spans to false
        val result = ArrayList<VoiceSpan>(spans.size)
        var used = 0
        var truncated = false
        for (span in spans) {
            if (used >= maxSamples) {
                truncated = true
                break
            }
            val room = maxSamples - used
            if (span.length <= room) {
                result.add(span)
                used += span.length
            } else {
                result.add(VoiceSpan(span.start, span.start + room))
                truncated = true
                break
            }
        }
        return result to truncated
    }

    /** 按区间表拼接；整体保留时直接复用入参数组，避免整段拷贝。 */
    private fun concat(samples: FloatArray, spans: List<VoiceSpan>): FloatArray {
        if (spans.size == 1 && spans[0].start == 0 && spans[0].end == samples.size) return samples
        val total = spans.sumOf { it.length }
        val out = FloatArray(total)
        var offset = 0
        for (span in spans) {
            samples.copyInto(out, offset, span.start, span.end)
            offset += span.length
        }
        return out
    }
}
