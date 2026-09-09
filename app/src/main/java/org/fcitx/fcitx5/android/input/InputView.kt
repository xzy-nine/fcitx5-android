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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp as composeDp
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
import org.fcitx.fcitx5.android.input.preedit.PreeditComponent
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
    private val popup = PopupComponent()
    private val punctuation = PunctuationComponent()
    private val returnKeyDrawable = ReturnKeyDrawableComponent()
    private val preeditEmptyState = PreeditEmptyStateComponent()
    private val preedit = PreeditComponent()
    private val composePreedit = ComposePreeditComponent()
    private val commonKeyActionListener = CommonKeyActionListener()
    private val windowManager = InputWindowManager()
    private val composeKawaiiBar = ComposeKawaiiBarComponent()
    // Compose 实现的候选栏组件
    // 旧 View 实现：HorizontalCandidateComponent（已断开接线，保留供对比）
    private val composeCandidate = ComposeCandidateComponent()
    private val keyboardWindow = KeyboardWindow()
    private val symbolPicker = symbolPicker()
    private val emojiPicker = emojiPicker()
    private val emoticonPicker = emoticonPicker()

    /**
     * 合并后的单一 Compose 容器：预编辑栏 + 工具栏共享同一 Composition 与同一个 MiuixTheme，
     * 替换原先各自独立持有的 ComposeView（消除 ComposeView 内嵌 ComposeView 的冗余 Composition）。
     */
    private val composeTopView: ComposeView by lazy {
        ComposeView(themedContext).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                MiuixTheme(controller = remember { ThemeController(ColorSchemeMode.System) }) {
                    Column {
                        // 预编辑栏在上、工具栏在下，与合并前的层级顺序保持一致
                        // 其高度用于让键盘背景从工具栏顶部开始绘制，保证预编辑栏右侧保持透明
                        composePreedit.PreeditContent(
                            modifier = Modifier.onSizeChanged { updatePreeditHeight(it.height) }
                        )
                        // 工具栏高度由 Composable 内部的 HEIGHT 决定，偏好变化后用 key 触发重组
                        key(composeKawaiiBar.toolbarHeightVersion.collectAsState().value) {
                            composeKawaiiBar.ToolbarContent(
                                // 键盘顶部圆角：背景图由 customBackground 裁剪，工具栏自身也裁同样的圆角
                                modifier = Modifier.clip(
                                    RoundedCornerShape(
                                        topStart = KEYBOARD_CORNER_RADIUS_DP.composeDp,
                                        topEnd = KEYBOARD_CORNER_RADIUS_DP.composeDp
                                    )
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * 预编辑栏当前高度（px，无预编辑时为 0）。
     * 预编辑栏跟随工具栏一起被收进了 [composeTopView]，也就位于 keyboardView 之内，
     * 若不处理，键盘背景（customBackground）会铺到预编辑行上，使右侧空白不再透明。
     */
    private var preeditHeightPx = 0

    private fun updatePreeditHeight(heightPx: Int) {
        if (preeditHeightPx == heightPx) return
        preeditHeightPx = heightPx
        applyCustomBackgroundClip()
    }

    /**
     * 键盘背景只绘制到工具栏顶部以下，顶部圆角也因此保持在键盘视觉顶部（工具栏顶部）。
     */
    private fun applyCustomBackgroundClip() {
        customBackground.applyTopRoundedCornerClip(
            dp(KEYBOARD_CORNER_RADIUS_DP.toInt()).toFloat(),
            preeditHeightPx.toFloat()
        )
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
        // 旧 View 实现：scope += preedit（已断开接线，保留供对比）
        scope += composePreedit
        scope += commonKeyActionListener
        scope += windowManager
        // 旧 View 实现：scope += kawaiiBar（已断开接线，保留供对比）
        scope += composeKawaiiBar
        // 旧 View 实现：scope += horizontalCandidate（已断开接线）
        scope += composeCandidate
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
        applyCustomBackgroundClip()

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

        // 键盘顶部圆角改为裁剪键盘背景（customBackground）与工具栏本身，
        // 这样圆角始终落在工具栏顶部，而不会裁掉上方的预编辑栏。

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
        add(popup.root, lParams(matchParent, matchParent) {
            centerVertically()
            centerHorizontally()
        })

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

    companion object {
        /**
         * 键盘顶部圆角半径（dp）。View 侧（键盘背景裁剪）与 Compose 侧（工具栏裁剪）共用。
         */
        private const val KEYBOARD_CORNER_RADIUS_DP = 16f
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
