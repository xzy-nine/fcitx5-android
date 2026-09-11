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
    private val inputView by manager.inputView()

    private val prefs = AppPrefs.getInstance()

    private val clipboardSuggestion = prefs.clipboard.clipboardSuggestion
    private val clipboardItemTimeout = prefs.clipboard.clipboardItemTimeout
    private val clipboardMaskSensitive by prefs.clipboard.clipboardMaskSensitive
    private val expandToolbarByDefault by prefs.keyboard.expandToolbarByDefault
    private val toolbarNumRowOnPassword by prefs.keyboard.toolbarNumRowOnPassword
    private val splitKeyboardPref = prefs.keyboard.splitKeyboard
    private val keyboardPrefs = prefs.keyboard

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var clipboardTimeoutJob: Job? = null

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
     * 图标/文字色仍取 miuix（工具栏是纯 Compose 区，不参与 fcitx 主题的键面配色）。
     */
    @Composable
    private fun getVisuals(): ToolbarVisuals {
        val theme = rememberActiveTheme()
        val keyboardIsLight = Color(theme.keyboardColor).luminance() > 0.5f
        val scrim = if (keyboardIsLight) Color.Black else Color.White
        return ToolbarVisuals(
            // < 10%：只做「与键盘区分」的暗示，不遮挡背后的主题/背景图
            barColor = scrim.copy(alpha = 0.08f),
            iconColor = MiuixTheme.colorScheme.onSurface,
            textColor = MiuixTheme.colorScheme.onSurface,
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
            onCursorMove = {
                windowManager.attachWindow(
                    org.fcitx.fcitx5.android.input.editing.TextEditingWindow()
                )
            },
            onClipboard = {
                windowManager.attachWindow(
                    org.fcitx.fcitx5.android.input.clipboard.ClipboardWindow()
                )
            },
            onSplitKeyboardToggle = {
                splitKeyboardPref.setValue(!splitKeyboardPref.getValue())
            },
            onMore = {
                windowManager.attachWindow(
                    org.fcitx.fcitx5.android.input.status.StatusAreaWindow()
                )
            },
            onTune = {
                if (inputView.isKeyboardTuneShown()) inputView.hideKeyboardTune()
                else inputView.showKeyboardTune()
            },
            onTitleBack = {
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
            splitKeyboardEnabled = splitKeyboardPref.getValue(),
        )
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
    }

    fun destroy() {
        ClipboardManager.removeOnUpdateListener(onClipboardUpdateListener)
        splitKeyboardPref.unregisterOnChangeListener(splitKeyboardListener)
        clipboardTimeoutJob?.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            inlineRenderJob?.cancel()
            inlineSuggestionsUi.clear()
        }
        scope.cancel()
    }

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
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
        barStateMachine.push(PreeditUpdated, PreeditEmpty to empty)
    }

    override fun onCandidateUpdate(data: CandidateListEvent.Data) {
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
                barStateMachine.push(ExtendedWindowAttached)
            }
            else -> {}
        }
    }

    override fun onWindowDetached(window: InputWindow) {
        barStateMachine.push(WindowDetached)
        if (_titleData.value != null) {
            _titleData.value = null
            _titleExtensionView.value = null
        }
    }

    fun onKeyboardLayoutSwitched(isNumber: Boolean) {
        isKeyboardLayoutNumber = isNumber
        evalIdleUiState()
    }

    fun onKeyboardSizeChanged(width: Int, height: Int) {
        keyboardWidth = width
        keyboardHeight = height
    }

    @RequiresApi(android.os.Build.VERSION_CODES.R)
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
        val barState by _barState.collectAsState()
        val idleSubState by _idleSubState.collectAsState()
        val titleData by _titleData.collectAsState()
        val titleExtensionView by _titleExtensionView.collectAsState()
        val expandButtonState by _expandButtonState.collectAsState()
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

        val callbacks = remember { createCallbacks() }
        val visuals = getVisuals()

        ComposeToolbar(
            barState = effectiveBarState,
            idleSubState = idleSubState,
            titleData = titleData,
            callbacks = callbacks,
            visuals = visuals,
            expandButtonState = expandButtonState,
            splitKeyboardEnabled = splitKeyboardEnabled,
            menuRotation = menuRotation,
            modifier = modifier,
            candidateVisible = candidateVisible,
            candidateContent = {
                // 候选栏内容由 ComposeCandidateComponent 提供，直接作为 Composable 接入（去除嵌套 ComposeView）
                composeCandidate.CandidateBarContent()
            },
            numberRowContent = {
                // NumberRow 已全 Compose 化（替代原 View / BaseKeyboard）
                NumberRowContent(
                    onCollapse = callbacks.onNumberRowCollapse,
                    keyActionListener = commonKeyActionListener.listener,
                    popupActionListener = popup.listener,
                )
            },
            inlineSuggestionContent = {
                // InlineSuggestionsUi 包含 SurfaceControl 生命周期管理
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { inlineSuggestionsUi.root },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            clipboardContent = {
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
            },
            titleExtensionContent = if (titleExtensionView != null) {
                {
                    // 扩展 View 用 AndroidView 包装
                    titleExtensionView?.let { extView ->
                        androidx.compose.runtime.key(extView) {
                            androidx.compose.ui.viewinterop.AndroidView(
                                factory = { extView },
                            )
                        }
                    }
                }
            } else null,
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
