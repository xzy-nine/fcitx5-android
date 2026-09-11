/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.Intent
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.core.content.ContextCompat
import androidx.transition.Slide
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.input.bar.ComposeKawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.wm.ComposeWindow
import org.fcitx.fcitx5.android.input.wm.EssentialWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.input.wm.createComposeWindowView
import org.fcitx.fcitx5.android.ui.main.SymbolSliderEditActivity
import org.mechdancer.dependency.manager.must
import android.content.res.Configuration

/**
 * 键盘窗口（`Docs/KeyboardComposePlan.md` 批次 C3）。
 *
 * 由 View 改为 [ComposeWindow]：
 *
 * - 视图由 `InputWindowManager` 的统一 ComposeView 宿主承载（D1），essential 窗口的
 *   Composition 被缓存、不 dispose（键盘正需要这个缓存）；
 * - 布局切换不再 `addView/removeView`，而是写 [currentLayout] 这个 Compose 状态（D2）；
 * - 键盘尺寸上报走 `Modifier.onSizeChanged`（D3，首次布局即上报一次）；
 * - 布局切换通知工具栏时机不变（D4）；
 * - 按键/弹层出口原样透传（D5/D6），按键 id 由 [ComposeKey] 依布局位置自造；
 * - 窗口生命周期回调改为写状态层（D12），不再 mutate View。
 *
 * 旧 View 实现（`BaseKeyboard` / `TextKeyboard` / `NumberKeyboard` / `KeyView` /
 * `CustomGestureView`）按 `AGENTS.md` 惯例保留但已断开接线，供对比与回退。
 */
