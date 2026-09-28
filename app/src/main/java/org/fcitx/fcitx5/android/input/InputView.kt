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
import android.widget.FrameLayout
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.ImageView
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.doOnLayout
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.ComposeKawaiiBarComponent
import org.fcitx.fcitx5.android.input.bar.ToolbarHeightTrace
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcaster
import org.fcitx.fcitx5.android.input.broadcast.PreeditEmptyStateComponent
import org.fcitx.fcitx5.android.input.broadcast.PunctuationComponent
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.candidates.ComposeCandidateActionMenu
import org.fcitx.fcitx5.android.input.candidates.horizontal.ComposeCandidateComponent
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyboardHeightPercentBase
import org.fcitx.fcitx5.android.input.keyboard.KeyboardHeightPercentBase.DisplayMetrics
import org.fcitx.fcitx5.android.input.keyboard.KeyboardHeightPercentBase.RealSize
import org.fcitx.fcitx5.android.input.keyboard.KeyboardTuneCompose
import org.fcitx.fcitx5.android.input.keyboard.TuneMetrics
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.picker.emojiPicker
import org.fcitx.fcitx5.android.input.picker.emoticonPicker
import org.fcitx.fcitx5.android.input.picker.symbolPicker
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.preedit.ComposePreeditComponent
import androidx.core.view.isVisible
import org.fcitx.fcitx5.android.input.voice.VoiceInputComponent
import org.fcitx.fcitx5.android.input.voice.VoicePanelHost
import org.fcitx.fcitx5.android.input.wm.createComposeWindowView
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
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

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
    // 键盘调音覆盖层（custom 特色：拖拽调键盘高度/边距/间隙，模糊键盘背景）：
    // 根组合（createComposeInputView）经 OverlayContent 渲染，故需对外可见
    internal val keyboardTune = KeyboardTuneCompose({ keyboardTuneMetrics() }) { setKeyboardTuneBlur(false) }
    private val keyboardWindow = KeyboardWindow()
    private val symbolPicker = symbolPicker()
    private val emojiPicker = emojiPicker()
    private val emoticonPicker = emoticonPicker()
    // custom: 内置语音输入的会话组件
    internal val voiceInput = VoiceInputComponent()

    /**
     * custom: 语音面板覆盖层宿主（挂在 [InputWindowManager.view] 里、当前窗口之上）。
     *
     * 可见性由 [VoiceInputComponent.panelVisibleListener] 直接翻转**本 View**（外层 ComposeView）；
     * 只翻转 Compose 内部的 `LocalView` 会让这个 GONE 的父级永远隐藏面板。
     */
    private val voicePanelView: View by lazy {
        createComposeWindowView(themedContext) {
            // 删除键等按键动作复用主键盘的监听器，保证与键盘语义完全一致
            VoicePanelHost(voiceInput, commonKeyActionListener.listener)
        }.apply {
            // 置于当前窗口之上：后续 attachWindow 追加的窗口 View 不会盖住它
            elevation = 1f
            // 面板显示时必须**吃掉触摸**：Compose 内容里没有 pointer handler 的空白区域
            // 默认不消费事件，会穿透到下层键盘（实测能点到下面的键）
            isClickable = true
            isVisible = false
            voiceInput.panelVisibleListener = { visible ->
                isVisible = visible
                // custom(临时诊断)：语音面板是「从语音输入回来后触发挤压」的边界事件，
                // 记录开合瞬间，用于把日志时间线与语音往返对齐
                ToolbarHeightTrace.log(
                    "voicePanel",
                    "visible=$visible"
                )
            }
        }
    }

    /**
     * 工具栏 Compose 容器：预编辑栏、顶部延伸带与工具栏合并后的单一 Composition。
     * 预编辑栏高度**贴合内容**（空态 0），`onComputeInsets` 补偿 `composePreedit.heightPx`
     * 实际高度 —— keyboardView 顶部已含预编辑高度，补偿后正好抵消，故 insets 恒定不随打字变化。
     *
     * 结构自上而下：预编辑栏 → [顶部延伸带][topExtensionPx] → 工具栏。
     * 延伸带是**键盘体圆角的向上延伸**（本身不画背景，由下方 customBackground 透出键盘底色/
     * 背景图，圆角裁剪落在它的顶部），把键盘体上缘抬到 app 可视区之上，盖住部分其他 app
     * UI 组件与键盘之间的空隙；带子**不计入 IME 可见区**（[topExtensionPx] 在
     * `onComputeInsets` 里被补偿掉），因此 app 内容不伸缩。
     */
    private val composeTopView: ComposeView by lazy {
        ComposeView(themedContext).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                MiuixTheme(controller = remember { ThemeController(ColorSchemeMode.System) }) {
                    // 预编辑栏高度变化时同步键盘背景裁剪：跳过预编辑行、圆角落在顶部延伸带顶
                    // 用 LaunchedEffect 而非 SideEffect：后者在每次成功重组后都会执行，
                    // 而裁剪参数只在高度变化时才需要重新下发（避免反复重建 OutlineProvider）
                    val preeditHeightPx = composePreedit.heightPx.collectAsState().value
                    androidx.compose.runtime.LaunchedEffect(preeditHeightPx) {
                        customBackground.applyTopRoundedCornerClip(
                            dp(IME_TOP_CORNER_RADIUS_DP).toFloat(),
                            preeditHeightPx.toFloat()
                        )
                    }
                    // custom(临时诊断)：预编辑可见性与候选到达门控上提到 Column 之外，
                    // 供下面的 onSizeChanged 日志一并打印
                    val preeditVisible = composePreedit.preeditVisible.collectAsState().value
                    val candidateReceived = composeKawaiiBar.candidateReceived.collectAsState().value
                    Column(
                        // custom：Compose 内容高度变化后核对宿主 ComposeView 是否跟上，
                        // 滞后则异步补发布局请求。修 AndroidView 互操作宿主在 measure 期间
                        // 触发 requestLayout 被吞、导致 View 层遍历停摆、内容被旧高度裁切的 bug
                        // （见 HostLayoutRecovery.kt）。
                        modifier = Modifier.onSizeChanged { size ->
                            composeTopView.recoverHostLayoutIfStale(size.height)
                            // custom(临时诊断)：记录顶部容器实测高度
                            ToolbarHeightTrace.logChange(
                                key = "imeTopColumn",
                                dedupeKey = "${size.height}/$preeditHeightPx/" +
                                        "$preeditVisible/$candidateReceived",
                                detail = "columnHeightPx=${size.height} " +
                                        "preeditHeightPx=$preeditHeightPx " +
                                        "preeditVisible=$preeditVisible " +
                                        "candidateReceived=$candidateReceived"
                            )
                        }
                    ) {
                        // 预编辑栏在上（贴合内容高度），键盘体顶部延伸带居中，工具栏在下
                        // 首次按键时 InputPanelEvent（预编辑）与 CandidateListEvent（候选）是两个
                        // 独立事件，可能跨帧到达。若预编辑栏先出现而候选栏尚未到达，工具栏仍处
                        // Idle 态（数字行/工具按钮），下一帧才切到候选态 → 单帧闪烁。
                        // 用 candidateReceived 门控：预编辑变非空时置 false，候选事件到达时置 true，
                        // 仅当候选事件已到达才让预编辑栏可见，两者同帧出现，消除闪烁。
                        // AnimatedVisibility 做展开/收起过渡动画，onSizeChanged 上报动画中间高度，
                        // 背景裁剪随之平滑跟进。
                        androidx.compose.animation.AnimatedVisibility(
                            visible = preeditVisible && candidateReceived,
                            enter = androidx.compose.animation.expandVertically(
                                expandFrom = Alignment.Top,
                                animationSpec = androidx.compose.animation.core.tween(200),
                            ) + androidx.compose.animation.fadeIn(
                                animationSpec = androidx.compose.animation.core.tween(200),
                            ),
                            exit = androidx.compose.animation.shrinkVertically(
                                shrinkTowards = Alignment.Top,
                                animationSpec = androidx.compose.animation.core.tween(150),
                            ) + androidx.compose.animation.fadeOut(
                                animationSpec = androidx.compose.animation.core.tween(150),
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .onSizeChanged { composePreedit.setHeightPx(it.height) },
                        ) {
                            composePreedit.PreeditContent(gate = true)
                        }
                        Box(Modifier.fillMaxWidth().height(ImeTopExtension))
                        // 工具栏高度由 Composable 内部的 HEIGHT 决定，偏好变化后用 key 触发重组
                        key(composeKawaiiBar.toolbarHeightVersion.collectAsState().value) {
                            composeKawaiiBar.ToolbarContent()
                        }
                    }
                }
            }
        }
    }

    /**
     * 该高度被 `FcitxInputMethodService.onComputeInsets` 加回 `contentTopInsets` 的计算里，
     * 把「键盘体向上多长出来的这一截」**抵消掉** —— 于是延伸带不计入 IME 可见区，
     * app 的内容区与改动前逐像素一致，带子只是盖在 app 可视区之上遮住空隙。
     */
    val topExtensionPx: Int get() = dp(IME_TOP_EXTENSION_DP)

    /**
     * custom(临时诊断)：顶部容器（[composeTopView]）的 View 实测高度（px）。
     *
     * 故障态下预编辑高度涨到 58 而 `keyboardViewTop` 不动 —— 该值可直接区分
     * 「Compose Column 没长高」与「Column 长了但 ConstraintLayout/insets 没跟上」。
     */
    val composeTopViewHeightPx: Int
        get() = if (composeTopView.isLaidOut) composeTopView.height else -1

    /**
     * custom(临时诊断)：顶部容器的**已测量**高度（px）。
     *
     * 与 [composeTopViewHeightPx]（已布局高度）对照即可判定故障卡在哪一级：
     *  - `measured=168` 而 `height=110` → 测量已按新内容跑过，**布局没把新尺寸写下去**；
     *  - `measured` 也停在 `110` → 测量本身就没跟上内容增长。
     */
    val composeTopViewMeasuredHeightPx: Int
        get() = if (composeTopView.measuredHeight > 0) composeTopView.measuredHeight else -1

    /** custom(临时诊断)：顶部容器当前是否挂着待处理的 layout 请求。 */
    val composeTopViewLayoutRequested: Boolean
        get() = composeTopView.isLayoutRequested

    /**
     * custom(临时诊断)：ComposeView 内部 `AndroidComposeView` 的实测高度（px）。
     * 它反映 Compose 侧真正布局出来的尺寸，用于和 [composeTopViewMeasuredHeightPx] 对照。
     */
    val composeTopViewChildHeightPx: Int
        get() = composeTopView.getChildAt(0)?.let { if (it.height > 0) it.height else -1 } ?: -1

    /**
     * custom(临时诊断)：一帧内把三级容器的「已布局 / 已测量 / 是否挂着 layout 请求」一起打出来。
     *
     * 这是本轮排查的核心读数：
     *  - `topView`（[composeTopView]，ComposeView）的 `m`（measuredHeight）若停在空闲值 110，
     *    说明它没被父级重新测量过；
     *  - `keyboardView` 的 `m`/`h` 同步不变，说明它也没重量；
     *  - `req=true` 长期挂着 = `requestLayout()` 发出后没被消费。
     */
    private fun traceTreeState(): String =
        "topView[h=${composeTopView.height} m=${composeTopView.measuredHeight} " +
                "childH=${composeTopView.getChildAt(0)?.height ?: -1} " +
                "req=${composeTopView.isLayoutRequested}] " +
                "keyboardView[h=${keyboardView.height} m=${keyboardView.measuredHeight} " +
                "req=${keyboardView.isLayoutRequested}] " +
                "selfReq=$isLayoutRequested preedit=${composePreedit.heightPx.value} " +
                "attached=$isAttachedToWindow windowVis=$windowVisibility " +
                "vis=$visibility shown=$isShown vtoAlive=${viewTreeObserver.isAlive} " +
                // custom(临时诊断)：isInLayout 是公开 API，能证明「遍历正卡在 layout 阶段」
                "inLayout=$isInLayout"

    /**
     * custom(临时诊断)：窗口可见性变化。
     *
     * 故障态的现象是「ViewRootImpl 不再遍历」；若 `windowVis` 从 VISIBLE 变成别的值后
     * 再没回到 VISIBLE，就说明遍历被系统按「窗口不可见」跳过，与 Compose 侧无关。
     */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        ToolbarHeightTrace.log(
            "inputViewWindowVis",
            "windowVisibility=$visibility ${traceTreeState()}"
        )
    }

    /** custom(临时诊断)：本 View 自身的可见性变化。 */
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        ToolbarHeightTrace.log(
            "inputViewVisibility",
            "changed=${changedView.javaClass.simpleName} visibility=$visibility"
        )
    }

    /**
     * custom(临时诊断)：`InputView`（根 ConstraintLayout）的 layout 遍历累计次数。
     * 故障态下 Compose 内容已长到 168 而 ComposeView 仍卡在 110，靠它区分
     * 「遍历根本没跑」与「跑了但写了旧值」。
     */
    private var layoutTraversalCount = 0

    /** custom(临时诊断)：`InputView` 的 measure 遍历累计次数。 */
    private var measureTraversalCount = 0

    /** custom(临时诊断)：窗口级 layout 完成（`onGlobalLayout`）累计次数。 */
    private var globalLayoutCount = 0

    /**
     * custom(临时诊断)：**每次** measure 遍历都打印（刻意不去重）。
     *
     * 上一轮把 `dedupeKey` 设为「已测高度」，结果故障态下三个值恒定 →
     * 遍历即使真在跑也被去重成 0 行，无法判定遍历是否推进。
     * 这里的 `n=` 计数就是判据本身，故必须逐次输出。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        measureTraversalCount++
        lastTraversalAtMs = android.os.SystemClock.uptimeMillis()
        ToolbarHeightTrace.log(
            "inputViewOnMeasure",
            "n=$measureTraversalCount self=${measuredWidth}x${measuredHeight} " +
                    "${traceTreeState()} " +
                    "spec=${android.view.View.MeasureSpec.toString(heightMeasureSpec)}"
        )
    }

    /**
     * custom(临时诊断)：**每次** layout 遍历都打印（刻意不去重）。
     * 判据是 `n=`（`traversals`）是否推进 —— 同上，去重会把判据本身吃掉。
     */
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        layoutTraversalCount++
        ToolbarHeightTrace.log(
            "inputViewOnLayout",
            "n=$layoutTraversalCount changed=$changed ${traceTreeState()}"
        )
    }

    private val onGlobalLayoutListener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
        globalLayoutCount++
        ToolbarHeightTrace.log(
            "inputViewGlobalLayout",
            "n=$globalLayoutCount ${traceTreeState()}"
        )
    }

    /**
     * custom(临时诊断)：窗口级 layout 完成监听。
     *
     * `onMeasure`/`onLayout` 只证明**本 View** 的遍历；`onGlobalLayout` 证明
     * **ViewRootImpl 级别**的遍历仍在调度。故障时若它继续推进而 `topView.m` 不变，
     * 说明遍历在跑但没把新尺寸算进去；若它也停住，说明整棵树不再被遍历。
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ToolbarHeightTrace.log("inputViewGlobalLayout", "listener attached")
        viewTreeObserver.addOnGlobalLayoutListener(onGlobalLayoutListener)
        postDelayed(stuckWatchdog, STUCK_POLL_MS)
    }

    /**
     * custom(临时诊断)：`requestLayout()` 调用计数与最近一次调用来源。
     *
     * 故障态下 View 遍历停摆，本计数用来判定是哪一种：
     *  - **计数不再增长** → 没人请求布局（或请求路径不经过本 View）；
     *  - **计数持续增长但遍历仍不发生** → 请求发了、ViewRootImpl 不执行。
     */
    private var requestLayoutCount = 0
    private var lastRequestLayoutCaller = "-"

    override fun requestLayout() {
        super.requestLayout()
        requestLayoutCount++
        // 仅取 1..3 帧，够定位来源且避免吞掉整个栈
        lastRequestLayoutCaller = Throwable().stackTrace
            .drop(1)
            .take(3)
            .joinToString(">") { "${it.className.substringAfterLast('.')}.${it.methodName}" }
    }

    /** custom(临时诊断)：最近一次完成遍历（measure/layout/globalLayout）的时刻。 */
    private var lastTraversalAtMs = 0L

    /**
     * custom(临时诊断)：布局停摆周期监测 —— 本轮最关键探针（第 3 版）。
     *
     * **前两版都失败了**，教训值得记录：
     *  - 第 1 版绑在 [requestLayout] 上：停摆后不再有请求，回调永不触发；
     *  - 第 2 版判据用 `InputView.isLayoutRequested`：实测停摆时该值为 **false**，
     *    真正卡住的是 `composeTopView`，于是看门狗一次都没触发。
     *
     * 第 3 版改为**独立周期轮询**（不依赖任何回调），并同时监视两个 View 的
     * `isLayoutRequested`。判据：某个 View 挂着 FORCE_LAYOUT 而整棵树已超过阈值无遍历。
     * 这能同时覆盖 `forceLayout()`（只置标志、不向上传播、不调度）与 `requestLayout()` 两种路径。
     *
     * 恢复后自动复位去重，故反复停摆/恢复都能各留一行。
     */
    private val stuckWatchdog = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) {
                // 未附着时不停摆判定（冷启动早期本就没有遍历）
                postDelayed(this, STUCK_POLL_MS)
                return
            }
            val sinceTraversal = android.os.SystemClock.uptimeMillis() - lastTraversalAtMs
            val topStuck = composeTopView.isLayoutRequested
            val selfStuck = isLayoutRequested
            if ((topStuck || selfStuck) && sinceTraversal > STUCK_THRESHOLD_MS) {
                ToolbarHeightTrace.logChange(
                    key = "layoutStall",
                    // 按「两个标志 + 遍历计数」去重：状态不变则只留一行，恢复后再停摆会再留一行
                    dedupeKey = "$topStuck/$selfStuck/$measureTraversalCount/$layoutTraversalCount",
                    detail = "布局停摆 ${sinceTraversal}ms 无遍历 " +
                            "topViewForced=$topStuck selfForced=$selfStuck " +
                            "measureN=$measureTraversalCount layoutN=$layoutTraversalCount " +
                            "globalN=$globalLayoutCount inLayout=$isInLayout " +
                            "reqCalls=$requestLayoutCount lastReqFrom=$lastRequestLayoutCaller " +
                            "${traceTreeState()} chain=${traceLayoutChain().second}"
                )
            } else {
                // 健康：清掉去重，下次停摆必定重新输出
                ToolbarHeightTrace.reset("layoutStall")
            }
            postDelayed(this, STUCK_POLL_MS)
        }
    }

    /** custom(临时诊断)：判定「标志挂着却迟迟无遍历」的阈值。 */
    private companion object {
        const val STUCK_THRESHOLD_MS = 600L

        /** 轮询周期：够密集以捕获停摆当刻，又不足以造成日志噪声（仅在异常时输出）。 */
        const val STUCK_POLL_MS = 300L
    }

    /** custom(临时诊断)：供 `onComputeInsets` 输出的布局请求读数。 */
    val layoutRequestDebug: String
        get() = "reqCalls=$requestLayoutCount lastReqFrom=$lastRequestLayoutCaller"

    /**
     * custom(临时诊断)：从本 View 向上遍历父链，逐级取 `isLayoutRequested` / 可见性 / 尺寸。
     *
     * 返回 (去重键, 详情)：去重键只含「类名:是否请求布局」，尺寸变化不会造成新行。
     * 用途是定位 `requestLayout()` 在**哪一级停止向上传播** ——
     * 若本 View 为 true 而某级父 View 为 false，问题就在该父级；
     * 若整条链到顶都是 true，则问题在 ViewRootImpl 不调度。
     */
    private fun traceLayoutChain(): Pair<String, String> {
        val key = StringBuilder()
        val detail = StringBuilder()
        var v: View? = this
        var depth = 0
        while (true) {
            if (v == null) {
                detail.append(" <- ViewRootImpl")
                break
            }
            if (depth >= 16) {
                detail.append(" <- ...")
                break
            }
            if (depth > 0) {
                key.append('<')
                detail.append(" <- ")
            }
            key.append("${v.javaClass.simpleName}:${v.isLayoutRequested}")
            detail.append(
                "${v.javaClass.simpleName}[req=${v.isLayoutRequested} " +
                        "vis=${v.visibility} ${v.width}x${v.height}]"
            )
            v = v.parent as? View
            depth++
        }
        return key.toString() to detail.toString()
    }

    /** custom(临时诊断)：父链上各级的布局请求状态（去重键）。 */
    val layoutChainKey: String get() = traceLayoutChain().first

    /** custom(临时诊断)：父链上各级的布局请求状态（可读详情）。 */
    val layoutChainDetail: String get() = traceLayoutChain().second

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
        scope += keyboardTune
        // custom: 语音输入会话组件（面板/工具栏/空格长按都通过它）
        scope += voiceInput
        broadcaster.onScopeSetupFinished(scope)
    }

    private val keyboardPrefs = AppPrefs.getInstance().keyboard

    private val focusChangeResetKeyboard by keyboardPrefs.focusChangeResetKeyboard

    /**
     * 上一次 [startInput] 看到的输入框标识，用来区分「同一个框被应用重启输入连接」与「焦点换到了另一个框」。
     *
     * 两者在框架层都是 `onStartInputView(restarting = true)`，只看 `restarting` 分不开：
     * 不少应用（例如 `io.legato.kazusa`）会在自己改动文本/选区之后重启输入连接做 resync，
     * 此时把 Picker / 剪贴板等面板踢回主键盘是纯打扰。
     */
    private var lastEditorKey: EditorKey? = null

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

    // keyboardHeightPx 的 base 缓存：getRealSize() 走 Binder + Point 分配，
    // displayMetrics 在配置不变时恒定。以 (baseType, configuration.hashCode()) 为键——
    // 旋转/导航栏显隐等都会触发 onConfigurationChanged → Configuration 变化 → 缓存失效。
    private var cachedBaseType: KeyboardHeightPercentBase? = null
    private var cachedBaseConfig: Int = 0
    private var cachedBase: Int = 0

    private val keyboardHeightPx: Int
        get() {
            val baseType = keyboardHeightPercentBase.getValue()
            val configHash = resources.configuration.hashCode()
            val base = if (baseType == cachedBaseType && configHash == cachedBaseConfig) {
                cachedBase
            } else {
                val b = when (baseType) {
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
                cachedBaseType = baseType
                cachedBaseConfig = configHash
                cachedBase = b
                b
            }
            val percent = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardHeightPercentLandscape
                else -> keyboardHeightPercent
            }.getValue()
            Timber.d("keyboardHeightPx get(): baseType=${baseType}, base=${base}, percent=${percent}")
            return base * percent / 100
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
            // 拖拽调音改了键盘尺寸后，让 Compose 调音浮层重新读取几何并刷新卡片位置
            if (keyboardTune.isShown()) {
                windowManager.view.doOnLayout { keyboardTune.refresh() }
            }
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

        broadcaster.onImeUpdate(fcitx.peek { inputMethodEntryCached })

        customBackground.imageDrawable = theme.backgroundDrawable(keyBorder)
        // 键盘背景裁剪（跳过预编辑栏、圆角落在工具栏顶部）在 composeTopView 组合内
        // 经 LaunchedEffect 跟随预编辑栏实际高度动态更新（见 composeTopView setContent）

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
            // custom: 语音面板覆盖层挂进窗口容器（与当前窗口同几何位置）。
            // 面板**不做窗口切换** —— 键盘窗口保持 attach，工具栏可见可用、空格长按的手势
            // 不会因布局切换被 CANCEL，物理松手才能被键盘侧收到（见 VoicePanelHost 注释）。
            // elevation 保证之后 attachWindow 追加的窗口 View 不会盖住它。
            windowManager.view.add(
                voicePanelView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            add(bottomPaddingSpace, lParams {
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
                bottomOfParent()
            })
        }

        // 顶部圆角由 customBackground 实现：裁剪时跳过预编辑高度，圆角便落在「顶部延伸带」顶部
        // （composeTopView Column 里的恒高 Box），因此键盘体上缘比工具栏顶高出一截
        // （见 composeTopView / topExtensionPx）。keyboardView 不整体裁剪，以免把键盘体顶部的
        // 预编辑行裁掉；延伸带本身不画背景，透出 keyboardView 的底色/背景图。
        // Custom: 键盘调校浮层已迁为 Compose IME 覆盖层（KeyboardTuneCompose），
        // 由根组合（createComposeInputView）在 AndroidView(InputView) 之上渲染，
        // 不再作为 keyboardView 的子 View 挂载，坐标系改用窗口绝对坐标。

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
        keyboardTune.show()
        setKeyboardTuneBlur(true)
    }

    fun hideKeyboardTune() {
        keyboardTune.hide()
        setKeyboardTuneBlur(false)
    }

    fun isKeyboardTuneShown(): Boolean = keyboardTune.isShown()

    @android.annotation.TargetApi(31)
    private fun setKeyboardTuneBlur(enabled: Boolean) {
        val blur = if (enabled) {
            android.graphics.RenderEffect.createBlurEffect(
                14f, 14f, android.graphics.Shader.TileMode.CLAMP
            )
        } else null
        windowManager.view.setRenderEffect(blur)
    }

    /**
     * 调音浮层已迁为 Compose IME 覆盖层，宿主是 IME 根组合（填充整个 IME 窗口），
     * 因此几何坐标必须是**窗口绝对坐标**（原点 0，落在整窗上），不再是相对 keyboardView 的局部坐标。
     * 直接用 [View.getLocationInWindow] 把键盘容器与底部留白在 IME 窗口里的真实矩形取出来，
     * 与浮层 BoxWithConstraints 的坐标系（同样填充 IME 窗口）完全对齐。
     */
    private fun keyboardTuneMetrics(): TuneMetrics {
        val kbLoc = IntArray(2).also { windowManager.view.getLocationInWindow(it) }
        val bottomLoc = IntArray(2).also { bottomPaddingSpace.getLocationInWindow(it) }
        return TuneMetrics(
            isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
            // 键盘区顶（窗口绝对 y）：其上方是「顶部延伸带 + 预编辑栏 + 工具栏」，
            // 调音浮层对这些区域的触摸一律放行，工具栏按钮保持可点（见 KeyboardTuneOverlay）
            topGuardPx = kbLoc[1],
            // 实测键盘容器的真实矩形（IME 窗口绝对坐标，与浮层坐标系一致）
            keyboardRect = Rect(
                kbLoc[0],
                kbLoc[1],
                kbLoc[0] + windowManager.view.width,
                kbLoc[1] + windowManager.view.height
            ),
            // 底部留白（键盘下方的空间）真实矩形
            bottomRect = Rect(
                bottomLoc[0],
                bottomLoc[1],
                bottomLoc[0] + bottomPaddingSpace.width,
                bottomLoc[1] + bottomPaddingSpace.height
            ),
            heightBasePx = run {
                val pct = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                    keyboardHeightPercentLandscape.getValue() else keyboardHeightPercent.getValue()
                if (pct > 0) keyboardHeightPx * 100 / pct else resources.displayMetrics.heightPixels
            }
        )
    }

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
        // `restarting = false`（新一次输入会话）照旧重置回主键盘；
        // `restarting = true` 只有在**换了输入框**时才按 `focusChangeResetKeyboard` 重置——
        // 同一个框被应用 resync 重启输入连接时保留当前面板（见 [lastEditorKey]）。
        val editorKey = EditorKey.of(info)
        val sameEditor = editorKey.isSameAs(lastEditorKey)
        lastEditorKey = editorKey
        Timber.d("startInput: restarting=$restarting, sameEditor=$sameEditor, key=$editorKey")
        if (!restarting || (focusChangeResetKeyboard && !sameEditor)) {
            // 收起语音面板覆盖层（若有）：会话丢弃、麦克风释放
            voiceInput.closePanel()
            windowManager.attachWindow(KeyboardWindow)
        }
    }

    override fun onStartHandleFcitxEvent() {
        val inputPanelData = fcitx.peek { inputPanelCached }
        val inputMethodEntry = fcitx.peek { inputMethodEntryCached }
        val statusAreaActions = fcitx.peek { statusAreaActionsCached }
        arrayOf(
            FcitxEvent.InputPanelEvent(inputPanelData),
            FcitxEvent.IMChangeEvent(inputMethodEntry),
            FcitxEvent.StatusAreaEvent(
                FcitxEvent.StatusAreaEvent.Data(statusAreaActions, inputMethodEntry)
            )
        ).forEach { handleFcitxEvent(it) }
        // 恢复缓存事件时只发了 InputPanelEvent（无 CandidateListEvent），
        // onPreeditEmptyStateUpdate 会把 candidateReceived 置 false → 预编辑栏被门控隐藏。
        // 恢复阶段没有待到达的候选事件，复位为 true 让缓存的预编辑栏正常显示。
        composeKawaiiBar.markCandidateReceived()
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
        // custom(临时诊断)：移除 global layout 监听（见上面的注册处）
        if (viewTreeObserver.isAlive) {
            viewTreeObserver.removeOnGlobalLayoutListener(onGlobalLayoutListener)
        }
        ToolbarHeightTrace.log("inputViewGlobalLayout", "listener detached")
        advancedPrefs.unregisterOnChangeListener(onKeyboardSizeChangeListener)
        keyboardPrefs.unregisterOnChangeListener(onKeyboardSizeChangeListener)
        // clear DynamicScope, implies that InputView should not be attached again after detached.
        scope.clear()
        super.onDetachedFromWindow()
    }

}

