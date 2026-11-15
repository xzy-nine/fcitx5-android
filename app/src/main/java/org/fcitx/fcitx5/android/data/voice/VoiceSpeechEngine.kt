/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 * custom: 本地离线语音引擎，按 [VoiceModelFamily] 分派到 sherpa-onnx 的
 * `OnlineRecognizer`（流式）或 `OfflineRecognizer`（整段）。
 *
 * 关键约束：原生构造函数读到与家族不符的模型元数据时走 `_Exit`（直接杀 `:asr` 进程），
 * 因此家族必须由 app 侧**预先判定**（见 [VoiceModelSpec]），本文件只按给定家族构造一次，
 * 不做任何「失败再换一个家族」的尝试。
 *
 * 流式与整段两种引擎在会话语义上不同：
 *  - 流式：push 一段就出一次部分结果（[push] 返回非空）；
 *  - 整段：push 只累积音频（只做长度上限保护），[finish] 时才解码并返回全文。
 */
package org.fcitx.fcitx5.android.data.voice

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineDolphinModelConfig
import com.k2fsa.sherpa.onnx.OfflineFireRedAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineFireRedAsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineMedAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWenetCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OfflineZipformerCtcModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineNeMoCtcModelConfig
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineToneCtcModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig

/** 会话结束后多久无新会话即释放模型句柄。 */
const val IDLE_RELEASE_DELAY_MS = 60_000L

/** 采样率固定 16kHz（模型训练口径）。 */
const val SAMPLE_RATE = 16000

/** 单次会话允许累积的采样点数上限（约 5 分钟），防止离线引擎无限吃内存。 */
private const val MAX_OFFLINE_SAMPLES = SAMPLE_RATE * 60 * 5

/** 纯 CPU：int8 模型在 NNAPI 上命中率极低；2 线程避免模型常驻期间空转发热。 */
private const val NUM_THREADS = 2

/** 两种引擎共用的操作面（会话编排只依赖它）。 */
internal interface SpeechEngine {
    /** 开始一次会话：建立/重置本次会话的状态（流式建流，整段清空音频缓冲）。 */
    fun startSession()

    /** 送入一段 16kHz 单声道采样；返回当前部分结果（整段引擎恒为空串）。 */
    fun push(samples: FloatArray): String

    /** 结束会话并返回最终文本。 */
    fun finish(): String

    /** 丢弃当前会话的流（保留模型句柄供热启动）。 */
    fun cancel()

    /** 释放模型句柄。 */
    fun release()
}

/** 流式引擎：边送边出字。 */
private class StreamingEngine(private val recognizer: OnlineRecognizer) : SpeechEngine {

    /**
     * 会话流**惰性创建**：开始会话时丢弃旧流，真正要送音频时再建。
     *
     * 这样「建流」不再依赖 [startSession] 是否被调用过：任何调用顺序下 push 都能出字，
     * 不会出现「音频照收、字永远不出」。
     */
    private var stream: OnlineStream? = null

    override fun startSession() {
        stream?.release()
        stream = null
    }

    override fun push(samples: FloatArray): String {
        if (samples.isEmpty()) return ""
        val s = stream ?: recognizer.createStream().also { stream = it }
        s.acceptWaveform(samples, SAMPLE_RATE)
        while (recognizer.isReady(s)) recognizer.decode(s)
        return recognizer.getResult(s).text
    }

    override fun finish(): String {
        val s = stream ?: return ""
        s.inputFinished()
        while (recognizer.isReady(s)) recognizer.decode(s)
        val text = recognizer.getResult(s).text
        // 本次会话结束：丢弃流，保留模型句柄供热启动
        stream?.release()
        stream = null
        return text
    }

    override fun cancel() {
        stream?.release()
        stream = null
    }

    override fun release() {
        cancel()
        recognizer.release()
    }
}

/** 整段引擎：先累积音频，结束时一次解码。 */
private class OfflineEngine(private val recognizer: OfflineRecognizer) : SpeechEngine {