class KeyboardWindow : InputWindow.SimpleInputWindow<KeyboardWindow>(), EssentialWindow,
    ComposeWindow, InputBroadcastReceiver {

    private val service by manager.inputMethodService()
    private val fcitx by manager.fcitx()
    private val commonKeyActionListener: CommonKeyActionListener by manager.must()
    private val windowManager: InputWindowManager by manager.must()
    private val popup: PopupComponent by manager.must()
    private val bar: ComposeKawaiiBarComponent by manager.must()
    private val returnKeyDrawable: ReturnKeyDrawableComponent by manager.must()

    companion object : EssentialWindow.Key

    override val key: EssentialWindow.Key
        get() = KeyboardWindow

    /** 当前布局（Compose 状态）：[TextKeyboard.Name] 或 [NumberKeyboard.Name]。 */
    private val currentLayout = mutableStateOf(TextKeyboard.Name)

    /** 文本键盘状态层（跨布局切换与重组存活）。 */
    private val textKeyboardState by lazy { TextKeyboardState(returnKeyDrawable.resourceId) }

    /** 数字键盘状态层（符号滑块与最近符号）。 */
    private val numberKeyboardState by lazy { NumberKeyboardState() }

    private val prefs = org.fcitx.fcitx5.android.data.prefs.AppPrefs.getInstance().keyboard

    override fun enterAnimation(lastWindow: InputWindow) = Slide().apply {
        slideEdge = Gravity.BOTTOM
    }.takeIf {
        // disable animation switching between picker
        lastWindow !is PickerWindow
    }

    override fun exitAnimation(nextWindow: InputWindow) =
        super.exitAnimation(nextWindow).takeIf {
            // disable animation switching between picker
            nextWindow !is PickerWindow
        }

    private val keyActionListener = KeyActionListener { action, source ->
        if (action is KeyAction.LayoutSwitchAction) {
            switchLayout(action.act)
        } else {
            commonKeyActionListener.listener.onKeyAction(action, source)
        }
    }

    private val popupActionListener: PopupActionListener by lazy { popup.listener }

    /**
     * 兜底路径：`InputWindowManager` 对 [ComposeWindow] 会走统一的 ComposeView 宿主，
     * 正常不会调用到这里；保留实现以防窗口被非 Compose 路径创建。
     */
    override fun onCreateView(): View = createComposeWindowView(context) { Content() }

    // -----------------------------------------------------------------------
    // Content（Compose 渲染）
    // -----------------------------------------------------------------------

    @Composable
    override fun Content() {
        val layout by currentLayout
        // 分体键盘相关偏好做成响应式（View 侧注册了 OnChangeListener 即时重建）
        val splitRequested = prefs.splitKeyboard.preferenceState()
        val splitThreshold = prefs.splitKeyboardThreshold.preferenceState()
        val blankRatio = prefs.splitKeyboardBlankRatio.preferenceState()
        val blankRatioLandscape = prefs.splitKeyboardBlankRatioLandscape.preferenceState()
        val landscape =
            LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val gapRatio = gapRatioFromBlankPercent(
            if (landscape) blankRatioLandscape else blankRatio
        )
        val density = LocalDensity.current

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { bar.onKeyboardSizeChanged(it.width, it.height) },
        ) {
            // 分体是否允许由「真实宽高比 > 阈值」决定，与 View 侧 isSplitAllowed 同口径
            val widthPx = with(density) { maxWidth.toPx() }.toInt()
            val heightPx = with(density) { maxHeight.toPx() }.toInt()
            val split = splitRequested &&
                    isSplitAllowedByRatio(widthPx, heightPx, splitThreshold)

            when (layout) {
                NumberKeyboard.Name -> ComposeNumberKeyboard(
                    state = numberKeyboardState,
                    keyActionListener = keyActionListener,
                    popupActionListener = popupActionListener,
                    onSymbolSliderEdit = ::launchSymbolSliderEdit,
                    modifier = Modifier.fillMaxSize(),
                    split = split,
                )

                else -> ComposeTextKeyboard(
                    state = textKeyboardState,
                    keyActionListener = keyActionListener,
                    popupActionListener = popupActionListener,
                    modifier = Modifier.fillMaxSize(),
                    split = split,
                    gapRatio = gapRatio,
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // 布局切换（D2：保留 postpone 语义 —— Picker 依赖它先切内部布局再 attach 窗口）
    // -----------------------------------------------------------------------

    fun switchLayout(to: String) {
        ContextCompat.getMainExecutor(service).execute {
            if (to == TextKeyboard.Name || to == NumberKeyboard.Name) {
                if (to == currentLayout.value) return@execute
                currentLayout.value = to
                if (windowManager.isAttached(this)) {
                    notifyBarLayoutChanged()
                }
            } else {
                windowManager.attachWindow(PickerWindow.Key.Symbol)
            }
        }
    }

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        val targetLayout = when (info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER -> NumberKeyboard.Name
            InputType.TYPE_CLASS_PHONE -> NumberKeyboard.Name
            else -> TextKeyboard.Name
        }
        switchLayout(targetLayout)
    }

    override fun onImeUpdate(ime: InputMethodEntry) {
        textKeyboardState.onInputMethodUpdate(ime)
    }

    override fun onPunctuationUpdate(mapping: Map<String, String>) {
        textKeyboardState.onPunctuationUpdate(mapping)
    }

    override fun onReturnKeyDrawableUpdate(resourceId: Int) {
        textKeyboardState.onReturnDrawableUpdate(resourceId)
    }

    override fun onAttached() {
        // View 侧 attachLayout 里补推的三项状态
        textKeyboardState.onAttach()
        textKeyboardState.onReturnDrawableUpdate(returnKeyDrawable.resourceId)
        textKeyboardState.refreshLangSwitchKeyVisibility()
        textKeyboardState.refreshSpaceVoiceIcon()
        fcitx.runImmediately { inputMethodEntryCached }?.let {
            textKeyboardState.onInputMethodUpdate(it)
        }
        // 符号滑块编辑结果回调（View: NumberKeyboard.onAttach）
        SymbolSliderEditStore.setOnResultListener { newSymbols ->
            numberKeyboardState.applySymbols(newSymbols)
        }
        SymbolSliderEditStore.consume()?.let { numberKeyboardState.applySymbols(it) }
        numberKeyboardState.refreshRecent()

        notifyBarLayoutChanged()
    }

    override fun onDetached() {
        popup.dismissAll()
    }

    // Call this when
    // 1) the keyboard window was newly attached
    // 2) currently keyboard window is attached and switchLayout was used
    private fun notifyBarLayoutChanged() {
        bar.onKeyboardLayoutSwitched(currentLayout.value == NumberKeyboard.Name)
    }

    /** 启动滑块符号编辑弹窗（顶部 Activity，内含输入框），结果经 [SymbolSliderEditStore] 回传。 */
    private fun launchSymbolSliderEdit() {
        SymbolSliderEditStore.setOnResultListener { newSymbols ->
            numberKeyboardState.applySymbols(newSymbols)
        }
        val intent = Intent(context, SymbolSliderEditActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(
                SymbolSliderEditActivity.EXTRA_SYMBOLS,
                numberKeyboardState.sliderSymbols.joinToString("")
            )
        }
        context.startActivity(intent)
    }
}


