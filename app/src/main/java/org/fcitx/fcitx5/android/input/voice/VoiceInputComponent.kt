/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.media.AudioManager
import android.text.InputType
import androidx.core.content.ContextCompat
import com.kingzcheung.xime.service.VoiceRecognitionHandler
import com.kingzcheung.xime.service.VoiceUiState
import com.kingzcheung.xime.speech.AsrModelManager
import com.kingzcheung.xime.speech.AsrPluginHostRegistry
import com.kingzcheung.xime.speech.RecognitionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.voice.VoicePermissionState
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.InputMethodUtil
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import org.mechdancer.dependency.manager.must
import timber.log.Timber

/**
 * custom: 内置语音输入的会话组件。
 *
 * 职责：
 * - 持有 Xime 移植过来的 [VoiceRecognitionHandler]（音频采集 + 引擎装配 + 部分/最终结果）；
 * - 把识别状态、频谱以 [StateFlow] 暴露给 IME 内的 Compose 面板；
 * - 把「部分结果 → composing 文本」「最终结果 → 上屏」接到 [FcitxInputMethodService]，
 *   从而复用 fcitx 自己的 composing/选区状态机（不自行写 InputConnection）；
 * - 语音入口的路由（内置面板 vs 回落外部语音输入法）。
 *
 * 生命周期：随 InputView 的 scope 存活（面板关闭后 handler 仍复用，
 * 但每次离开面板都会 `abandonSession()` 丢弃未结束的会话）。
 */
