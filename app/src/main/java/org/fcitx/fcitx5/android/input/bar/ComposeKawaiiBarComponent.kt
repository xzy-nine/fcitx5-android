/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import android.content.res.Configuration
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputMethodSubtype
import android.widget.FrameLayout
import android.widget.inline.InlineContentView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxEvent.CandidateListEvent
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.State.Hidden
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.BooleanKey.CandidateEmpty
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.BooleanKey.PreeditEmpty
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.TransitionEvent.CandidatesUpdated
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.TransitionEvent.ExtendedWindowAttached
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.TransitionEvent.PreeditUpdated
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.TransitionEvent.WindowDetached
import org.fcitx.fcitx5.android.input.bar.ui.idle.NumberRow
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.candidates.horizontal.ComposeCandidateComponent
import org.fcitx.fcitx5.android.input.dependency.UniqueViewComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.inputView
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.InputMethodUtil
import org.fcitx.fcitx5.android.utils.appContext
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import kotlin.math.min

/**
 * Compose 工具栏组件
 * 替代 KawaiiBarComponent，使用 Compose 实现
 */
class ComposeKawaiiBarComponent :
    UniqueViewComponent<ComposeKawaiiBarComponent, View>(), InputBroadcastReceiver {

    private val context by manager.context()
    private val theme by manager.theme()
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
    private val showVoiceInputButton by prefs.keyboard.showVoiceInputButton
    private val preferredVoiceInput by prefs.keyboard.preferredVoiceInput
    private val splitKeyboardPref = prefs.keyboard.splitKeyboard
    private val keyboardPrefs = prefs.keyboard

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var clipboardTimeoutJob: Job? = null

    private var isClipboardFresh: Boolean = false
    private var isInlineSuggestionPresent: Boolean = false
    private var isCapabilityFlagsPassword: Boolean = false
    private var isKeyboardLayoutNumber: Boolean = false
    private var isToolbarManuallyToggled: Boolean = false

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
    private val _isVoiceInputMode = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val _inlineSuggestions = kotlinx.coroutines.flow.MutableStateFlow<List<InlineSuggestion>>(emptyList())

    private var voiceInputSubtype: Pair<String, InputMethodSubtype>? = null

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

    private fun launchClipboardTimeoutJob() {
        clipboardTimeoutJob?.cancel()
        val timeout = clipboardItemTimeout.getValue() * 1000L
        if (timeout < 0L) return
        clipboardTimeoutJob = scope.launch {
            delay(timeout)
            isClipboardFresh = false
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

    private fun getVisuals(): ToolbarVisuals {
        return ToolbarVisuals(
            barColor = androidx.compose.ui.graphics.Color(theme.barColor),
            iconColor = androidx.compose.ui.graphics.Color(theme.altKeyTextColor),
            pressHighlightColor = androidx.compose.ui.graphics.Color(theme.keyPressHighlightColor),
            textColor = androidx.compose.ui.graphics.Color(theme.altKeyTextColor),
            dividerColor = androidx.compose.ui.graphics.Color(theme.dividerColor),
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
    }

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        isCapabilityFlagsPassword = toolbarNumRowOnPassword && capFlags.has(CapabilityFlag.Password)
        isInlineSuggestionPresent = false
        numberRowState = NumberRowState.Auto
        voiceInputSubtype = InputMethodUtil.findVoiceSubtype(preferredVoiceInput)
        val shouldShowVoiceInput =
            showVoiceInputButton && voiceInputSubtype != null && !capFlags.has(CapabilityFlag.Password)
        _isVoiceInputMode.value = false
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

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.R)
    fun handleInlineSuggestions(response: InlineSuggestionsResponse): Boolean {
        val suggestions = response.inlineSuggestions
        if (suggestions.isEmpty()) {
            isInlineSuggestionPresent = false
            evalIdleUiState()
            _inlineSuggestions.value = emptyList()
            return true
        }
        _inlineSuggestions.value = suggestions
        isInlineSuggestionPresent = true
        evalIdleUiState()
        return true
    }

    override val view by lazy {
        ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val themeController = remember { ThemeController(ColorSchemeMode.System) }
                MiuixTheme(controller = themeController) {
                    val barState by _barState.collectAsState()
                    val idleSubState by _idleSubState.collectAsState()
                    val titleData by _titleData.collectAsState()
                    val titleExtensionView by _titleExtensionView.collectAsState()
                    val expandButtonState by _expandButtonState.collectAsState()
                    val clipboardText by _clipboardText.collectAsState()
                    val inlineSuggestions by _inlineSuggestions.collectAsState()

                    val callbacks = remember { createCallbacks() }
                    val visuals = remember { getVisuals() }

                    ComposeToolbar(
                        barState = barState,
                        idleSubState = idleSubState,
                        titleData = titleData,
                        callbacks = callbacks,
                        visuals = visuals,
                        expandButtonState = expandButtonState,
                        candidateContent = {
                            // 候选栏内容由 ComposeCandidateComponent 提供
                            // 使用 AndroidView 包装其 ComposeView
                            androidx.compose.ui.viewinterop.AndroidView(
                                factory = { composeCandidate.view },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                        numberRowContent = {
                            // NumberRow 是 View (BaseKeyboard)，用 AndroidView 包装
                            NumberRowHost(
                                theme = theme,
                                onCollapse = callbacks.onNumberRowCollapse,
                                keyActionListener = commonKeyActionListener.listener,
                                popupActionListener = popup.listener,
                            )
                        },
                        inlineSuggestionContent = {
                            // InlineSuggestions 需要 AndroidView 包装
                            InlineSuggestionsHost(
                                suggestions = inlineSuggestions,
                            )
                        },
                        clipboardContent = {
                            if (clipboardText.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onClick = callbacks.onClipboardSuggestionClick,
                                        )
                                        .padding(horizontal = 8.dp),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    Text(
                                        text = clipboardText,
                                        color = visuals.textColor,
                                    )
                                }
                            }
                        },
                        titleExtensionContent = if (titleExtensionView != null) {
                            {
                                // 扩展 View 用 AndroidView 包装
                                titleExtensionView?.let { extView ->
                                    androidx.compose.ui.viewinterop.AndroidView(
                                        factory = { extView },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        } else null,
                    )
                }
            }
        }
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

/**
 * NumberRow 的 AndroidView 宿主
 */
@Composable
private fun NumberRowHost(
    theme: org.fcitx.fcitx5.android.data.theme.Theme,
    onCollapse: () -> Unit,
    keyActionListener: org.fcitx.fcitx5.android.input.keyboard.KeyActionListener?,
    popupActionListener: org.fcitx.fcitx5.android.input.popup.PopupActionListener?,
) {
    var numberRowRef by remember { mutableStateOf<NumberRow?>(null) }

    // 监听 listener 变化
    androidx.compose.runtime.DisposableEffect(keyActionListener, popupActionListener) {
        numberRowRef?.let { nr ->
            nr.keyActionListener = keyActionListener
            nr.popupActionListener = popupActionListener
        }
        onDispose {
            numberRowRef?.let { nr ->
                nr.keyActionListener = null
                nr.popupActionListener = null
            }
        }
    }

    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            NumberRow(ctx, theme).also { nr ->
                nr.keyActionListener = keyActionListener
                nr.popupActionListener = popupActionListener
                nr.onCollapseListener = onCollapse
                numberRowRef = nr
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * InlineSuggestions 的 AndroidView 宿主
 */
@Composable
private fun InlineSuggestionsHost(
    suggestions: List<InlineSuggestion>,
) {
    // InlineSuggestions 是系统 API，需要 AndroidView 包装
    // 简单显示提示文本，实际 inflate 逻辑后续完善
    if (suggestions.isNotEmpty()) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "${suggestions.size} suggestions",
                color = androidx.compose.ui.graphics.Color.Gray,
            )
        }
    }
}
