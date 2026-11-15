/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.text.InputType
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
import org.fcitx.fcitx5.android.utils.InputMethodUtil
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import timber.log.Timber

/**
 * custom: 内置语音输入的会话组件。
 *
 * 职责：
 * - 持有 [VoiceSession]（音频采集 + 引擎装配 + 部分/最终结果）；
 * - 把识别状态、频谱以 [StateFlow] 暴露给 IME 内的 Compose 面板；
 * - 把「部分结果 → composing 文本」「最终结果 → 上屏」接到 [FcitxInputMethodService]，
 *   复用 fcitx 自己的 composing/选区状态机（不自行写 InputConnection）；
 * - 语音入口的路由（内置面板 vs 回落外部语音输入法）。
 *
 * **面板不再是独立 InputWindow**：改为 [panelVisible] 驱动的覆盖层（见 [VoiceInputCoverHost]），
 * 键盘窗口保持 attach —— 这样工具栏可见可用、空格长按的手势也不会被窗口切换打断
 * （「物理松手停止」靠键盘侧对长按动作补的 release 回调）。
 */
class VoiceInputComponent : UniqueComponent<VoiceInputComponent>(), Dependent,
    ManagedHandler by managedHandler() {

    companion object {
        /** 可视化条数（与 VoiceSpectrum 的频段数一致）。 */
        const val SPECTRUM_BARS = 16

        /**
         * 长按模式（空格长按 / 面板麦克风长按）松手后的尾部采集时长。
         *
         * 抬手瞬间采集线程可能还停在一次 `read()` 上，最后一个 100ms 分块与
         * 引擎的收尾解码都需要时间；立即停止会让句尾一两个字丢失。
         */
        const val HOLD_RELEASE_TAIL_MS = 300L
    }

    private val context by manager.context()
    private val service by manager.inputMethodService()

    private val prefs = AppPrefs.getInstance().voice
    private val kbdPrefs = AppPrefs.getInstance().keyboard

    private val _state = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    private val _spectrum = MutableStateFlow(FloatArray(SPECTRUM_BARS))
    val spectrum: StateFlow<FloatArray> = _spectrum.asStateFlow()

    /** 面板是否显示（覆盖层可见性）。 */
    private val _panelVisible = MutableStateFlow(false)
    val panelVisible: StateFlow<Boolean> = _panelVisible.asStateFlow()

    var panelVisibleListener: ((Boolean) -> Unit)? = null

    private var session: VoiceSession? = null
    private var savedMediaVolume = -1

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 长按松手后的尾音延迟停止任务。
     *
     * 长按路径的松手先延时 [HOLD_RELEASE_TAIL_MS] 再真正停止，让尾音进入引擎；
     * 期间若面板被收起/取消/重新开录，取消该任务并直接收尾。
     */
    private var pendingHoldStop: Runnable? = null

    /** 本次面板由空格长按进入（出字后自动回主键盘）。 */
    private var autoReturnOnCommit = false

    /** 本次会话是否已经提交过文本。 */
    private var committedThisSession = false

    /** 语音输入总开关。 */
    val isEnabled: Boolean get() = prefs.voiceInputEnabled.getValue()

    fun isPermissionGranted(): Boolean = VoicePermissionState.has(context)

    /** 当前编辑框的识别状态（面板文案用）。 */
    fun recognitionState(): VoiceRecognitionState = _state.value.recognitionState

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
        onlineProviderReady = OnlineAsrRegistry.selected(context)
            ?.isConfigured(context) == true,
        hasExternalVoiceIme = InputMethodUtil
            .findVoiceSubtype(kbdPrefs.preferredVoiceInput.getValue()) != null,
    )

    /**
     * 语音入口被触发。
     *
     * @param returnToKeyboardOnCommit 提交出文本后自动收起面板回键盘（空格长按进入时为 true，
     *   工具栏麦克风进入时为 false —— 后者留在面板方便连续说话）。
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

    /**
     * 打开语音面板。
     *
     * @param returnToKeyboardOnCommit 见 [onVoiceEntryClicked]；
     *   **true（空格长按）时面板打开即开始录音**——继承空格的长按状态，物理松手由键盘侧回调停止；
     *   false（工具栏麦克风）时不自动开录，等用户点按/长按麦克风。
     */
    fun openPanel(returnToKeyboardOnCommit: Boolean = false) {
        cancelPendingHoldStop()
        autoReturnOnCommit = returnToKeyboardOnCommit
        committedThisSession = false
        _panelVisible.value = true
        panelVisibleListener?.invoke(true)
        VoicePermissionState.refresh(context)
        _state.value = _state.value.copy(isVoiceMode = true)
        session().warmUp()
        if (returnToKeyboardOnCommit && canAutoStart()) startRecognition()
    }

    /** 收起面板（回到键盘）：丢弃未结束的会话，迟到结果不得上屏。 */
    fun closePanel() {
        if (!_panelVisible.value) return
        cancelPendingHoldStop()
        _panelVisible.value = false
        panelVisibleListener?.invoke(false)
        autoReturnOnCommit = false
        committedThisSession = false
        onPanelHidden()
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
                // 会话结束即恢复媒体音量：stopRecognition 路径之外（超时/出错收尾）也能恢复
                unmuteMedia()
                // 空格长按进入：出字后自动收起面板回键盘（未出字，例如识别失败/无语音，则留在面板）
                if (autoReturnOnCommit && committedThisSession) {
                    service.finishComposing()
                    closePanel()
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

    /** 按下麦克风/空格长按进入：开始识别。 */
    fun startRecognition() {
        // 尾音窗口内再次开始：取消待执行的停止，让本次录音继续
        cancelPendingHoldStop()
        if (prefs.voiceMuteDuringRecording.getValue()) muteMedia()
        session().startRecognition()
        _state.value = _state.value.copy(isVoiceMode = true)
    }

    /** 抬起/再次点击麦克风/物理松手：先提交已识别的部分，再停止。 */
    fun stopRecognition() {
        cancelPendingHoldStop()
        val s = session
        if (s == null) {
            unmuteMedia()
            return
        }
        if (!s.isSessionActive()) s.commitPendingOnRelease()
        s.stopRecognition()
        unmuteMedia()
    }

    /**
     * 长按模式（空格长按 / 面板麦克风长按）松手：延时 [HOLD_RELEASE_TAIL_MS] 再停止采集。
     *
     * 采集与识别都继续跑这 300ms，让句尾的字进入引擎后再收尾；重复调用（多次松手通知）
     * 只保留最早的一次，避免尾音窗口被不断顺延。
     */
    fun stopRecognitionWithTail() {
        if (pendingHoldStop != null) return
        val task = Runnable {
            pendingHoldStop = null
            stopRecognition()
        }
        pendingHoldStop = task
        mainHandler.postDelayed(task, HOLD_RELEASE_TAIL_MS)
    }

    /** 取消尚未到期的尾音停止（面板收起/取消会话/重新开录时收尾立即生效）。 */
    private fun cancelPendingHoldStop() {
        pendingHoldStop?.let { mainHandler.removeCallbacks(it) }
        pendingHoldStop = null
    }

    /**
     * 麦克风**点按切换**：按真实会话状态决定开始还是结束。
     *
     */
    fun toggleRecognition() {
        val active = session?.isSessionActive() == true
        Timber.d("voice toggle: active=$active, state=${_state.value.recognitionState}")
        if (active) stopRecognition() else startRecognition()
    }

    /** 取消本次会话（停止识别并丢弃尚未上屏的文本），面板保留。 */
    fun cancelSession() {
        cancelPendingHoldStop()
        val s = session
        s?.cancelPreStart()
        s?.abandonSession()
        service.finishComposing()
        unmuteMedia()
        _state.value = _state.value.copy(
            isVoiceMode = true,
            recognizedText = "",
            recognitionState = VoiceRecognitionState.IDLE,
            error = null,
        )
        _spectrum.value = FloatArray(SPECTRUM_BARS)
    }

    /** 当前引擎是否可用（本地模型已下载 / 在线平台已配置）且已获录音权限。 */
    fun canAutoStart(): Boolean {
        if (!VoicePermissionState.has(context)) return false
        return session().isEngineReady()
    }

    /** 面板隐藏相关收尾（收起面板、输入框切换、IME 退出）。 */
    fun onPanelHidden() {
        cancelPendingHoldStop()
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

    /**
     * 组件销毁（InputView 从窗口摘除）：释放语音会话资源并恢复媒体音量。
     * 之后组件不应再被使用（DynamicScope 已随 detach 清空）。
     */
    fun release() {
        cancelPendingHoldStop()
        panelVisibleListener = null
        session?.release()
        session = null
        unmuteMedia()
        _state.value = VoiceUiState()
        _spectrum.value = FloatArray(SPECTRUM_BARS)
        _panelVisible.value = false
    }

    // ---- 录音时静音媒体音量 ----

    private fun audioManager(): AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private fun muteMedia() {
        val am = audioManager() ?: return
        // 已保存过原始音量就不再重复静音：重复调用会先把当前（已置 0）的音量
        // 存进 savedMediaVolume，恢复时反而把媒体音量设成 0
        if (savedMediaVolume >= 0) return
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
