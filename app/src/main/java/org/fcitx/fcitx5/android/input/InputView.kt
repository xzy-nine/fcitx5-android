/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.ImageView
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.updateLayoutParams
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import org.fcitx.fcitx5.android.core.CapabilityFlags
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.ComposeKawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcaster
import org.fcitx.fcitx5.android.input.broadcast.PreeditEmptyStateComponent
import org.fcitx.fcitx5.android.input.broadcast.PunctuationComponent
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.candidates.ComposeCandidateActionMenu
import org.fcitx.fcitx5.android.input.candidates.horizontal.ComposeCandidateComponent
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyboardHeightPercentBase.DisplayMetrics
import org.fcitx.fcitx5.android.input.keyboard.KeyboardHeightPercentBase.RealSize
import org.fcitx.fcitx5.android.input.keyboard.KeyboardTuneOverlay
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.picker.emojiPicker
import org.fcitx.fcitx5.android.input.picker.emoticonPicker
import org.fcitx.fcitx5.android.input.picker.symbolPicker
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.preedit.ComposePreeditComponent
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.unset
import org.fcitx.fcitx5.android.utils.windowManager
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.manager.wrapToUniqueComponent
import org.mechdancer.dependency.plusAssign
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.endToStartOf
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.startToEndOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable
import timber.log.Timber

