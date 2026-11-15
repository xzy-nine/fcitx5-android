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
 *   迁移期暂时桥接旧 handler，provider 落地后删除（见 [legacyHandler]）。
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
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.voice.online.OnlineAsrCallback
import org.fcitx.fcitx5.android.data.voice.online.OnlineAsrProvider
import org.fcitx.fcitx5.android.data.voice.online.OnlineAsrRegistry
import org.fcitx.fcitx5.android.data.voice.online.OnlineAsrSession
import org.fcitx.fcitx5.android.data.voice.preprocess.VoiceUtteranceBuffer
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

        /** 已停止采集后等待最终结果的上限；超过即视为异常并回到可再次识别的状态。 */
        private const val PROCESSING_TIMEOUT_MS = 3_000L
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

    /**
     * 非流式会话的整段音频缓冲。
     *
     * 非 null 表示本次会话的目标是整段提交：采集期间只累积音频，松手后统一预处理再送入引擎
     * （离线整段模型与批次上传的在线平台）。流式目标为 null，音频边采边送。
     */
    private var utterance: VoiceUtteranceBuffer? = null

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

    /** 引擎是否已在 :asr 加载完成（预热成功后为 true）。 */
    @Volatile
    private var enginePrepared = false

    /**
     * 面板打开时预热引擎：绑定 :asr 服务并让它**在后台线程**加载模型。
     *
     * 加载期间状态为 [VoiceRecognitionState.PREPARING]（面板显示"正在加载模型…"），
     * 加载完成回到 IDLE；这样后续 startRecognition 不必等模型加载，
     * 避免「停止后仍显示正在识别」（finish 排在加载后面）。
     */
    fun warmUp() {
        if (!isUsingLocalEngine) return
        if (enginePrepared) return
        // 先做便宜的「文件是否齐全」判断，避免为缺文件的模型白起一次协程
        val modelId = prefs.voiceAsrModelId.getValue()
        if (!VoiceModelStore.isReady(context, modelId)) return
        updateState {
            it.copy(
                engineName = LOCAL_ENGINE_NAME,
                recognitionState = if (running) it.recognitionState
                else VoiceRecognitionState.PREPARING,
            )
        }
        scope.launch {
            val spec = resolveSpec(modelId) ?: return@launch
            val bound = runCatching { client().ensureBound() }.getOrDefault(false)
            if (!bound) return@launch
            val ok = runCatching { client().prepare(spec) }.getOrDefault(false)
            enginePrepared = ok
            mainHandler.post {
                if (!running && _state.value.recognitionState == VoiceRecognitionState.PREPARING) {
                    updateState {
                        it.copy(recognitionState = VoiceRecognitionState.IDLE)
                    }
                }
            }
        }
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
        val seq = sessionSeq.incrementAndGet()
        lastPartial = ""
        sessionFinished = false
        running = true
        updateState {
            it.copy(
                isVoiceMode = true,
                engineName = LOCAL_ENGINE_NAME,
                // 模型尚未加载完成时先显示"正在加载模型"，加载完再进入聆听
                recognitionState = if (enginePrepared) VoiceRecognitionState.LISTENING
                else VoiceRecognitionState.PREPARING,
                recognizedText = "",
                error = null,
            )
        }

        scope.launch {
            // 家族判定要扫 onnx 头部区域，必须在 IO 线程上做
            val spec = resolveSpec(modelId)
            if (spec == null) {
                if (seq == sessionSeq.get()) {
                    val reason = withContext(Dispatchers.IO) {
                        VoiceModelStore.resolveDetailed(context, modelId)
                    }
                    running = false
                    publishError(
                        when (reason) {
                            // 文件在，但架构无法从模型自身还原（sherpa 侧需用户显式指定配置）
                            is VoiceModelClassifier.Result.Unknown ->
                                "该模型的架构无法自动识别，暂不支持"

                            else -> "离线语音模型不可用，请先到模型市场下载或导入"
                        }
                    )
                }
                return@launch
            }
            val bound = client().ensureBound()
            if (!bound) {
                if (seq == sessionSeq.get()) {
                    running = false
                    publishError("无法连接离线识别服务（:asr）")
                }
                return@launch
            }
            val started = client().start(spec, object : VoiceAsrClient.Callback {
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
            enginePrepared = started
            if (!started) {
                if (seq == sessionSeq.get()) {
                    running = false
                    publishError("离线语音模型加载失败")
                }
                return@launch
            }
            // 整段模型（离线家族）先缓冲、松手后统一预处理；流式家族仍边采边送
            utterance = if (spec.family.streaming) null else VoiceUtteranceBuffer()
            mainHandler.post {
                // 启动过程中用户已松手（stopRecognition 置 running=false）→ 不要再开采集
                if (seq != sessionSeq.get() || !running) return@post
                updateState { it.copy(recognitionState = VoiceRecognitionState.LISTENING) }
                startCapture(seq)
            }
        }
        return true
    }

    /**
     * 按 [VoiceModelSpec] 解析并构造引擎规格。
     *
     * 家族判定要顺序跳过整个 onnx 才能读到元数据（protobuf 的 metadata_props 排在 graph
     * 之后），因此**只能在 IO 线程调用**——面板打开的预热路径与开始识别路径都已经是协程。
     */
    private suspend fun resolveSpec(modelId: String): VoiceModelSpec? =
        withContext(Dispatchers.IO) { VoiceModelStore.resolve(context, modelId) }

    /**
     * 松手/再次点击：停止采集并提交最终结果。
     *
     * 注意：这里**不做 `running` 早退**——采集启动失败、或引擎还没起来就松手时，
     * 早退会把界面永远留在 LISTENING/PROCESSING（实测「按了停止还显示正在识别」）。
     */
    fun stopRecognition() {
        val seq = sessionSeq.get()
        val wasRunning = running
        running = false
        capture?.stop()
        capture?.release()
        capture = null
        // 取走整段缓冲：流式会话恒为 null，此时下面的 flush 是空操作
        val pending = utterance
        utterance = null
        if (!wasRunning && !isSessionActive()) {
            finishSession()
            return
        }
        updateState { it.copy(recognitionState = VoiceRecognitionState.PROCESSING) }
        if (isUsingLocalEngine) {
            scope.launch {
                flushUtterance(pending) { samples ->
                    runCatching { client().push(samples) }
                }
                if (seq != sessionSeq.get()) return@launch
                // 送入完成后再计时：预处理与逐块送入的耗时不计入「等待最终结果」
                armProcessingTimeout(seq)
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
            if (pending == null) {
                // 流式平台：在此发尾包；结果由 provider 回调
                runCatching { providerSession?.finish() }
                    .onFailure { publishError(it.message ?: "在线识别失败") }
            } else {
                // 批次平台：先送入预处理后的整段音频，再上传
                scope.launch {
                    flushUtterance(pending) { samples ->
                        runCatching { providerSession?.pushAudio(samples) }
                    }
                    if (seq != sessionSeq.get()) return@launch
                    runCatching { providerSession?.finish() }
                        .onFailure { publishError(it.message ?: "在线识别失败") }
                }
            }
            // 兜底超时：provider 既没成功也没失败时给用户明确结果
            mainHandler.postDelayed({
                if (seq == sessionSeq.get() && !sessionFinished) {
                    publishError("在线识别超时")
                }
            }, ONLINE_RESULT_TIMEOUT_MS)
        }
    }

    /** 已停止采集后等待最终结果的上限；超时即视为异常并回到可再次识别的状态。 */
    private fun armProcessingTimeout(seq: Int) {
        mainHandler.postDelayed({
            if (seq == sessionSeq.get() && _state.value.recognitionState ==
                VoiceRecognitionState.PROCESSING
            ) {
                Timber.w("$TAG: final result timeout, forcing idle")
                finishSession()
            }
        }, PROCESSING_TIMEOUT_MS)
    }

    /**
     * 取出整段缓冲、预处理后按块送入引擎；[pending] 为 null（流式会话）时不做任何事。
     *
     * 预处理是纯计算，放 Default 线程；块大小与采集链路一致，跨进程单次传参不会过大。
     */
    private suspend fun flushUtterance(
        pending: VoiceUtteranceBuffer?,
        push: (FloatArray) -> Unit,
    ) {
        if (pending == null) return
        val result = withContext(Dispatchers.Default) { runCatching { pending.drain() } }
            .getOrNull() ?: return
        val stats = result.stats
        Timber.i(
            "$TAG: preprocess ${stats.originalMs}ms -> ${stats.processedMs}ms " +
                    "(dropped=${stats.droppedMs}ms, sped=${stats.speedSavedMs}ms, " +
                    "fallback=${stats.fallback})"
        )
        result.processed.forEachPushChunk(push)
    }

    /** 是否有正在进行的会话（采集/加载/等待结果）；点按切换、停止都用它判断，避免只看 running。 */
    fun isSessionActive(): Boolean = running || when (_state.value.recognitionState) {
        VoiceRecognitionState.LISTENING,
        VoiceRecognitionState.PREPARING,
        VoiceRecognitionState.PROCESSING -> true

        else -> false
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
        utterance = null
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
        // 按是否持有 client 判断：本地引擎会话要解绑 :asr；在线会话也必须把
        // 此前预热时绑定的 client 解绑并清引用，避免泄漏 ServiceConnection
        val c = client
        client = null
        c?.unbind()
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
            // 批次平台（松手后整段上传）先缓冲、松手后统一预处理；流式平台仍边说边发
            utterance = if (provider.streamsAudio) null else VoiceUtteranceBuffer()
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
        // 整段目标在本会话内先缓冲，故采集侧不做逐块增益：响度统一由预处理决定
        val buffered = utterance
        val capture = VoiceAudioCapture(
            onSpeechChunk = { samples ->
                if (seq != sessionSeq.get()) return@VoiceAudioCapture
                val buffer = buffered
                if (buffer != null) {
                    // 整段目标：只累积，停止后统一预处理再送入引擎
                    buffer.append(samples)
                } else if (provider != null) {
                    // 在线平台：流式平台边说边发
                    runCatching { providerSession?.pushAudio(samples) }
                } else {
                    // 本地流式引擎：push 是同步 IPC，文本以返回值给出（采集线程）
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
            applyInputGain = buffered == null,
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
        utterance = null
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
        utterance = null
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