    private var samples = FloatArray(0)
    private var overflowed = false

    override fun startSession() {
        samples = FloatArray(0)
        overflowed = false
    }

    override fun push(samples: FloatArray): String {
        if (samples.isEmpty()) return ""
        if (this.samples.size + samples.size > MAX_OFFLINE_SAMPLES) {
            if (!overflowed) {
                overflowed = true
                Log.w("VoiceEngine", "offline session reached sample cap, ignoring the rest")
            }
            return ""
        }
        this.samples += samples
        return ""
    }

    override fun finish(): String {
        val audio = samples
        samples = FloatArray(0)
        if (audio.isEmpty()) return ""
        return try {
            val stream = recognizer.createStream()
            stream.acceptWaveform(audio, SAMPLE_RATE)
            recognizer.decode(stream)
            val text = recognizer.getResult(stream).text
            stream.release()
            text
        } catch (e: Throwable) {
            Log.e("VoiceEngine", "offline decode failed", e)
            ""
        }
    }

    override fun cancel() {
        samples = FloatArray(0)
    }

    override fun release() {
        recognizer.release()
    }
}

/**
 * 按 [VoiceModelSpec] 构造引擎；模型文件缺失或家族不被支持返回 null。
 *
 * 每个家族只走一条分支：**绝不**在失败后改用另一家族的配置重试。
 */
internal fun createSpeechEngine(spec: VoiceModelSpec): SpeechEngine? {
    val missing = spec.files.filterValues { it.isBlank() }
    if (missing.isNotEmpty()) {
        Log.e("VoiceEngine", "model files missing: ${missing.keys}")
        return null
    }
    return try {
        if (spec.family.streaming) {
            StreamingEngine(OnlineRecognizer(assetManager = null, config = onlineConfig(spec)))
        } else {
            OfflineEngine(OfflineRecognizer(assetManager = null, config = offlineConfig(spec)))
        }
    } catch (e: Throwable) {
        // 元数据与家族不符时原生侧通常是 _Exit（进程直接结束），能走到这里的一般是
        // 文件损坏/维度不符；记录后返回 null 由会话层报错
        Log.e("VoiceEngine", "createSpeechEngine failed for ${spec.family}", e)
        null
    }
}

private fun featureConfig() =
    FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80, dither = 0.0f)

private fun onlineModelConfig(spec: VoiceModelSpec): OnlineModelConfig = OnlineModelConfig(
    transducer = OnlineTransducerModelConfig(),
    paraformer = OnlineParaformerModelConfig(),
    zipformer2Ctc = OnlineZipformer2CtcModelConfig(),
    neMoCtc = OnlineNeMoCtcModelConfig(),
    toneCtc = OnlineToneCtcModelConfig(),
    tokens = spec.path(VoiceFileRole.TOKENS),
    numThreads = NUM_THREADS,
    provider = "cpu",
    debug = false,
).apply {
    when (spec.family) {
        VoiceModelFamily.OnlineTransducer -> transducer = OnlineTransducerModelConfig(
            encoder = spec.path(VoiceFileRole.ENCODER),
            decoder = spec.path(VoiceFileRole.DECODER),
            joiner = spec.path(VoiceFileRole.JOINER),
        )

        VoiceModelFamily.OnlineParaformer -> paraformer = OnlineParaformerModelConfig(
            encoder = spec.path(VoiceFileRole.ENCODER),
            decoder = spec.path(VoiceFileRole.DECODER),
        )

        VoiceModelFamily.OnlineZipformer2Ctc -> zipformer2Ctc =
            OnlineZipformer2CtcModelConfig(model = spec.path(VoiceFileRole.MODEL))

        VoiceModelFamily.OnlineNemoCtc -> neMoCtc =
            OnlineNeMoCtcModelConfig(model = spec.path(VoiceFileRole.MODEL))

        VoiceModelFamily.OnlineToneCtc -> toneCtc =
            OnlineToneCtcModelConfig(model = spec.path(VoiceFileRole.MODEL))

        else -> Unit
    }
}