@SuppressLint("ViewConstructor")
class InputView(
    service: FcitxInputMethodService,
    fcitx: FcitxConnection,
    theme: Theme
) : BaseInputView(service, fcitx, theme) {

    private val keyBorder by ThemeManager.prefs.keyBorder

    private val customBackground = imageView {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }

    private val placeholderOnClickListener = OnClickListener { }

    // use clickable view as padding, so MotionEvent can be split to padding view and keyboard view
    private val leftPaddingSpace = view(::View) {
        setOnClickListener(placeholderOnClickListener)
    }
    private val rightPaddingSpace = view(::View) {
        setOnClickListener(placeholderOnClickListener)
    }
    private val bottomPaddingSpace = view(::View) {
        // height as keyboardBottomPadding
        // bottomMargin as WindowInsets (Navigation Bar) offset
        setOnClickListener(placeholderOnClickListener)
    }

    private val scope = DynamicScope()
    private val broadcaster = InputBroadcaster()
    // 按键弹窗层：根组合（createComposeInputView）经 PopupOverlayContent 渲染，故需对外可见
    internal val popup = PopupComponent()
    private val punctuation = PunctuationComponent()
    private val returnKeyDrawable = ReturnKeyDrawableComponent()
    private val preeditEmptyState = PreeditEmptyStateComponent()
    // 预编辑栏：根组合/Service 经 heightPx 读取当前高度做 insets 补偿，故需对外可见
    internal val composePreedit = ComposePreeditComponent()
    private val commonKeyActionListener = CommonKeyActionListener()
    private val windowManager = InputWindowManager()
    private val composeKawaiiBar = ComposeKawaiiBarComponent()
    // Compose 实现的候选栏组件
    // 旧 View 实现：HorizontalCandidateComponent（已断开接线，保留供对比）
    private val composeCandidate = ComposeCandidateComponent()
    // 候选操作菜单覆盖层（Compose 调用方长按候选词时在 IME 内弹出的悬浮菜单）：
    // 根组合（createComposeInputView）经 OverlayContent 渲染，故需对外可见
    internal val candidateActionMenu = ComposeCandidateActionMenu()
    private val keyboardWindow = KeyboardWindow()
    private val symbolPicker = symbolPicker()
    private val emojiPicker = emojiPicker()
    private val emoticonPicker = emoticonPicker()

    /**
     * 工具栏 Compose 容器：预编辑栏与工具栏合并后的单一 Composition。
     * 预编辑栏高度**贴合内容**（空态 0），`onComputeInsets` 补偿 `composePreedit.heightPx`
     * 实际高度 —— keyboardView 顶部已含预编辑高度，补偿后正好抵消，故 insets 恒定不随打字变化。
     */
    private val composeTopView: ComposeView by lazy {
        ComposeView(themedContext).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                MiuixTheme(controller = remember { ThemeController(ColorSchemeMode.System) }) {
                    // 预编辑栏高度变化时同步键盘背景裁剪：跳过预编辑行、圆角落在工具栏顶部
                    // 用 LaunchedEffect 而非 SideEffect：后者在每次成功重组后都会执行，
                    // 而裁剪参数只在高度变化时才需要重新下发（避免反复重建 OutlineProvider）
                    val preeditHeightPx = composePreedit.heightPx.collectAsState().value
                    androidx.compose.runtime.LaunchedEffect(preeditHeightPx) {
                        customBackground.applyTopRoundedCornerClip(
                            dp(16).toFloat(),
                            preeditHeightPx.toFloat()
                        )
                    }
                    Column {
                        // 预编辑栏在上（贴合内容高度），工具栏在下
                        composePreedit.PreeditContent()
                        // 工具栏高度由 Composable 内部的 HEIGHT 决定，偏好变化后用 key 触发重组
                        key(composeKawaiiBar.toolbarHeightVersion.collectAsState().value) {
                            composeKawaiiBar.ToolbarContent()
                        }
                    }
                }
            }
        }
    }

    private fun setupScope() {
        scope += this@InputView.wrapToUniqueComponent()
        scope += service.wrapToUniqueComponent()
        scope += fcitx.wrapToUniqueComponent()
        scope += theme.wrapToUniqueComponent()
        scope += themedContext.wrapToUniqueComponent()
        scope += broadcaster
        scope += popup
        scope += punctuation
        scope += returnKeyDrawable
        scope += preeditEmptyState
        scope += composePreedit
        scope += commonKeyActionListener
        scope += windowManager
        // 旧 View 实现：scope += kawaiiBar（已断开接线，保留供对比）
        scope += composeKawaiiBar
        // 旧 View 实现：scope += horizontalCandidate（已断开接线）
        scope += composeCandidate
        scope += candidateActionMenu
        broadcaster.onScopeSetupFinished(scope)
    }

    private val keyboardPrefs = AppPrefs.getInstance().keyboard

    private val focusChangeResetKeyboard by keyboardPrefs.focusChangeResetKeyboard

    private val keyboardHeightPercent = keyboardPrefs.keyboardHeightPercent
    private val keyboardHeightPercentLandscape = keyboardPrefs.keyboardHeightPercentLandscape
    private val toolbarHeight = keyboardPrefs.toolbarHeight
    private val toolbarHeightLandscape = keyboardPrefs.toolbarHeightLandscape
    private val keyboardSidePadding = keyboardPrefs.keyboardSidePadding
    private val keyboardSidePaddingLandscape = keyboardPrefs.keyboardSidePaddingLandscape
    private val keyboardBottomPadding = keyboardPrefs.keyboardBottomPadding
    private val keyboardBottomPaddingLandscape = keyboardPrefs.keyboardBottomPaddingLandscape

    private val advancedPrefs = AppPrefs.getInstance().advanced
    private val keyboardHeightPercentBase = advancedPrefs.keyboardHeightPercentBase

    private val keyboardSizePrefs = listOf(
        keyboardHeightPercent,
        keyboardHeightPercentLandscape,
        toolbarHeight,
        toolbarHeightLandscape,
        keyboardSidePadding,
        keyboardSidePaddingLandscape,
        keyboardBottomPadding,
        keyboardBottomPaddingLandscape,
        keyboardHeightPercentBase,
    )

    private val keyboardTuneOverlay by lazy {
        KeyboardTuneOverlay(
            context,
            theme,
            keyboardPrefs,
            onDismiss = { setKeyboardTuneBlur(false) }
        ) { keyboardTuneMetrics() }
    }

    private val keyboardHeightPx: Int
        get() {
            val baseType = keyboardHeightPercentBase.getValue()
            val base = when (baseType) {
                DisplayMetrics -> resources.displayMetrics.heightPixels
                RealSize -> Point().also {
                    @Suppress("DEPRECATION")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        context.display
                    } else {
                        context.windowManager.defaultDisplay
                    }.getRealSize(it)
                }.y
            }
            val percent = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardHeightPercentLandscape
                else -> keyboardHeightPercent
            }.getValue()
            Timber.d("keyboardHeightPx get(): baseType=${baseType}, base=${base}, percent=${percent}")
            return base * percent / 100
        }

    private val toolbarHeightPx: Int
        get() {
            val value = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> toolbarHeightLandscape
                else -> toolbarHeight
            }.getValue()
            return dp(value)
        }

    private val keyboardSidePaddingPx: Int
        get() {
            val value = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardSidePaddingLandscape
                else -> keyboardSidePadding
            }.getValue()
            return dp(value)
        }

    private val keyboardBottomPaddingPx: Int
        get() {
            val value = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardBottomPaddingLandscape
                else -> keyboardBottomPadding
            }.getValue()
            return dp(value)
        }

    @Keep
    private val onKeyboardSizeChangeListener = ManagedPreferenceProvider.OnChangeListener { key ->
        if (keyboardSizePrefs.any { it.key == key }) {
            updateKeyboardSize()
        }
    }

    val keyboardView: View

    init {
        // MUST call before any operation
        setupScope()

        // restore punctuation mapping in case of InputView recreation
        fcitx.launchOnReady {
            punctuation.updatePunctuationMapping(it.statusAreaActionsCached)
        }

        // make sure KeyboardWindow's view has been created before it receives any broadcast
        windowManager.addEssentialWindow(keyboardWindow, createView = true)
        windowManager.addEssentialWindow(symbolPicker)
        windowManager.addEssentialWindow(emojiPicker)
        windowManager.addEssentialWindow(emoticonPicker)
        // show KeyboardWindow by default
        windowManager.attachWindow(KeyboardWindow)

        broadcaster.onImeUpdate(fcitx.runImmediately { inputMethodEntryCached })

        customBackground.imageDrawable = theme.backgroundDrawable(keyBorder)
        // 键盘背景裁剪（跳过预编辑栏、圆角落在工具栏顶部）在 composeTopView 组合内
        // 经 SideEffect 跟随预编辑栏实际高度动态更新（见 composeTopView setContent）

        keyboardView = constraintLayout {
            // allow MotionEvent to be delivered to keyboard while pressing on padding views.
            // although it should be default for apps targeting Honeycomb (3.0, API 11) and higher,
            // but it's not the case on some devices ... just set it here
            isMotionEventSplittingEnabled = true
            add(customBackground, lParams {
                centerVertically()
                centerHorizontally()
            })
            add(composeTopView, lParams(matchParent, wrapContent) {
                topOfParent()
                centerHorizontally()
            })
            add(leftPaddingSpace, lParams {
                below(composeTopView)
                startOfParent()
                bottomOfParent()
            })
            add(rightPaddingSpace, lParams {
                below(composeTopView)
                endOfParent()
                bottomOfParent()
            })
            add(windowManager.view, lParams {
                below(composeTopView)
                above(bottomPaddingSpace)
                /**
                 * set start and end constrain in [updateKeyboardSize]
                 */
            })
            add(bottomPaddingSpace, lParams {
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
                bottomOfParent()
            })
        }

        // 顶部圆角由 customBackground（跳过预编辑栏固定高度）与 ComposeToolbar 自身的
        // clip(RoundedCornerShape(16.dp)) 共同实现，keyboardView 不再整体裁剪，
        // 以免把键盘体顶部的预编辑行裁掉。
        // Custom: 调校浮层挂在键盘主体内（match-constraint 填充 keyboardView），
        // 这样它既不会撑高 InputView，也不会溢出到键盘之外。
        keyboardView.add(keyboardTuneOverlay, ConstraintLayout.LayoutParams(matchParent, 0).apply {
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        })

        updateKeyboardSize()

        add(keyboardView, lParams(matchParent, wrapContent) {
            centerHorizontally()
            bottomOfParent()
        })
        // 按键弹窗层与候选操作菜单覆盖层已并入根组合（FcitxInputMethodService.createComposeInputView），
        // 由根 Composition 在 AndroidView(InputView) 之上渲染，InputView 不再持有这两个宿主。

        keyboardPrefs.registerOnChangeListener(onKeyboardSizeChangeListener)
        advancedPrefs.registerOnChangeListener(onKeyboardSizeChangeListener)
    }

    private fun updateKeyboardSize() {
        // 工具栏高度不再由 View 的 LayoutParams 决定，改为通知 Compose 侧重组
        composeKawaiiBar.notifyToolbarHeightChanged()
        windowManager.view.updateLayoutParams {
            height = keyboardHeightPx
        }
        bottomPaddingSpace.updateLayoutParams {
            height = keyboardBottomPaddingPx
        }
        val sidePadding = keyboardSidePaddingPx
        if (sidePadding == 0) {
            // hide side padding space views when unnecessary
            leftPaddingSpace.visibility = GONE
            rightPaddingSpace.visibility = GONE
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToEnd = unset
                endToStart = unset
                startOfParent()
                endOfParent()
            }
        } else {
            leftPaddingSpace.visibility = VISIBLE
            rightPaddingSpace.visibility = VISIBLE
            leftPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            rightPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToStart = unset
                endToEnd = unset
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
            }
        }
        composeTopView.setPadding(sidePadding, 0, sidePadding, 0)
    }

    // Custom: visual keyboard tuning overlay entry points
    fun showKeyboardTune() {
        keyboardTuneOverlay.show()
        setKeyboardTuneBlur(true)
    }

    fun hideKeyboardTune() {
        keyboardTuneOverlay.hide()
        setKeyboardTuneBlur(false)
    }

    fun isKeyboardTuneShown(): Boolean = keyboardTuneOverlay.visibility == View.VISIBLE

    @android.annotation.TargetApi(31)
    private fun setKeyboardTuneBlur(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= 31) {
            val blur = if (enabled) {
                android.graphics.RenderEffect.createBlurEffect(
                    14f, 14f, android.graphics.Shader.TileMode.CLAMP
                )
            } else null
            windowManager.view.setRenderEffect(blur)
        }
    }

    private fun keyboardTuneMetrics() = KeyboardTuneOverlay.TuneMetrics(
        isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
        toolbarHeightPx = toolbarHeightPx,
        // 实测键盘容器与底部留白的真实矩形（相对 keyboardView，即浮层坐标系）
        keyboardRect = Rect(
            windowManager.view.left,
            windowManager.view.top,
            windowManager.view.right,
            windowManager.view.bottom
        ),
        bottomRect = Rect(
            bottomPaddingSpace.left,
            bottomPaddingSpace.top,
            bottomPaddingSpace.right,
            bottomPaddingSpace.bottom
        ),
        heightBasePx = run {
            val pct = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                keyboardHeightPercentLandscape.getValue() else keyboardHeightPercent.getValue()
            if (pct > 0) keyboardHeightPx * 100 / pct else resources.displayMetrics.heightPixels
        }
    )

    /**
     * 候选操作菜单（Compose 覆盖层）：Compose 侧调用方经此路由到 `ComposeCandidateActionMenu`
     * （anchor 为窗口绝对坐标 [Rect]，与弹窗层同款坐标抽象）。
     */
    override fun showCandidateActionMenu(idx: Int, text: String, anchor: Rect) {
        candidateActionMenu.show(idx, text, anchor)
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        bottomPaddingSpace.updateLayoutParams<LayoutParams> {
            bottomMargin = getNavBarBottomInset(insets)
        }
        return insets
    }

    /**
     * called when [InputView] is about to show, or restart
     */
    fun startInput(info: EditorInfo, capFlags: CapabilityFlags, restarting: Boolean = false) {
        broadcaster.onStartInput(info, capFlags)
        returnKeyDrawable.updateDrawableOnEditorInfo(info)
        if (focusChangeResetKeyboard || !restarting) {
            windowManager.attachWindow(KeyboardWindow)
        }
    }

    override fun onStartHandleFcitxEvent() {
        val inputPanelData = fcitx.runImmediately { inputPanelCached }
        val inputMethodEntry = fcitx.runImmediately { inputMethodEntryCached }
        val statusAreaActions = fcitx.runImmediately { statusAreaActionsCached }
        arrayOf(
            FcitxEvent.InputPanelEvent(inputPanelData),
            FcitxEvent.IMChangeEvent(inputMethodEntry),
            FcitxEvent.StatusAreaEvent(
                FcitxEvent.StatusAreaEvent.Data(statusAreaActions, inputMethodEntry)
            )
        ).forEach { handleFcitxEvent(it) }
    }

    override fun handleFcitxEvent(it: FcitxEvent<*>) {
        when (it) {
            is FcitxEvent.CandidateListEvent -> {
                broadcaster.onCandidateUpdate(it.data)
            }
            is FcitxEvent.ClientPreeditEvent -> {
                preeditEmptyState.updatePreeditEmptyState(clientPreedit = it.data)
                broadcaster.onClientPreeditUpdate(it.data)
            }
            is FcitxEvent.InputPanelEvent -> {
                preeditEmptyState.updatePreeditEmptyState(preedit = it.data.preedit)
                broadcaster.onInputPanelUpdate(it.data)
            }
            is FcitxEvent.IMChangeEvent -> {
                broadcaster.onImeUpdate(it.data)
            }
            is FcitxEvent.StatusAreaEvent -> {
                punctuation.updatePunctuationMapping(it.data.actions)
                broadcaster.onStatusAreaUpdate(it.data.actions)
            }
            else -> {}
        }
    }

    fun updateSelection(start: Int, end: Int) {
        broadcaster.onSelectionUpdate(start, end)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun handleInlineSuggestions(response: InlineSuggestionsResponse): Boolean {
        return composeKawaiiBar.handleInlineSuggestions(response)
    }

    override fun onDetachedFromWindow() {
        advancedPrefs.unregisterOnChangeListener(onKeyboardSizeChangeListener)
        keyboardPrefs.unregisterOnChangeListener(onKeyboardSizeChangeListener)
        // clear DynamicScope, implies that InputView should not be attached again after detached.
        scope.clear()
        super.onDetachedFromWindow()
    }

}
