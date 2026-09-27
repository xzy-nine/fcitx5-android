/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Kingz Cheung
 *
 * 移植自 Xime (https://github.com/ximeiorg/xime) 的 service/VoiceRecognitionHandler.kt，见仓库根 NOTICE.md。
 *
 * 与上游的差异（为适配 fcitx5-android）：
 *  - 上游直接持有 InputConnection 并 setComposingText/finishComposingText/commitText/deleteSurroundingText；
 *    本移植改为注入三个回调（writeComposing / commitVoiceText / finishComposing），由 IME 侧接到
 *    FcitxInputMethodService —— 因为本仓库的输入框内容由 fcitx 引擎与 FcitxInputMethodService 统一管理，
 *    绕过去自己写 InputConnection 会与引擎的 preedit/选区状态打架。
 *  - 上游的 resolveProviderName() 依赖 ExtensionManager（插件框架）；本移植改走
 *    AsrPluginHostRegistry + SettingsPreferences（见 speech/AsrPluginHost.kt）。
 *  - UI 状态类型 InputUIState（上游 79 字段的全局状态）裁剪为本模块需要的 VoiceUiState。
 *  - 因为 IME 侧的 commitText 语义本身就是「替换现有 composing」，上游
 *    deleteSurroundingText(partial.length, 0) 的兜底分支不再需要。
 *  - 标点启发式/partial 清洗抽到纯函数 VoiceTextRules，便于单测。
 *  - 语音会话结束/输入框切换时的弃置语义（abandonSession / suppressDuplicateFinal）逐字保留。
 */
package com.kingzcheung.xime.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.speech.AsrPluginHostRegistry
import com.kingzcheung.xime.speech.AsrSupport
import com.kingzcheung.xime.speech.RecognitionState
import com.kingzcheung.xime.speech.SpeechRecognitionManager
import com.kingzcheung.xime.speech.VoiceCommitPlan
import com.kingzcheung.xime.speech.VoiceTextRules
import com.kingzcheung.xime.util.FileLogger

class VoiceRecognitionHandler(
    private val context: Context,
    private val onStateChanged: (VoiceUiState) -> Unit,
    private val getState: () -> VoiceUiState,
    /** 部分结果：写入输入框的 composing 区域（IME 侧 → FcitxInputMethodService.setVoiceComposingText）。 */
    private val writeComposing: (String) -> Unit,
    /** 提交文本（IME 侧 → FcitxInputMethodService.commitText）。 */
    private val commitVoiceText: (String) -> Unit,
    /** 结束 composing 但不提交（收尾/取消）。 */
    private val finishComposing: () -> Unit,
    private val onVoiceComplete: () -> Unit = {},
    private val onAmplitudeChanged: (Float) -> Unit = {},
    private val onSpectrumChanged: (FloatArray) -> Unit = {},
) {
    companion object {
        private const val TAG = "VoiceRecognition"
    }

    private lateinit var speechRecognitionManager: SpeechRecognitionManager

    fun initialize() {
        FileLogger.i(TAG, "Initializing speech recognition system")

        speechRecognitionManager = SpeechRecognitionManager(context)

        speechRecognitionManager.setCallbacks(
            onResult = { text -> handleSpeechResult(text) },
            onPartialResult = { text -> handlePartialResult(text) },
            onStateChange = { state -> handleSpeechStateChange(state) },
            onError = { error, userVisible -> handleSpeechError(error, userVisible) },
            onAmplitude = { amplitude -> handleAmplitudeUpdate(amplitude) },
            onSpectrum = { spectrum -> handleSpectrumUpdate(spectrum) }
        )

        val providerName = resolveProviderName()

        onStateChanged(getState().copy(voicePluginName = providerName))
        FileLogger.i(TAG, "STT provider: $providerName")

        // 若「使用本地模型」开关已开启，启动时即加载模型并常驻，
        // 保证语音时绝不现场加载模型（避免丢开头音频）
        if (SettingsPreferences.isSttUseLocal(context) && AsrSupport.getLocalName() != null) {
            Thread {
                AsrSupport.warmup(context)
            }.start()
        }
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val delayedPreStartRunnable = Runnable {
        if (::speechRecognitionManager.isInitialized) {
            speechRecognitionManager.startPreStart()
        }
    }

    fun startDelayedPreStart(delayMs: Long = 150) {
        mainHandler.removeCallbacks(delayedPreStartRunnable)
        mainHandler.postDelayed(delayedPreStartRunnable, delayMs)
    }

    fun cancelPreStart() {
        mainHandler.removeCallbacks(delayedPreStartRunnable)
        if (::speechRecognitionManager.isInitialized) {
            speechRecognitionManager.cancelPreStart()
        }
    }

    fun startRecognition() {
        if (!::speechRecognitionManager.isInitialized) {
            Log.e(TAG, "speechRecognitionManager not initialized")
            onStateChanged(
                getState().copy(
                    isVoiceMode = false,
                    voiceSticky = false,
                    voiceRecognitionState = RecognitionState.ERROR
                )
            )
            return
        }

        val providerName = resolveProviderName()
        onStateChanged(getState().copy(voicePluginName = providerName))

        speechRecognitionManager.startRecognition()
    }

    fun stopRecognition() {
        if (::speechRecognitionManager.isInitialized) {
            speechRecognitionManager.stopRecognition()
        }
        // 最终结果由 handleSpeechResult 处理（stopRecognition 的 finalize 结果回来时）
    }

    fun release() {
        if (::speechRecognitionManager.isInitialized) {
            speechRecognitionManager.release()
        }
    }

    fun isInitialized(): Boolean = ::speechRecognitionManager.isInitialized

    private fun resolveProviderName(): String {
        // 用户开启「本地识别」时优先显示本地引擎名
        if (SettingsPreferences.isSttUseLocal(context)) {
            AsrSupport.getLocalName()?.let { return it }
        }
        val plugins = AsrPluginHostRegistry.enabledAsrPlugins(context)
        if (plugins.isNotEmpty()) {
            val selectedId = SettingsPreferences.getSttOnlinePluginId(context)
            return (plugins.firstOrNull { it.pluginId == selectedId } ?: plugins.first()).displayName
        }
        return "—"
    }

    private var lastPartialText = ""
    private var lastAmplitudeUpdate = 0L
    private var smoothedAmplitude = 0f
    private val smoothedSpectrum = FloatArray(16)
    // 抬起时已提交当前识别文本后，置真以忽略随后可能迟到的重复最终结果
    private var suppressDuplicateFinal = false
    // 输入法窗口隐藏等场景：丢弃本会话，迟到结果不得写入任何输入框
    private var sessionAbandoned = false
    private var errorToast: Toast? = null

    /** 输入法隐藏/切换输入框时调用：丢弃当前会话的未识别文本，忽略迟到的最终结果 */
    fun abandonSession() {
        sessionAbandoned = true
        lastPartialText = ""
    }

    // 语音按钮长按抬起时调用：立即提交当前已识别的文本（不依赖可能被断连竞态吞掉的异步最终结果）
    fun commitPendingOnRelease() {
        if (sessionAbandoned) return
        val partial = lastPartialText
        Log.d(TAG, "commitPendingOnRelease: partial='$partial', suppress=$suppressDuplicateFinal")
        if (partial.isEmpty()) return
        val punctuatedText = VoiceTextRules.addPunctuation(partial)
        commitFinal(punctuatedText, partial)
        suppressDuplicateFinal = true
        lastPartialText = ""
    }

    private fun handleSpeechResult(text: String) {
        Log.d(TAG, "Speech result (final): $text")

        if (sessionAbandoned) {
            sessionAbandoned = false
            lastPartialText = ""
            onVoiceComplete()
            return
        }

        if (suppressDuplicateFinal) {
            // 抬起时已提交，忽略迟到的重复最终结果
            suppressDuplicateFinal = false
            lastPartialText = ""
            onVoiceComplete()
            return
        }

        val cleanText = text.replace(" ", "")
        if (cleanText.isNotEmpty() && !cleanText.startsWith("错误:")) {
            val punctuatedText = VoiceTextRules.addPunctuation(cleanText)
            commitFinal(punctuatedText, lastPartialText)
        } else {
            finishComposing()
        }
        lastPartialText = ""
        onVoiceComplete()
    }

    // 增量语音模式：先结束 composing，再只提交增量，避免重复与整段重写。
    private fun commitFinal(finalText: String, partial: String) {
        finishComposing()
        when (val plan = VoiceTextRules.planCommit(finalText, partial)) {
            is VoiceCommitPlan.FinishOnly ->
                Log.d(TAG, "commitFinal: remainder empty, only finished composing")

            is VoiceCommitPlan.Append -> commitVoiceText(plan.remainder)
            is VoiceCommitPlan.Replace -> commitVoiceText(plan.text)
        }
        Log.d(TAG, "commitFinal: final='$finalText', partial='$partial'")
    }

    private fun handlePartialResult(text: String) {
        if (sessionAbandoned || suppressDuplicateFinal) return
        if (text == lastPartialText) return
        lastPartialText = text
        Log.d(TAG, "Speech result (partial): $text")

        // 过滤掉空格，避免显示空白
        val cleanText = VoiceTextRules.cleanPartial(text)
        if (cleanText.isEmpty()) return

        writeComposing(cleanText)
        onStateChanged(getState().copy(voiceRecognizedText = cleanText))
    }

    private fun handleSpeechStateChange(state: RecognitionState) {
        Log.d(TAG, "Speech state changed: $state")
        if (state == RecognitionState.LISTENING) {
            lastPartialText = ""
            suppressDuplicateFinal = false
            sessionAbandoned = false
        }
        onStateChanged(getState().copy(voiceRecognitionState = state))
    }

    private fun handleSpeechError(error: String, userVisible: Boolean) {
        Log.e(TAG, "Speech error: $error")
        FileLogger.e(TAG, "Speech error: $error")
        lastPartialText = ""
        if (userVisible && error.isNotBlank()) {
            mainHandler.post {
                errorToast?.cancel()
                errorToast = Toast.makeText(context, error, Toast.LENGTH_LONG)
                errorToast?.show()
            }
        }
        onVoiceComplete()
    }

    private fun handleAmplitudeUpdate(amplitude: Float) {
        val now = System.currentTimeMillis()
        if (now - lastAmplitudeUpdate < 80) return
        lastAmplitudeUpdate = now
        smoothedAmplitude = smoothedAmplitude * 0.45f + amplitude * 0.55f
        onAmplitudeChanged(smoothedAmplitude)
    }

    private fun handleSpectrumUpdate(spectrum: FloatArray) {
        val smoothed = smoothedSpectrum
        for (i in spectrum.indices) {
            if (i < smoothed.size) smoothed[i] = smoothed[i] * 0.5f + spectrum[i] * 0.5f
        }
        onSpectrumChanged(smoothed.copyOf())
    }
}