private fun onlineConfig(spec: VoiceModelSpec): OnlineRecognizerConfig =
    OnlineRecognizerConfig(
        featConfig = featureConfig(),
        modelConfig = onlineModelConfig(spec),
        // 按住说话：由 UI 决定起止，不用引擎的静音端点判定
        enableEndpoint = false,
        decodingMethod = "greedy_search",
    )

private fun offlineModelConfig(spec: VoiceModelSpec): OfflineModelConfig = OfflineModelConfig(
    tokens = spec.path(VoiceFileRole.TOKENS),
    numThreads = NUM_THREADS,
    provider = "cpu",
    debug = false,
).apply {
    when (spec.family) {
        VoiceModelFamily.OfflineTransducer -> transducer = OfflineTransducerModelConfig(
            encoder = spec.path(VoiceFileRole.ENCODER),
            decoder = spec.path(VoiceFileRole.DECODER),
            joiner = spec.path(VoiceFileRole.JOINER),
        )

        VoiceModelFamily.OfflineParaformer -> paraformer =
            OfflineParaformerModelConfig(model = spec.path(VoiceFileRole.MODEL))

        VoiceModelFamily.OfflineWhisper -> whisper = OfflineWhisperModelConfig(
            encoder = spec.path(VoiceFileRole.ENCODER),
            decoder = spec.path(VoiceFileRole.DECODER),
            // 空串 = 多语模型按音频自行判定语种；单语（*.en）模型只会走英文
            language = spec.language,
            task = "transcribe",
        )

        // FireRed ASR 的构造只读元数据、不读 ONNX 元数据键里的判据，配置字段是 encoder/decoder
        VoiceModelFamily.OfflineFireRedAsr -> fireRedAsr = OfflineFireRedAsrModelConfig(
            encoder = spec.path(VoiceFileRole.ENCODER),
            decoder = spec.path(VoiceFileRole.DECODER),
        )

        VoiceModelFamily.OfflineSenseVoice -> senseVoice = OfflineSenseVoiceModelConfig(
            model = spec.path(VoiceFileRole.MODEL),
            // 空串归一成 auto，由模型按音频判定语种
            language = spec.language.ifBlank { "auto" },
            useInverseTextNormalization = spec.useInverseTextNormalization,
        )

        VoiceModelFamily.OfflineZipformerCtc -> zipformerCtc =
            OfflineZipformerCtcModelConfig(model = spec.path(VoiceFileRole.MODEL))

        VoiceModelFamily.OfflineNemoCtc -> nemo =
            OfflineNemoEncDecCtcModelConfig(model = spec.path(VoiceFileRole.MODEL))

        VoiceModelFamily.OfflineWenetCtc -> wenetCtc =
            OfflineWenetCtcModelConfig(model = spec.path(VoiceFileRole.MODEL))

        VoiceModelFamily.OfflineFireRedAsrCtc -> fireRedAsrCtc =
            OfflineFireRedAsrCtcModelConfig(model = spec.path(VoiceFileRole.MODEL))

        VoiceModelFamily.OfflineMedAsr -> medasr =
            OfflineMedAsrCtcModelConfig(model = spec.path(VoiceFileRole.MODEL))

        VoiceModelFamily.OfflineDolphin -> dolphin =
            OfflineDolphinModelConfig(model = spec.path(VoiceFileRole.MODEL))

        VoiceModelFamily.OfflineTeleSpeech -> teleSpeech = spec.path(VoiceFileRole.MODEL)

        else -> Unit
    }
}

private fun offlineConfig(spec: VoiceModelSpec): OfflineRecognizerConfig =
    OfflineRecognizerConfig(
        featConfig = featureConfig(),
        modelConfig = offlineModelConfig(spec),
        decodingMethod = "greedy_search",
    )
