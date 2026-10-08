/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 非流式语音输入的整段预处理参数与结果类型。
 *
 * 适用对象：松手后**整段提交**的识别路径——离线模型（整段解码）与批次上传的在线平台。
 * 流式路径（说话中持续发送音频）不做静音切除：它依赖连续的时间轴，切片会破坏端点判定。
 *
 * 参数对外统一用毫秒（便于设置页调参），内部换算成样本下标；采样率与采集链路
 * （[org.fcitx.fcitx5.android.data.voice.VoiceAudioCapture]）一致，为 16kHz 单声道。
 */
package org.fcitx.fcitx5.android.data.voice.preprocess

/** 静音切除方式（由保守到激进）。 */
enum class VoiceTrimMode {
    /** 只切首尾静音：内部停顿原样保留，改动面最小。 */
    TrimEdges,

    /**
     * 长停顿压缩：内部超过 [VoicePreprocessConfig.mergeGapMs] 的停顿压到
     * [VoicePreprocessConfig.keptSilenceMs]，短停顿保留原样（保住自然语流的节奏）。
     */
    CompressInternal,

    /** 长停顿移除：内部超过合并阈值的停顿整段删除，省流最多但语流被拼接。 */
    RemoveInternal,
}

/** 整段没有检测到语音时的处置。 */
enum class VoiceEmptyPolicy {
    /** 原样返回：宁可多传一段静音，也不丢低音量内容（默认）。 */
    KeepRaw,

    /** 返回空音频，由调用方提示「未检测到语音」。 */
    Drop,
}

/** 处理未能生效的原因（音频原样返回，或按 [VoiceEmptyPolicy] 返回空音频）。 */
enum class VoicePreprocessFallback {
    /** 音频短于一个分析帧。 */
    TooShort,

    /** VAD 未找到任何语音段。 */
    NoSpeech,
}

/**
 * 一次整段预处理的参数。
 *
 * 门限是**相对**噪声底的（见 [snrMarginDb]），因此同一套参数在安静与嘈杂环境下都能用：
 * 噪声底由整段各帧能量的低分位数估计，见 [VoiceVad.analyze]。
 */
data class VoicePreprocessConfig(
    val sampleRate: Int = SAMPLE_RATE,

    /** 分析帧长（毫秒）。 */
    val frameMs: Int = 20,

    /** 帧移（毫秒）；小于帧长即产生重叠，时间分辨率更高。 */
    val hopMs: Int = 10,

    /** 语音门限 = 噪声底 + 本余量（dB）。越大越保守，只切更明确的静音。 */
    val snrMarginDb: Float = 9f,

    /** 门限的绝对下限（dBFS）；整段电平低于它时改用纯相对门限（见 [VoiceVad.analyze]）。 */
    val minSpeechDb: Float = -50f,

    /** 最小语音段（毫秒）：短于此的孤立帧群视为噪声脉冲丢弃。 */
    val minSpeechMs: Int = 160,

    /**
     * 停顿合并阈值（毫秒）：间隔小于它的相邻语音段视为同一段。
     *
     * 内部停顿按**已加留白**的语音段之间的间隔计算（见 [leadPadMs] / [tailPadMs]），
     * 因此一段停顿要被当成「长停顿」处理，其长度需超过本阈值与两侧留白之和。
     */
    val mergeGapMs: Int = 300,

    /** 每个语音段向前留白（毫秒），避免切掉字头。 */
    val leadPadMs: Int = 120,

    /** 每个语音段向后留白（毫秒），避免切掉尾音。 */
    val tailPadMs: Int = 240,

    /** [VoiceTrimMode.CompressInternal] 下长停顿压缩后保留的时长（毫秒）。 */
    val keptSilenceMs: Int = 200,

    val mode: VoiceTrimMode = VoiceTrimMode.TrimEdges,

    val emptyPolicy: VoiceEmptyPolicy = VoiceEmptyPolicy.KeepRaw,

    /** 输出时长上限（毫秒）；0 = 不限制。超出时从尾部截断，并在统计里标记。 */
    val maxOutputMs: Int = 0,
) {

    val frameSamples: Int get() = (sampleRate * frameMs / 1000).coerceAtLeast(1)

    val hopSamples: Int get() = (sampleRate * hopMs / 1000).coerceAtLeast(1)

    fun samplesOf(ms: Int): Int = sampleRate * ms / 1000

    fun msOf(samples: Int): Int = samples * 1000 / sampleRate

    companion object {
        /** 与采集链路一致的采样率。 */
        const val SAMPLE_RATE = 16000
    }
}