class VoiceInputComponent : UniqueComponent<VoiceInputComponent>(), Dependent,
    ManagedHandler by managedHandler() {

    companion object {
        /** 可视化条数（与 SpectrumAnalyzer 的频段数一致）。 */
        const val SPECTRUM_BARS = 16
    }

    private val context by manager.context()
    private val service by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()

    private val prefs = AppPrefs.getInstance().voice
    private val kbdPrefs = AppPrefs.getInstance().keyboard

    private val _state = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    private val _spectrum = MutableStateFlow(FloatArray(SPECTRUM_BARS))
    val spectrum: StateFlow<FloatArray> = _spectrum.asStateFlow()

    private var handler: VoiceRecognitionHandler? = null
    private var savedMediaVolume = -1

    /** 语音输入总开关。 */
    val isEnabled: Boolean get() = prefs.voiceInputEnabled.getValue()

    val isAutoMode: Boolean get() = prefs.voiceAutoMode.getValue()

    fun setAutoMode(enabled: Boolean) {
        prefs.voiceAutoMode.setValue(enabled)
    }

    fun isPermissionGranted(): Boolean = VoicePermissionState.has(context)

    /** 当前编辑器是否为密码框（密码框不提供语音输入）。 */
    private fun isPasswordField(): Boolean {
        val info = service.currentInputEditorInfo ?: return false
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        return cls == InputType.TYPE_CLASS_TEXT && (
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                        variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                        variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                )
    }

    fun planEntry(): VoiceEntryPlan = VoiceEntryRouting.plan(
        enabled = prefs.voiceInputEnabled.getValue(),
        permissionGranted = VoicePermissionState.has(context),
        useLocal = prefs.voiceUseLocal.getValue(),
        localModelReady = AsrModelManager(context).isModelReady(),
        onlinePluginReady = AsrPluginHostRegistry
            .enabledAsrPlugins(context)
            .any { it.isConfigured(context) },
        hasExternalVoiceIme = InputMethodUtil
            .findVoiceSubtype(kbdPrefs.preferredVoiceInput.getValue()) != null,
    )

    /**
     * 语音入口被触发（空格长按 / 工具栏麦克风）：
     * 按 [planEntry] 打开内置面板，或回落到外部语音输入法。
     */
    fun onVoiceEntryClicked() {
        if (isPasswordField()) {
            Timber.d("voice input: skipped on password field")
            return
        }
        when (planEntry()) {
            VoiceEntryPlan.OpenPanel -> openPanel()
            VoiceEntryPlan.SwitchExternalIme -> switchToExternalVoiceIme()
            VoiceEntryPlan.Noop -> Timber.d("voice input: no engine and no external voice IME")
        }
    }

    fun openPanel() {
        ContextCompat.getMainExecutor(service).execute {
            windowManager.attachWindow(VoiceInputWindow())
        }
    }

    private fun switchToExternalVoiceIme() {
        val id = kbdPrefs.preferredVoiceInput.getValue()
        val subtype = InputMethodUtil.findVoiceSubtype(id) ?: return
        val (imeId, sub) = subtype
        InputMethodUtil.switchInputMethod(service, imeId, sub)
    }

    // ---- 会话控制 ----

    private fun handler(): VoiceRecognitionHandler {
        handler?.let { return it }
        val h = VoiceRecognitionHandler(
            context = context,
            onStateChanged = { _state.value = it },
            getState = { _state.value },
            writeComposing = { service.setVoiceComposingText(it) },
            commitVoiceText = { service.commitText(it) },
            finishComposing = { service.finishComposing() },
            onVoiceComplete = {
                _spectrum.value = FloatArray(SPECTRUM_BARS)
            },
            onAmplitudeChanged = { amplitude ->
                // 振幅驱动可视化：整体缩放当前频谱
                val current = _spectrum.value
                _spectrum.value = FloatArray(SPECTRUM_BARS) { i ->
                    (current[i] * 0.7f + amplitude * 0.3f).coerceIn(0f, 1f)
                }
            },
            onSpectrumChanged = { spectrum ->
                if (spectrum.isNotEmpty()) _spectrum.value = spectrum
            },
        )
        h.initialize()
        handler = h
        return h
    }

    /** 按下麦克风：开始识别。 */
    fun startRecognition() {
        val h = handler()
        if (prefs.voiceMuteDuringRecording.getValue()) muteMedia()
        h.startRecognition()
        _state.value = _state.value.copy(isVoiceMode = true)
    }

    /** 抬起/再次点击麦克风：先提交已识别的部分，再停止。 */
    fun stopRecognition() {
        val h = handler ?: return
        h.commitPendingOnRelease()
        h.stopRecognition()
        unmuteMedia()
    }

    /** 取消本次会话（丢弃已识别文本）。 */
    fun cancelSession() {
        val h = handler
        h?.cancelPreStart()
        h?.abandonSession()
        h?.stopRecognition()
        service.finishComposing()
        unmuteMedia()
        _state.value = _state.value.copy(
            isVoiceMode = false,
            voiceRecognizedText = "",
            voiceRecognitionState = RecognitionState.IDLE,
        )
        _spectrum.value = FloatArray(SPECTRUM_BARS)
    }

    /** 面板显示：刷新权限缓存与引擎名。 */
    fun onPanelShown() {
        VoicePermissionState.refresh(context)
        _state.value = _state.value.copy(isVoiceMode = true)
    }

    /** 面板隐藏（切走窗口/输入框失焦）：丢弃未结束的会话，迟到结果不得上屏。 */
    fun onPanelHidden() {
        val h = handler
        h?.cancelPreStart()
        h?.abandonSession()
        h?.stopRecognition()
        unmuteMedia()
        _state.value = _state.value.copy(
            isVoiceMode = false,
            voiceRecognizedText = "",
            voiceRecognitionState = RecognitionState.IDLE,
        )
        _spectrum.value = FloatArray(SPECTRUM_BARS)
    }

    /** 引擎展示名（本地 Zipformer / 插件名）。 */
    fun engineName(): String = _state.value.voicePluginName

    // ---- 录音时静音媒体音量 ----

    private fun audioManager(): AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private fun muteMedia() {
        val am = audioManager() ?: return
        savedMediaVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (savedMediaVolume > 0) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        }
    }

    private fun unmuteMedia() {
        if (savedMediaVolume < 0) return
        audioManager()?.setStreamVolume(AudioManager.STREAM_MUSIC, savedMediaVolume, 0)
        savedMediaVolume = -1
    }
}