/**
 * 输入框标识（只取「换框会变、同框重启不变」的字段），用于 [InputView.startInput] 区分
 * 「同一输入框重启」与「焦点换框」。
 *
 * 刻意**不含** `initialSelStart/End`：应用重启输入连接时经常带上陈旧（甚至差一格）的选区，
 * 把它算进来会让「同框重启」永远被判成换框。
 *
 * `fieldId` 为 [View.NO_ID]（应用没给控件 id）时退化为只比较其余字段。
 */
private class EditorKey(
    private val packageName: String?,
    private val fieldId: Int,
    private val inputType: Int,
    private val hintText: String?,
    private val imeOptions: Int,
) {

    fun isSameAs(other: EditorKey?): Boolean = other != null &&
            packageName == other.packageName &&
            inputType == other.inputType &&
            hintText == other.hintText &&
            imeOptions == other.imeOptions &&
            (fieldId == other.fieldId || fieldId == View.NO_ID)

    override fun toString(): String =
        "EditorKey(pkg=$packageName, fieldId=$fieldId, inputType=0x${inputType.toString(16)}, " +
                "hint=$hintText, imeOptions=0x${imeOptions.toString(16)})"

    companion object {
        fun of(info: EditorInfo) = EditorKey(
            packageName = info.packageName,
            fieldId = info.fieldId,
            inputType = info.inputType,
            // hintText 可能是 Spanned，转成 String 再比较，避免同一段文字因实例类型不同而判成换框
            hintText = info.hintText?.toString(),
            imeOptions = info.imeOptions,
        )
    }
}
