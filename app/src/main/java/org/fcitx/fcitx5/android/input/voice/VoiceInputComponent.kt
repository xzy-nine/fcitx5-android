/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.media.AudioManager
import android.text.InputType
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.voice.VoiceModelStore
import org.fcitx.fcitx5.android.data.voice.VoicePermissionState
import org.fcitx.fcitx5.android.data.voice.VoiceRecognitionState
import org.fcitx.fcitx5.android.data.voice.VoiceSession
import org.fcitx.fcitx5.android.data.voice.VoiceUiState
import org.fcitx.fcitx5.android.data.voice.online.OnlineAsrRegistry
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
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

    private var session: VoiceSession? = null
    private var savedMediaVolume = -1

    /** 本次面板由空格长按进入（出字后自动回主键盘）。 */
    private var autoReturnOnCommit = false

    /** 本次会话是否已经提交过文本。 */
    private var committedThisSession = false

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
        localModelReady = VoiceModelStore.isReady(context, prefs.voiceAsrModelId.getValue()),
        onlineProviderReady = OnlineAsrRegistry.configured(context).isNotEmpty(),
        hasExternalVoiceIme = InputMethodUtil
            .findVoiceSubtype(kbdPrefs.preferredVoiceInput.getValue()) != null,
    )

    /**
     * 语音入口被触发。
     *
     * @param returnToKeyboardOnCommit 提交出文本后自动回到主键盘（空格长按进入时为 true，
     *   工具栏麦克风进入时为 false —— 后者留在语音页方便连续说话）。
     */
    fun onVoiceEntryClicked(returnToKeyboardOnCommit: Boolean) {
        if (isPasswordField()) {
            Timber.d("voice input: skipped on password field")
            return
        }
        when (planEntry()) {
            VoiceEntryPlan.OpenPanel -> openPanel(returnToKeyboardOnCommit)
            VoiceEntryPlan.SwitchExternalIme -> switchToExternalVoiceIme()
            VoiceEntryPlan.Noop -> Timber.d("voice input: no engine and no external voice IME")
        }
    }

    fun openPanel(returnToKeyboardOnCommit: Boolean = false) {
        autoReturnOnCommit = returnToKeyboardOnCommit
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

    private fun session(): VoiceSession = session ?: VoiceSession(
        context = context,
        callbacks = object : VoiceSession.Callbacks {
            override fun onState(state: VoiceUiState) {
                _state.value = state
            }

            override fun onPartialText(text: String) {
                // 部分结果写入 composing（复用 fcitx 的 preedit 状态机）
                service.setVoiceComposingText(text)
            }

            override fun onFinalText(text: String) {
                committedThisSession = true
                service.commitText(text)
            }

            override fun onSessionComplete() {
                _spectrum.value = FloatArray(SPECTRUM_BARS)
                // 空格长按进入：出字后自动回主键盘（未出字，例如识别失败/无语音，则留在面板）
                if (autoReturnOnCommit && committedThisSession) {
                    autoReturnOnCommit = false
                    committedThisSession = false
                    service.finishComposing()
                    ContextCompat.getMainExecutor(service).execute {
                        windowManager.attachWindow(KeyboardWindow)
                    }
                }
            }

            override fun onAmplitude(amplitude: Float) {
                // 振幅驱动可视化：整体缩放当前频谱
                val current = _spectrum.value
                _spectrum.value = FloatArray(SPECTRUM_BARS) { i ->
                    (current[i] * 0.7f + amplitude * 0.3f).coerceIn(0f, 1f)
                }
            }

            override fun onSpectrum(spectrum: FloatArray) {
                if (spectrum.isNotEmpty()) _spectrum.value = spectrum
            }
        },
    ).also { session = it }

    /** 按下麦克风：开始识别。 */
    fun startRecognition() {
        if (prefs.voiceMuteDuringRecording.getValue()) muteMedia()
        session().startRecognition()
        _state.value = _state.value.copy(isVoiceMode = true)
    }

    /** 抬起/再次点击麦克风：先提交已识别的部分，再停止。 */
    fun stopRecognition() {
        val s = session
        s?.commitPendingOnRelease()
        s?.stopRecognition()
        unmuteMedia()
    }

    /** 取消本次会话（丢弃已识别文本）。 */
    fun cancelSession() {
        val s = session
        s?.cancelPreStart()
        s?.abandonSession()
        service.finishComposing()
        unmuteMedia()
        _state.value = _state.value.copy(
            isVoiceMode = false,
            recognizedText = "",
            recognitionState = VoiceRecognitionState.IDLE,
            error = null,
        )
        _spectrum.value = FloatArray(SPECTRUM_BARS)
    }

    /** 面板显示：刷新权限缓存，并在引擎可用时自动开始识别（进入语音页即录音，无需手动点麦克风）。 */
    fun onPanelShown() {
        VoicePermissionState.refresh(context)
        _state.value = _state.value.copy(isVoiceMode = true)
        committedThisSession = false
        session().warmUp()
        if (canAutoStart()) startRecognition()
    }

    /** 当前选中的引擎是否已就绪（本地模型已下载 / 在线平台已配置）且已获录音权限。 */
    fun canAutoStart(): Boolean {
        if (!VoicePermissionState.has(context)) return false
        return session().isEngineReady()
    }

    /** 面板隐藏（切走窗口/输入框失焦）：丢弃未结束的会话，迟到结果不得上屏。 */
    fun onPanelHidden() {
        val s = session
        s?.cancelPreStart()
        s?.abandonSession()
        unmuteMedia()
        _state.value = _state.value.copy(
            isVoiceMode = false,
            recognizedText = "",
            recognitionState = VoiceRecognitionState.IDLE,
            error = null,
        )
        _spectrum.value = FloatArray(SPECTRUM_BARS)
    }

    /** 引擎展示名（本地 Zipformer / 在线平台名）。 */
    fun engineName(): String = _state.value.engineName

    /** 关闭「本地识别」开关时卸载 :asr 侧模型句柄。 */
    fun releaseLocalModel() {
        session?.releaseLocalModel()
    }

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
