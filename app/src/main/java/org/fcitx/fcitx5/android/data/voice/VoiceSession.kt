/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 语音输入会话编排（IME 侧）。
 *
 * 职责：音频采集 → 引擎（本地 sherpa-onnx / 在线平台）→ partial/final 文本 → 状态与可视化。
 * 文本上屏仍由调用方（[org.fcitx.fcitx5.android.input.voice.VoiceInputComponent]）接到
 * FcitxInputMethodService，复用 fcitx 自己的 composing/选区状态机。
 *
 * 本地路径：`:asr` 进程内的官方 sherpa-onnx 引擎（见 [VoiceAsrService] / [VoiceAsrClient]）；
 * 在线路径：平台 provider（WebSocket / REST，按各平台官方文档实现）——
 *   迁移期暂时桥接旧的 Xime handler，provider 落地后删除（见 [legacyHandler]）。
 */
package org.fcitx.fcitx5.android.data.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.voice.online.OnlineAsrCallback
import org.fcitx.fcitx5.android.data.voice.online.OnlineAsrProvider
import org.fcitx.fcitx5.android.data.voice.online.OnlineAsrRegistry
import org.fcitx.fcitx5.android.data.voice.online.OnlineAsrSession
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

class VoiceSession(
    private val context: Context,
    private val callbacks: Callbacks,
) {

    companion object {
        private const val TAG = "VoiceSession"

        /** 面板频谱条数。 */
        const val SPECTRUM_BARS = 16

        /** 本地引擎展示名。 */
        const val LOCAL_ENGINE_NAME = "本地 Zipformer（离线）"

        /** 在线平台既没成功也没失败时，多久判定超时。 */
        private const val ONLINE_RESULT_TIMEOUT_MS = 30_000L
    }

    interface Callbacks {
        fun onState(state: VoiceUiState)

        /** 部分结果 → 写入输入框 composing 区域。 */
        fun onPartialText(text: String)

        /** 最终结果 → 提交上屏。 */
        fun onFinalText(text: String)

        /** 一次会话结束（成功或失败），用于面板收尾/回键盘判定。 */
        fun onSessionComplete()

        fun onAmplitude(amplitude: Float)

        fun onSpectrum(spectrum: FloatArray)
    }

    private val prefs = AppPrefs.getInstance().voice
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    private val _spectrum = MutableStateFlow(FloatArray(SPECTRUM_BARS))
    val spectrum: StateFlow<FloatArray> = _spectrum.asStateFlow()

    private var client: VoiceAsrClient? = null
    private var capture: VoiceAudioCapture? = null

    /** 在线平台会话（provider）。 */
    private var providerSession: OnlineAsrSession? = null

    /** 会话代次：迟到结果（停止/取消之后到达）一律丢弃。 */
    private val sessionSeq = AtomicInteger(0)

    private var lastPartial = ""
    private var running = false
    private var sessionFinished = false

    val isUsingLocalEngine: Boolean get() = prefs.voiceUseLocal.getValue()

    val engineName: String get() = _state.value.engineName

    /** 当前引擎是否已就绪（本地模型已下载 / 在线平台已配置）。 */
    fun isEngineReady(): Boolean = if (isUsingLocalEngine) {
        VoiceModelStore.isReady(context, prefs.voiceAsrModelId.getValue())
    } else {
        OnlineAsrRegistry.selected(context)?.isConfigured(context) == true
    }

    // ---- 生命周期 ----

    /** 预绑定 :asr 服务（面板打开时调用；失败不影响后续 start 时重试）。 */
    fun warmUp() {
        if (!isUsingLocalEngine) return
        scope.launch { runCatching { client().ensureBound() } }
    }

    /**
     * 开始一次会话。
     *
     * @return 是否成功开始（未就绪时返回 false，并已把错误写入状态）
     */
    fun startRecognition(): Boolean {
        if (running) return true
        if (!isUsingLocalEngine) return startOnlineSession()

        val modelId = prefs.voiceAsrModelId.getValue()
        val files = VoiceModelStore.resolve(context, modelId)
        if (files == null) {
            publishError("离线语音模型未下载，请先到模型市场下载")
            return false
        }

        val seq = sessionSeq.incrementAndGet()
        lastPartial = ""
        running = true
        updateState {
            it.copy(
                isVoiceMode = true,
                engineName = LOCAL_ENGINE_NAME,
                recognitionState = VoiceRecognitionState.LISTENING,
                recognizedText = "",
                error = null,
            )
        }

        scope.launch {
            val bound = client().ensureBound()
            if (!bound) {
                if (seq == sessionSeq.get()) {
                    running = false
                    publishError("无法连接离线识别服务（:asr）")
                }
                return@launch
            }
            val started = client().start(files, object : VoiceAsrClient.Callback {
                override fun onPartial(text: String) {
                    if (seq != sessionSeq.get() || text.isEmpty() || text == lastPartial) return
                    lastPartial = text
                    _state.value = _state.value.copy(recognizedText = text)
                    mainHandler.post {
                        if (seq == sessionSeq.get()) callbacks.onPartialText(text)
                    }
                }

                override fun onError(message: String) {
                    if (seq != sessionSeq.get()) return
                    publishError(message)
                }
            })
            if (!started) {
                if (seq == sessionSeq.get()) {
                    running = false
                    publishError("离线语音模型加载失败")
                }
                return@launch
            }
            mainHandler.post {
                if (seq != sessionSeq.get()) return@post
                startCapture(seq)
            }
        }
        return true
    }

    /** 松手/再次点击：停止采集并提交最终结果。 */
    fun stopRecognition() {
        if (!running) return
        running = false
        val seq = sessionSeq.get()
        capture?.stop()
        capture?.release()
        capture = null
        updateState { it.copy(recognitionState = VoiceRecognitionState.PROCESSING) }
        if (isUsingLocalEngine) {
            scope.launch {
                val final = runCatching { client().finish() }.getOrDefault("")
                if (seq != sessionSeq.get()) return@launch
                val text = VoiceTextRules.addPunctuation(final.ifBlank { lastPartial })
                if (text.isNotBlank()) {
                    mainHandler.post {
                        if (seq == sessionSeq.get()) callbacks.onFinalText(text)
                    }
                }
                mainHandler.post {
                    if (seq == sessionSeq.get()) finishSession()
                }
            }
        } else {
            // 在线：流式平台在此发尾包，批次平台（MiMo）在此整段上传；结果由 provider 回调
            runCatching { providerSession?.finish() }
                .onFailure { publishError(it.message ?: "在线识别失败") }
            // 兜底超时：provider 既没成功也没失败时给用户明确结果
            mainHandler.postDelayed({
                if (seq == sessionSeq.get() && !sessionFinished) {
                    publishError("在线识别超时")
                }
            }, ONLINE_RESULT_TIMEOUT_MS)
        }
    }

    /** 松手时兜底提交：正常路径由最终结果统一提交，这里只在引擎没给最终结果时补交最后一次部分结果。 */
    fun commitPendingOnRelease() {
        if (!running && lastPartial.isNotEmpty()) callbacks.onFinalText(lastPartial)
    }

    /** 取消进入语音面板前的预启动（当前实现无预启动，保留接口语义）。 */
    fun cancelPreStart() = Unit

    /** 丢弃当前会话：迟到结果不得上屏。 */
    fun abandonSession() {
        sessionSeq.incrementAndGet()
        running = false
        sessionFinished = true
        lastPartial = ""
        capture?.release()
        capture = null
        if (isUsingLocalEngine) {
            client().cancel()
        } else {
            runCatching { providerSession?.cancel() }
            providerSession = null
        }
        updateState {
            it.copy(
                isVoiceMode = false,
                recognitionState = VoiceRecognitionState.IDLE,
                recognizedText = "",
                error = null,
            )
        }
        _spectrum.value = FloatArray(SPECTRUM_BARS)
    }

    /** 释放资源（组件销毁）。 */
    fun release() {
        abandonSession()
        capture?.release()
        capture = null
        scope.cancel()
        if (isUsingLocalEngine) client().unbind()
    }

    /** 关闭「本地识别」开关时调用：卸载 :asr 侧模型句柄。 */
    fun releaseLocalModel() {
        scope.launch { client().releaseModel() }
    }

    // ---- 在线平台会话 ----

    private fun startOnlineSession(): Boolean {
        val provider = OnlineAsrRegistry.selected(context)
        if (provider == null) {
            publishError("没有可用的在线识别平台")
            return false
        }
        if (!provider.isConfigured(context)) {
            publishError("在线识别平台未配置：${context.getString(provider.nameRes)}")
            return false
        }
        val seq = sessionSeq.incrementAndGet()
        lastPartial = ""
        sessionFinished = false
        running = true
        updateState {
            it.copy(
                isVoiceMode = true,
                engineName = context.getString(provider.nameRes),
                recognitionState = VoiceRecognitionState.LISTENING,
                recognizedText = "",
                error = null,
            )
        }

        val callback = object : OnlineAsrCallback {
            override fun onPartial(text: String) {
                if (seq != sessionSeq.get() || text.isEmpty() || text == lastPartial) return
                lastPartial = text
                _state.value = _state.value.copy(recognizedText = text)
                mainHandler.post {
                    if (seq == sessionSeq.get()) callbacks.onPartialText(text)
                }
            }

            override fun onFinal(text: String) {
                if (seq != sessionSeq.get() || sessionFinished) return
                sessionFinished = true
                mainHandler.post {
                    if (seq != sessionSeq.get()) return@post
                    if (text.isNotBlank()) {
                        callbacks.onFinalText(VoiceTextRules.addPunctuation(text))
                    }
                    finishSession()
                }
            }

            override fun onError(message: String) {
                if (seq != sessionSeq.get() || sessionFinished) return
                sessionFinished = true
                publishError(message)
            }
        }

        return try {
            val s = provider.newSession(context, callback)
            providerSession = s
            s.start()
            mainHandler.post {
                if (seq == sessionSeq.get()) startCapture(seq, provider)
            }
            true
        } catch (e: Exception) {
            Timber.e(e, "$TAG: online session start failed")
            publishError(e.message ?: "在线识别启动失败")
            false
        }
    }

    // ---- 内部 ----

    private fun startCapture(seq: Int, provider: OnlineAsrProvider? = null) {
        val capture = VoiceAudioCapture(
            onSpeechChunk = { samples ->
                if (seq != sessionSeq.get()) return@VoiceAudioCapture
                if (provider != null) {
                    // 在线平台：流式平台边说边发；批次平台（MiMo）内部累积，松手时整段上传
                    runCatching { providerSession?.pushAudio(samples) }
                } else {
                    // 本地引擎：push 是同步 IPC，文本以返回值给出（采集线程）
                    val text = VoiceTextRules.cleanPartial(
                        runCatching { client().push(samples) }.getOrDefault("")
                    )
                    if (text.isNotEmpty() && text != lastPartial) {
                        lastPartial = text
                        _state.value = _state.value.copy(recognizedText = text)
                        mainHandler.post {
                            if (seq == sessionSeq.get()) callbacks.onPartialText(text)
                        }
                    }
                }
            },
            onAmplitude = { amplitude ->
                mainHandler.post { callbacks.onAmplitude(amplitude) }
            },
            onSpectrum = { spectrum ->
                _spectrum.value = spectrum
                mainHandler.post { callbacks.onSpectrum(spectrum) }
            },
            onError = { message -> if (seq == sessionSeq.get()) publishError(message) },
        )
        if (!capture.start()) {
            running = false
            return
        }
        this.capture = capture
    }

    private fun finishSession() {
        running = false
        sessionFinished = true
        lastPartial = ""
        providerSession = null
        _spectrum.value = FloatArray(SPECTRUM_BARS)
        _state.value = _state.value.copy(
            recognitionState = VoiceRecognitionState.IDLE,
            recognizedText = "",
        )
        mainHandler.post { callbacks.onSessionComplete() }
    }

    private fun publishError(message: String) {
        Timber.w("$TAG: $message")
        running = false
        sessionFinished = true
        providerSession = null
        _state.value = _state.value.copy(
            recognitionState = VoiceRecognitionState.ERROR,
            error = message,
        )
        mainHandler.post {
            callbacks.onSpectrum(FloatArray(SPECTRUM_BARS))
            callbacks.onState(_state.value)
            callbacks.onSessionComplete()
        }
    }

    private fun updateState(transform: (VoiceUiState) -> VoiceUiState) {
        val next = transform(_state.value)
        _state.value = next
        mainHandler.post { callbacks.onState(next) }
    }

    private fun client(): VoiceAsrClient = client ?: VoiceAsrClient(context).also { client = it }
}
