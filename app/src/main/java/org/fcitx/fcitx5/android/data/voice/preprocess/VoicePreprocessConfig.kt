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

    /**
     * 内部长停顿压缩后保留的时长（毫秒）；短于它的停顿原样保留，0 = 停顿时整段不进入输出。
     *
     * 长停顿按**快进**处理（按倍速重采样，音高随之升高），保留停顿的连贯与节奏，不做硬拼接。
     * 取值必须短于 [mergeGapMs]，否则旋钮失去意义——见 [effectiveKeptSilenceMs]。
     */
    val keptSilenceMs: Int = 200,

    val emptyPolicy: VoiceEmptyPolicy = VoiceEmptyPolicy.KeepRaw,

    /**
     * 输出时长上限（毫秒）；0 = 不限制。超出时从尾部截断，并在统计里标记。
     *
     * 上限按**最终**输出时长计（即整段快进之后），不是快进前的拼装时长。
     */
    val maxOutputMs: Int = 0,

    /**
     * 整段快进倍速（1 = 原速，上限 [MAX_FAST_FORWARD_RATE]）。
     *
     * 与停顿压缩不同，这里作用于**处理后的全部保留音频**：整段按固定倍速变速播放（音高随之升高），
     * 时长与上传量按倍速等比减少。云端按音频时长计费，识别模型也能处理倍速语音，因此这是最直接的
     * 省量手段；再快会明显损失语音细节。
     */
    val fastForwardRate: Float = 1f,

    /**
     * 是否做整段响度均衡：按语音段 RMS 把整段拉到一个固定响度。
     *
     * 麦克风增益、持机距离与说话音量都会改变采集电平，而识别模型对输入电平敏感；
     * 均衡后同一批音频的响度一致，安静录音也不会因为幅度过小被模型忽略。
     * 在切除**之后**执行：门限判决始终基于原始电平，切除结果不受均衡影响。
     */
    val normalizeLoudness: Boolean = true,

    /** 均衡目标：语音段 RMS（dBFS）。 */
    val targetRmsDb: Float = -20f,

    /** 均衡后的采样峰值上限（dBFS），超出即削顶。 */
    val peakCeilingDb: Float = -1f,

    /** 最大提升（dB），防止把底噪放大成主信号。 */
    val maxBoostDb: Float = 20f,

    /** 最大衰减（dB），防止把异常大的录音压得过低。 */
    val maxCutDb: Float = 12f,
) {

    /**
     * 实际生效的「压缩后保留停顿」（毫秒）：统一夹到 [mergeGapMs] 以下。
     *
     * 间隔小于合并阈值的相邻语音段在 VAD 阶段就并成了同一段，只有更长的间隔才作为「长停顿」进入
     * 压缩；保留时长不小于阈值时，能被压缩的只剩「阈值 + 两侧留白」以上的停顿，阈值刚认定的那批
     * 长停顿反而原样保留，旋钮失去意义。这里统一夹取，避免调用方组出这种无效组合。
     */
    val effectiveKeptSilenceMs: Int
        get() = keptSilenceMs.coerceAtMost((mergeGapMs - 1).coerceAtLeast(0))

    /** 实际生效的整段快进倍速：夹在 1（原速）与 [MAX_FAST_FORWARD_RATE] 之间。 */
    val effectiveFastForwardRate: Float
        get() = fastForwardRate.coerceIn(1f, MAX_FAST_FORWARD_RATE)

    val frameSamples: Int get() = (sampleRate * frameMs / 1000).coerceAtLeast(1)

    val hopSamples: Int get() = (sampleRate * hopMs / 1000).coerceAtLeast(1)

    fun samplesOf(ms: Int): Int = sampleRate * ms / 1000

    fun msOf(samples: Int): Int = samples * 1000 / sampleRate

    companion object {
        /** 与采集链路一致的采样率。 */
        const val SAMPLE_RATE = 16000

        /** 整段快进倍速上限：再快语音细节损失过多。 */
        const val MAX_FAST_FORWARD_RATE = 2f
    }
}
