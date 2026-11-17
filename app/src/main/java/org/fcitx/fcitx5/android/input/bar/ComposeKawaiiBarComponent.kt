/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import android.content.res.Configuration
import android.os.Build
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.inline.InlineContentView
import androidx.annotation.RequiresApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxEvent.CandidateListEvent
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.input.bar.ComposeKawaiiBarComponent.Companion.HEIGHT
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.State.Hidden
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.BooleanKey.CandidateEmpty
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.BooleanKey.PreeditEmpty
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.TransitionEvent.CandidatesUpdated
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.TransitionEvent.ExtendedWindowAttached
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.TransitionEvent.PreeditUpdated
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.TransitionEvent.WindowDetached
import org.fcitx.fcitx5.android.input.bar.ui.idle.InlineSuggestionsUi
import org.fcitx.fcitx5.android.input.bar.ui.idle.NumberRowContent
import org.fcitx.fcitx5.android.input.keyboard.rememberActiveTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.candidates.horizontal.CandidateBarState
import org.fcitx.fcitx5.android.input.candidates.horizontal.ComposeCandidateComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.inputView
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.status.StatusAreaWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.appContext
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds

/**
 * Compose 工具栏组件
 * 替代 KawaiiBarComponent，使用 Compose 实现
 */
class ComposeKawaiiBarComponent :
    UniqueComponent<ComposeKawaiiBarComponent>(),
    Dependent,
    ManagedHandler by managedHandler(),
    InputBroadcastReceiver {

    private val context by manager.context()
    private val service by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()
    private val composeCandidate: ComposeCandidateComponent by manager.must()
    private val commonKeyActionListener: CommonKeyActionListener by manager.must()
    private val popup: PopupComponent by manager.must()
    // custom: 内置语音输入入口（工具栏麦克风按钮）
    private val voiceInput: org.fcitx.fcitx5.android.input.voice.VoiceInputComponent by manager.must()
    private val inputView by manager.inputView()

    private val prefs = AppPrefs.getInstance()

    private val toolbarEdit: ToolbarEditComponent by manager.must()

    private val clipboardSuggestion = prefs.clipboard.clipboardSuggestion
    private val clipboardItemTimeout = prefs.clipboard.clipboardItemTimeout
    private val clipboardMaskSensitive by prefs.clipboard.clipboardMaskSensitive
    private val expandToolbarByDefault by prefs.keyboard.expandToolbarByDefault
    private val toolbarNumRowOnPassword by prefs.keyboard.toolbarNumRowOnPassword
    private val splitKeyboardPref = prefs.keyboard.splitKeyboard
    private val keyboardPrefs = prefs.keyboard

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var clipboardTimeoutJob: Job? = null
    private var voicePanelJob: Job? = null

    private var isClipboardFresh: Boolean = false
    private var isInlineSuggestionPresent: Boolean = false
    private var isCapabilityFlagsPassword: Boolean = false
    private var isKeyboardLayoutNumber: Boolean = false
    private var isToolbarManuallyToggled: Boolean = false
    private var keyboardWidth: Int = 0
    private var keyboardHeight: Int = 0

    private enum class NumberRowState { Auto, ForceShow, ForceHide }
    private var numberRowState = NumberRowState.Auto

    // 状态机
    private val barStateMachine = KawaiiBarStateMachine.new { state ->
        _barState.value = state
    }

    val expandButtonStateMachine = ExpandButtonStateMachine.new { state ->
        _expandButtonState.value = state
    }

    // Compose 驱动状态
    private val _barState = kotlinx.coroutines.flow.MutableStateFlow(KawaiiBarStateMachine.State.Idle)
    private val _candidateReceived = kotlinx.coroutines.flow.MutableStateFlow(true)
    val candidateReceived: kotlinx.coroutines.flow.StateFlow<Boolean>
        get() = _candidateReceived

    fun markCandidateReceived() {
        _candidateReceived.value = true
    }
    private val _idleSubState = kotlinx.coroutines.flow.MutableStateFlow(IdleSubState.Empty)
    private val _titleData = kotlinx.coroutines.flow.MutableStateFlow<TitleData?>(null)
    private val _titleExtensionView = kotlinx.coroutines.flow.MutableStateFlow<View?>(null)
    private val _expandButtonState = kotlinx.coroutines.flow.MutableStateFlow<ExpandButtonStateMachine.State>(Hidden)
    private val _clipboardText = kotlinx.coroutines.flow.MutableStateFlow("")
    private val _splitKeyboardEnabled = kotlinx.coroutines.flow.MutableStateFlow(splitKeyboardPref.getValue())
    private val _menuRotation = kotlinx.coroutines.flow.MutableStateFlow(270f)

    private val _toolbarHeightVersion = kotlinx.coroutines.flow.MutableStateFlow(0)

    /**
     * 工具栏高度偏好变更计数。
     * 工具栏高度由 Composable 内部的 [HEIGHT] 决定，偏好变化后必须自增它并让父级重组才能生效。
     */
    val toolbarHeightVersion: kotlinx.coroutines.flow.StateFlow<Int>
        get() = _toolbarHeightVersion

    fun notifyToolbarHeightChanged() {
        _toolbarHeightVersion.value++
    }

    /**
     * custom: 按钮布局（[ToolbarLayoutState]）。
     *
     * docked 部分驱动工具栏可停靠行，pool 部分驱动更多页按钮池。编辑中读草稿，
     * 否则读已保存值 —— 两个渲染区因此始终看到同一份结果（见 [ToolbarEditComponent]）。
     */
    val toolbarLayout: kotlinx.coroutines.flow.StateFlow<ToolbarLayoutState>
        get() = toolbarEdit.draftOrCommitted

    /** custom: 撤销/重做两个固定槽位是否显示。 */
    val showUndoRedo: kotlinx.coroutines.flow.StateFlow<Boolean>
        get() = toolbarEdit.showUndoRedo

    /** custom: 更多页是否打开中（顶栏据此换成「返回 + 可停靠行 + 编辑」）。 */
    private val _morePageVisible = kotlinx.coroutines.flow.MutableStateFlow(false)
    val morePageVisible: kotlinx.coroutines.flow.StateFlow<Boolean>
        get() = _morePageVisible

    /**
     * custom: 语音面板是否打开中。
     *
     * 与 [morePageVisible] 同规：面板打开时顶栏保留可停靠行、左侧放返回，
     * 而不是退化成「返回 + 标题」把整行按钮藏起来。
     */
    private val _voicePageVisible = kotlinx.coroutines.flow.MutableStateFlow(false)
    val voicePageVisible: kotlinx.coroutines.flow.StateFlow<Boolean>
        get() = _voicePageVisible

    /**
     * custom: 按钮可用性版本号。
     *
     * 可用性依赖键盘几何（分体键盘的宽高比）与三项偏好，几何量本身不是 Compose 状态，
     * 因此统一用这个计数器驱动重组：任一来源变化即自增，组合期重新算一遍可用性。
     */
    private val _buttonUsabilityVersion = kotlinx.coroutines.flow.MutableStateFlow(0)
    val buttonUsabilityVersion: kotlinx.coroutines.flow.StateFlow<Int>
        get() = _buttonUsabilityVersion

    private fun notifyButtonUsabilityChanged() {
        _buttonUsabilityVersion.value++
    }

    /** custom: 布局是否正在编辑中（顶栏据此把右侧按钮换成「完成」）。 */
    val editing: kotlinx.coroutines.flow.StateFlow<Boolean>
        get() = toolbarEdit.editing

    private var cachedHeightKey: Pair<Int, Int>? = null
    private var cachedHeightValue = 0

    /**
     * 工具栏高度（dp 数值）。
     *
     * 组合期工具栏子树会读它十余次，若每次都走 `AppPrefs` + `SharedPreferences` 会重复付出
     * 同步读取与配置查询的开销；这里按「偏好版本 + 屏幕方向」缓存，任一变化即重算。
     * 版本变化本身已驱动父级重组（[toolbarHeightVersion] 被 InputView 收集），
     * 方向变化则会重建 IME 视图树，故不会读到过期值。
     */
    val toolbarHeight: Int
        get() {
            val orientation = context.resources.configuration.orientation
            val key = _toolbarHeightVersion.value to orientation
            if (key != cachedHeightKey) {
                cachedHeightKey = key
                cachedHeightValue = if (orientation == Configuration.ORIENTATION_LANDSCAPE)
                    keyboardPrefs.toolbarHeightLandscape.getValue()
                else
                    keyboardPrefs.toolbarHeight.getValue()
            }
            return cachedHeightValue
        }

    // InlineSuggestions 视图容器
    private val inlineSuggestionsUi by lazy { InlineSuggestionsUi(context) }
    private var inlineRenderJob: Job? = null
    private var inlineRenderGeneration = 0

    private val suggestionSize by lazy {
        Size(ViewGroup.LayoutParams.WRAP_CONTENT, context.dp(HEIGHT))
    }

    private val directExecutor by lazy {
        Executor { it.run() }
    }

    // Clipboard 监听
    private val onClipboardUpdateListener =
        ClipboardManager.OnClipboardUpdateListener {
            if (!clipboardSuggestion.getValue()) return@OnClipboardUpdateListener
            scope.launch {
                if (it.text.isEmpty()) {
                    isClipboardFresh = false
                } else {
                    _clipboardText.value = if (it.sensitive && clipboardMaskSensitive) {
                        ClipboardEntry.BULLET.repeat(min(42, it.text.length))
                    } else {
                        it.text.take(42)
                    }
                    isClipboardFresh = true
                    launchClipboardTimeoutJob()
                }
                evalIdleUiState()
            }
        }

    // 分割键盘偏好监听
    private val splitKeyboardListener =
        ManagedPreference.OnChangeListener<Boolean> { _, enabled ->
            _splitKeyboardEnabled.value = enabled
        }

    /**
     * custom: 按钮可用性来源的偏好监听。
     *
     * 语音/手写是否启用、分体键盘阈值都会改变置灰状态；它们与控制显示的偏好是两回事
     * （例如手写偏好关掉后按钮仍在原位、只是变灰），故单独监听并只自增可用性版本号。
     * [ManagedPreference] 的监听器带类型参数，故三项各持一个实例。
     */
    private val splitThresholdUsabilityListener =
        ManagedPreference.OnChangeListener<Float> { _, _ -> notifyButtonUsabilityChanged() }
    private val voiceUsabilityListener =
        ManagedPreference.OnChangeListener<Boolean> { _, _ -> notifyButtonUsabilityChanged() }
    private val handwritingUsabilityListener =
        ManagedPreference.OnChangeListener<Boolean> { _, _ -> notifyButtonUsabilityChanged() }

    private val splitKeyboardThresholdPref = keyboardPrefs.splitKeyboardThreshold
    private val voiceInputEnabledPref = prefs.voice.voiceInputEnabled
    private val handwritingEnabledPref = prefs.handwriting.handwritingInputEnabled

    private fun launchClipboardTimeoutJob() {
        clipboardTimeoutJob?.cancel()
        val timeout = clipboardItemTimeout.getValue() * 1000L
        if (timeout < 0L) return
        clipboardTimeoutJob = scope.launch {
            delay(timeout.milliseconds)
            isClipboardFresh = false
            evalIdleUiState()
            clipboardTimeoutJob = null
        }
    }

    private fun evalIdleUiState(fromUser: Boolean = false) {
        val newState = when {
            numberRowState == NumberRowState.ForceShow -> IdleSubState.NumberRow
            isClipboardFresh -> IdleSubState.Clipboard
            isInlineSuggestionPresent -> IdleSubState.InlineSuggestion
            isCapabilityFlagsPassword && !isKeyboardLayoutNumber && numberRowState != NumberRowState.ForceHide -> IdleSubState.NumberRow
            expandToolbarByDefault == isToolbarManuallyToggled -> IdleSubState.Empty
            else -> IdleSubState.Toolbar
        }
        if (newState == _idleSubState.value) return
        _idleSubState.value = newState
    }

    /**
     * 工具栏视觉配置。
     *
     * **背景是刻意的「反差薄层」**：工具栏与键盘背景要分开，做法是在键盘底色上叠一层
     * **反色**的极低透明度 scrim（键盘深 → 叠白、键盘浅 → 叠黑，`alpha < 10%`），
     * 再配圆角（见 `ComposeToolbar`）把它和键盘区在视觉上切开 —— 起分隔作用的是**下**两角
     * （工具栏与键盘区的分界；IME 的下缘由屏幕自身圆角代劳，不在此列）。
     *
     * 深浅判断用**键盘背景色本身的明度**（`theme.keyboardColor`），而不是主题自报的
     * `isDark` —— 用户名下的自定义主题常常与 `isDark` 不一致。背景图主题下该色不代表实际
     * 画面明度，此时仍以此色为准（后续若要更准，可对背景图取平均亮度）。
     *
     * 图标/文字色同样随键盘明度取黑/白（工具栏是纯 Compose 区，不参与 fcitx 主题的键面配色；
     * 不恒用 miuix onSurface —— miuix 主题明度与键盘主题可能不一致，会造成前景与底色反差不足）。
     */
    @Composable
    private fun getVisuals(): ToolbarVisuals {
        val theme = rememberActiveTheme()
        val keyboardIsLight = Color(theme.keyboardColor).luminance() > 0.5f
        val scrim = if (keyboardIsLight) Color.Black else Color.White
        // 前景色随键盘明度取黑/白，确保与「键盘底色 + scrim」叠加后的工具栏底色对比足够
        val foreground = if (keyboardIsLight) Color.Black else Color.White
        return ToolbarVisuals(
            // < 10%：只做「与键盘区分」的暗示，不遮挡背后的主题/背景图
            barColor = scrim.copy(alpha = 0.08f),
            iconColor = foreground,
            textColor = foreground,
        )
    }

    private fun createCallbacks(): ToolbarCallbacks {
        return ToolbarCallbacks(
            onMenuClick = {
                when (_idleSubState.value) {
                    IdleSubState.Empty -> {
                        isToolbarManuallyToggled = !expandToolbarByDefault
                        evalIdleUiState(fromUser = true)
                    }
                    IdleSubState.Toolbar -> {
                        isToolbarManuallyToggled = expandToolbarByDefault
                        evalIdleUiState(fromUser = true)
                    }
                    else -> {
                        isToolbarManuallyToggled = !expandToolbarByDefault
                        _idleSubState.value = IdleSubState.Toolbar
                    }
                }
                // 更新菜单按钮旋转角度（收起时90°指向左，展开时270°指向右）
                _menuRotation.value = if (_idleSubState.value == IdleSubState.Toolbar) 270f else 90f
                if (clipboardTimeoutJob != null) {
                    launchClipboardTimeoutJob()
                }
            },
            onHideKeyboard = {
                service.requestHideSelf(0)
            },
            onUndo = {
                service.sendCombinationKeyEvents(android.view.KeyEvent.KEYCODE_Z, ctrl = true)
            },
            onRedo = {
                service.sendCombinationKeyEvents(
                    android.view.KeyEvent.KEYCODE_Z,
                    ctrl = true,
                    shift = true
                )
            },
            onTitleBack = {
                // custom: 语音/手写面板是覆盖层，不能只切窗口 —— 不然面板会盖在新键盘上
                voiceInput.closePanel()
                windowManager.attachWindow(
                    org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
                )
            },
            onClipboardSuggestionClick = {
                ClipboardManager.lastEntry?.let {
                    service.commitText(it.text)
                }
                clipboardTimeoutJob?.cancel()
                clipboardTimeoutJob = null
                isClipboardFresh = false
                evalIdleUiState()
            },
            onClipboardSuggestionLongClick = {
                ClipboardManager.lastEntry?.let {
                    windowManager.attachWindow(
                        org.fcitx.fcitx5.android.input.clipboard.ClipboardEditWindow(it.id, true)
                    )
                }
            },
            onNumberRowCollapse = {
                numberRowState = NumberRowState.ForceHide
                evalIdleUiState(fromUser = true)
            },
            onNumberRowShow = {
                numberRowState = NumberRowState.ForceShow
                evalIdleUiState(fromUser = true)
            },
            onMore = {
                windowManager.attachWindow(StatusAreaWindow())
            },
            // 可用性在每次取用时读偏好：偏好变化不重建 callbacks，读死值会让置灰状态停留在旧值
            buttonUsable = ::isButtonUsable,
            onButtonClick = ::onToolbarButtonClick,
            onEditToggle = {
                if (toolbarEdit.editing.value) toolbarEdit.commit() else toolbarEdit.beginEdit()
            },
            onBackToKeyboard = {
                // custom: 语音/手写面板是覆盖层，不能只切窗口 —— 不然面板会盖在新键盘上
                voiceInput.closePanel()
                windowManager.attachWindow(
                    org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
                )
            },
        )
    }

    /**
     * 可停靠按钮当前是否可用（不可用时置灰但仍占位）。
     *
     * 语音/手写看各自的启用偏好；分体键盘还要满足「非数字键盘且宽高比允许」，
     * 与更多页按钮池共用同一判定，避免两处表现不一致。
     */
    /** 可停靠按钮当前是否可用（更多页按钮池与本页共用同一判定，避免两处置灰状态不一致）。 */
    fun isButtonUsable(button: ToolbarButton): Boolean = when (button) {        ToolbarButton.Voice -> prefs.voice.voiceInputEnabled.getValue()
        ToolbarButton.Handwriting -> prefs.handwriting.handwritingInputEnabled.getValue()
        ToolbarButton.SplitKeyboard -> isSplitKeyboardAvailable()
        else -> true
    }

    /** 分体键盘入口是否可用：数字键盘下不可用；宽高比不超过阈值时也不可用。 */
    private fun isSplitKeyboardAvailable(): Boolean {
        if (isKeyboardLayoutNumber) return false
        if (keyboardHeight <= 0) return true
        val ratio = keyboardWidth.toFloat() / keyboardHeight
        return ratio > keyboardPrefs.splitKeyboardThreshold.getValue()
    }

    /** 可移动按钮的行为（工具栏行与更多页按钮池共用）。 */
    fun onToolbarButtonClick(button: ToolbarButton) {
        when (button) {
            ToolbarButton.TextEditing -> windowManager.attachWindow(
                org.fcitx.fcitx5.android.input.editing.TextEditingWindow()
            )
            ToolbarButton.Clipboard -> windowManager.attachWindow(
                org.fcitx.fcitx5.android.input.clipboard.ClipboardWindow()
            )
            ToolbarButton.Tune -> {
                if (inputView.isKeyboardTuneShown()) inputView.hideKeyboardTune()
                else inputView.showKeyboardTune()
            }
            ToolbarButton.Voice ->
                // 工具栏麦克风进入：留在语音页，方便连续说话
                voiceInput.onVoiceEntryClicked(returnToKeyboardOnCommit = false)
            ToolbarButton.Handwriting -> {
                // 手写是第三种键盘布局：先确保键盘窗口在前台，再切布局
                windowManager.attachWindow(
                    org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
                )
                inputView.keyboardWindow.switchLayout(
                    org.fcitx.fcitx5.android.input.keyboard.KeyboardLayoutNames.Handwriting
                )
            }
            ToolbarButton.SplitKeyboard ->
                splitKeyboardPref.setValue(!splitKeyboardPref.getValue())
        }
    }

    // InputBroadcastReceiver 实现

    override fun onScopeSetupFinished(scope: DynamicScope) {
        ClipboardManager.lastEntry?.let {
            val now = System.currentTimeMillis()
            val clipboardTimeout = clipboardItemTimeout.getValue() * 1000L
            if (now - it.timestamp < clipboardTimeout) {
                onClipboardUpdateListener.onUpdate(it)
            }
        }
        ClipboardManager.addOnUpdateListener(onClipboardUpdateListener)
        splitKeyboardPref.registerOnChangeListener(splitKeyboardListener)
        // custom: 布局偏好被外部改写（恢复备份/同步）时让编辑会话重新读取
        toolbarEdit.registerPreferenceListeners()
        splitKeyboardThresholdPref.registerOnChangeListener(splitThresholdUsabilityListener)
        voiceInputEnabledPref.registerOnChangeListener(voiceUsabilityListener)
        handwritingEnabledPref.registerOnChangeListener(handwritingUsabilityListener)
        observeVoicePanel()
    }

    /**
     * custom: 语音面板是**覆盖层**而不是 [InputWindow]，因此不会触发
     * [onWindowAttached]，工具栏也就拿不到「标题 + 返回」态（面板打开时看不到返回按钮）。
     * 这里直接把面板可见性映射成同一套状态机的转移，返回按钮的语义仍由工具栏统一提供。
     */
    private fun observeVoicePanel() {
        voicePanelJob = scope.launch {
            voiceInput.panelVisible.collect { visible ->
                _voicePageVisible.value = visible
                if (visible) {
                    _titleData.value = TitleData(
                        title = context.getString(R.string.voice_input),
                        showTitle = true
                    )
                    _titleExtensionView.value = null
                    barStateMachine.push(ExtendedWindowAttached)
                } else {
                    _titleData.value = null
                    _titleExtensionView.value = null
                    barStateMachine.push(WindowDetached)
                }
            }
        }
    }

    fun destroy() {
        ClipboardManager.removeOnUpdateListener(onClipboardUpdateListener)
        splitKeyboardPref.unregisterOnChangeListener(splitKeyboardListener)
        toolbarEdit.unregisterPreferenceListeners()
        splitKeyboardThresholdPref.unregisterOnChangeListener(splitThresholdUsabilityListener)
        voiceInputEnabledPref.unregisterOnChangeListener(voiceUsabilityListener)
        handwritingEnabledPref.unregisterOnChangeListener(handwritingUsabilityListener)
        clipboardTimeoutJob?.cancel()
        voicePanelJob?.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            inlineRenderJob?.cancel()
            inlineSuggestionsUi.clear()
        }
        scope.cancel()
    }

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags, restarting: Boolean) {
        isCapabilityFlagsPassword = toolbarNumRowOnPassword && capFlags.has(CapabilityFlag.Password)
        isInlineSuggestionPresent = false
        numberRowState = NumberRowState.Auto
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            inlineRenderJob?.cancel()
            inlineSuggestionsUi.clear()
        }
        evalIdleUiState()
    }

    override fun onPreeditEmptyStateUpdate(empty: Boolean) {
        // 预编辑变非空 → 候选事件待到达；预编辑变空 → 无待到达的候选事件
        _candidateReceived.value = empty
        barStateMachine.push(PreeditUpdated, PreeditEmpty to empty)
    }

    override fun onCandidateUpdate(data: CandidateListEvent.Data) {
        _candidateReceived.value = true
        barStateMachine.push(CandidatesUpdated, CandidateEmpty to data.candidates.isEmpty())
    }

    override fun onWindowAttached(window: InputWindow) {
        when (window) {
            is InputWindow.ExtendedInputWindow<*> -> {
                _titleData.value = TitleData(
                    title = window.title,
                    showTitle = window.showTitle
                )
                _titleExtensionView.value = window.onCreateBarExtension()
                _morePageVisible.value = window is StatusAreaWindow
                barStateMachine.push(ExtendedWindowAttached)
            }
            else -> {}
        }
    }

    override fun onWindowDetached(window: InputWindow) {
        // 离开更多页即结束编辑会话：草稿丢弃，避免「没点完成就离开」留下半成品布局
        if (_morePageVisible.value) {
            _morePageVisible.value = false
            toolbarEdit.cancel()
        }
        barStateMachine.push(WindowDetached)
        if (_titleData.value != null) {
            _titleData.value = null
            _titleExtensionView.value = null
        }
    }

    fun onKeyboardLayoutSwitched(isNumber: Boolean) {
        isKeyboardLayoutNumber = isNumber
        notifyButtonUsabilityChanged()
        evalIdleUiState()
    }

    fun onKeyboardSizeChanged(width: Int, height: Int) {
        // 键盘尺寸决定分体键盘入口是否可用（宽高比阈值），可用性要跟着重新计算
        val changed = keyboardWidth != width || keyboardHeight != height
        keyboardWidth = width
        keyboardHeight = height
        if (changed) notifyButtonUsabilityChanged()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun handleInlineSuggestions(response: InlineSuggestionsResponse): Boolean {
        val suggestions = response.inlineSuggestions
        if (suggestions.isEmpty()) {
            isInlineSuggestionPresent = false
            evalIdleUiState()
            inlineRenderJob?.cancel()
            inlineSuggestionsUi.clear()
            return true
        }
        var pinned: InlineSuggestion? = null
        val scrollable = mutableListOf<InlineSuggestion>()
        var extraPinnedCount = 0
        suggestions.forEach {
            if (it.info.isPinned) {
                if (pinned == null) {
                    pinned = it
                } else {
                    scrollable.add(extraPinnedCount++, it)
                }
            } else {
                scrollable.add(it)
            }
        }
        inlineRenderJob?.cancel()
        val gen = ++inlineRenderGeneration
        inlineSuggestionsUi.clear()
        val pinnedJob = scope.launch {
            val view = pinned?.let { inflateInlineContentView(it) }
            if (inlineRenderGeneration == gen) {
                inlineSuggestionsUi.setPinnedView(view)
            }
        }
        val scrollableJob = scope.launch {
            val views = scrollable.map { s ->
                async {
                    inflateInlineContentView(s)
                }
            }.awaitAll()
            if (inlineRenderGeneration == gen) {
                inlineSuggestionsUi.setScrollableViews(views)
            }
        }
        inlineRenderJob = pinnedJob  // 追踪主 Job 即可
        isInlineSuggestionPresent = true
        evalIdleUiState()
        return true
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun inflateInlineContentView(suggestion: InlineSuggestion): InlineContentView? {
        return suspendCancellableCoroutine { c ->
            suggestion.inflate(context, suggestionSize, directExecutor) { v ->
                c.resume(v)
            }
        }
    }

    /**
     * 工具栏 Composable 内容。
     * 不再持有独立 ComposeView，由父级（合并后的单一 Composition）在 MiuixTheme 内直接调用。
     */
    @Composable
    fun ToolbarContent(modifier: Modifier = Modifier) {
        // 冷启动阶段计时：工具栏/候选栏内容完成首次组合
        androidx.compose.runtime.SideEffect {
            org.fcitx.fcitx5.android.input.ColdStartTrace.markOnce("toolbarComposed")
            org.fcitx.fcitx5.android.input.ColdStartTrace.pass("toolbar")
        }
        val barState by _barState.collectAsState()
        val idleSubState by _idleSubState.collectAsState()
        val titleData by _titleData.collectAsState()
        val titleExtensionView by _titleExtensionView.collectAsState()
        val clipboardText by _clipboardText.collectAsState()
        val splitKeyboardEnabled by _splitKeyboardEnabled.collectAsState()
        val menuRotation by _menuRotation.collectAsState()

        // 候选栏可见性由「候选内容就绪」驱动（composeCandidate.barState 是唯一事实源）：
        // barState 与候选内容是两个独立 StateFlow 异步收集，若仅按 barState 显示候选栏，
        // 可能在候选内容尚未到达的帧显示空行 → 闪烁。内容未就绪时保持 Idle 展示。
        val candidateState by composeCandidate.barState.collectAsState()
        val candidateActive = candidateState is CandidateBarState.Active
        val effectiveBarState =
            if (barState == KawaiiBarStateMachine.State.Candidate && !candidateActive) {
                KawaiiBarStateMachine.State.Idle
            } else barState
        // 候选栏仅当「内容就绪且处于候选态」时可见（Title 态不显示，避免透出候选词与标题重叠）
        val candidateVisible = effectiveBarState == KawaiiBarStateMachine.State.Candidate

        val layout by toolbarLayout.collectAsState()
        val showUndoRedo by showUndoRedo.collectAsState()
        val morePage by morePageVisible.collectAsState()
        val voicePage by voicePageVisible.collectAsState()
        val isEditing by editing.collectAsState()
        // 可用性依赖非 Compose 的键盘几何，靠版本号驱动重算（见 notifyButtonUsabilityChanged）
        buttonUsabilityVersion.collectAsState()

        // callbacks 依赖上面的状态（更多页/语音页/编辑中会改变顶栏行为），故以它们为 key 重建；
        // 这些状态变化极低频（进出覆盖层页面、切换编辑模式），重建开销可忽略。
        val callbacks = remember(morePage, voicePage, isEditing, showUndoRedo) {
            createCallbacks().copy(
                morePageVisible = morePage,
                voicePageVisible = voicePage,
                editing = isEditing,
                showUndoRedo = showUndoRedo,
            )
        }
        val visuals = getVisuals()

        // 稳定化 slot lambda：remember 后 ComposeToolbar 的对应参数实例不变，
        // 候选更新等只改 barState/candidateVisible 时，这些 slot 的内部组合可被复用、不再整片重组。
        // （NumberRow 含整排按键，候选每次更新都连带重组它是主要浪费源。）
        val candidateContent: @Composable () -> Unit = remember {
            { composeCandidate.CandidateBarContent() }
        }
        val numberRowContent: @Composable () -> Unit = remember {
            {
                NumberRowContent(
                    onCollapse = callbacks.onNumberRowCollapse,
                    keyActionListener = commonKeyActionListener.listener,
                    popupActionListener = popup.listener,
                )
            }
        }
        val inlineSuggestionContent: @Composable () -> Unit = remember {
            {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { inlineSuggestionsUi.root },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        // clipboardContent 读 visuals（主题色）与 clipboardText（State delegate）：
        // 以 visuals 为 key——主题切换时重建；clipboardText 在 lambda 内经 delegate 读取、自带追踪。
        val clipboardContent: @Composable () -> Unit = remember(visuals) {
            {
                if (clipboardText.isNotEmpty()) {
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .inputFeedback()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = callbacks.onClipboardSuggestionClick,
                            )
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_clipboard),
                            contentDescription = null,
                            tint = visuals.iconColor,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = clipboardText,
                            color = visuals.textColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        val titleExtensionContent: (@Composable () -> Unit)? = if (titleExtensionView != null) {
            remember {
                {
                    titleExtensionView?.let { extView ->
                        androidx.compose.runtime.key(extView) {
                            androidx.compose.ui.viewinterop.AndroidView(
                                factory = { extView },
                            )
                        }
                    }
                }
            }
        } else null

        ComposeToolbar(
            barState = effectiveBarState,
            idleSubState = idleSubState,
            titleData = titleData,
            callbacks = callbacks,
            visuals = visuals,
            splitKeyboardEnabled = splitKeyboardEnabled,
            menuRotation = menuRotation,
            modifier = modifier,
            toolbarHeight = toolbarHeight.dp,
            dockedButtons = layout.docked,
            poolButtons = layout.pool,
            // 非编辑态不注入拖动状态：避免普通点击/长按误触改动布局
            drag = if (isEditing) toolbarEdit.drag else null,
            onDropButton = { button, target -> toolbarEdit.applyDrop(button, target) },
            candidateVisible = candidateVisible,
            candidateContent = candidateContent,
            numberRowContent = numberRowContent,
            inlineSuggestionContent = inlineSuggestionContent,
            clipboardContent = clipboardContent,
            titleExtensionContent = titleExtensionContent,
        )
    }

    companion object {
        val HEIGHT: Int
            get() {
                val prefs = AppPrefs.getInstance().keyboard
                val context = appContext
                return when (context.resources.configuration.orientation) {
                    Configuration.ORIENTATION_LANDSCAPE -> prefs.toolbarHeightLandscape.getValue()
                    else -> prefs.toolbarHeight.getValue()
                }
            }
    }
}
