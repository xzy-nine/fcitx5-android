/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 触控笔手写（Android 13+ 触控笔手写协议）。
 *
 * 应用侧调用 `InputMethodManager.startStylusHandwriting()`（或文本框自动手写）后，
 * 系统依次回调本 IME（见 `FcitxInputMethodService` 的 4 个 override）：
 * - `onPrepareStylusHandwriting()`：笔尖接近时的预热回调（预加载识别模型；不保证启动会话）；
 * - `onStartStylusHandwriting()`：返回 true 进入会话 —— 系统给应用发 ACTION_CANCEL、把
 *   触控笔事件改道给 IME；本类把墨迹视图挂到系统墨迹窗口
 *   （`InputMethodService.getStylusHandwritingWindow()`，官方要求 attach via setContentView）；
 * - `onStylusHandwritingMotionEvent()`：每个触控笔 MotionEvent（已重写转发到这里，
 *   不依赖墨迹窗口可见时机）；
 * - `onFinishStylusHandwriting()`：会话结束（系统空闲超时 / 输入结束 / IME 主动
 *   `finishStylusHandwriting()`）→ 固化活动区并清理。
 *
 * 识别**完全对齐米系 `MiuiHandWritingIMEStylus` 的会话模型**：整段墨迹累积，
 * 抬笔后 [STYLUS_SETTLE_MS]（= 米系 `mTextEditTimer` 500ms）到点做**二选一** ——
 * 是手势 → 交编辑器执行；否则把**整段墨迹一次性**识别出**单个结果** `commitText` 上屏。
 * 不做逐笔识别、不做叠写切分、不产生候选（米系同样只有单结果）。
 * 手势判定与识别后端解耦：系统引擎（小米随手写）优先，不可用时文字走自带 ONNX 模型
 * （整窗当一个字）、手势走本地几何启发式 `HandwritingGestures` + `HandwritingStrokeFx`。
 * 与键盘手写布局（`HandwritingKeyboardLayout`，那套仍用 `HandwritingSegmenter` 多字切分）
 * 是**并列的两条输入入口**，不共享状态。
 *
 * 墨迹渲染用原生 `View`（不走 Compose：系统墨迹窗口的 decorView 没有 lifecycle owner 链，
 * 且原生绘制延迟更低）；笔宽按 `AXIS_PRESSURE` 调制；橡皮擦端（`TOOL_TYPE_ERASER`）
 * 笔画用于擦除与它相交的墨迹（撤销该笔，不删文本）。
 *
 * 坐标系假设：墨迹窗口默认全屏（"entire screen area is handwritable"）且事件坐标即
 * 窗口坐标，与全屏墨迹视图 1:1 对应 —— 真机上若墨迹有整体偏移，先查窗口 insets。
 *
 * ⚠️ 系统回调都在主线程；本类所有公开方法约定主线程调用（识别推理切 Default）。
 */
package org.fcitx.fcitx5.android.input.handwriting

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.DeleteGesture
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.HandwritingGesture
import android.view.inputmethod.InsertGesture
import android.view.inputmethod.SelectGesture
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.data.handwriting.HandwritingCandidate
import org.fcitx.fcitx5.android.data.handwriting.HandwritingEngine
import org.fcitx.fcitx5.android.data.handwriting.HandwritingGestures
import org.fcitx.fcitx5.android.data.handwriting.HandwritingMarketCategory
import org.fcitx.fcitx5.android.data.handwriting.HandwritingStrokeFx
import org.fcitx.fcitx5.android.data.handwriting.HandwritingStrokeKind
import org.fcitx.fcitx5.android.data.handwriting.StrokePoint
import org.fcitx.fcitx5.android.data.handwriting.XiaomiHandwritingEngine
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.EditorKey
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.utils.forceShowSelf
import timber.log.Timber
import java.util.function.IntConsumer
import kotlin.math.max

/** 墨迹视图命中目标（单点按下抬起视为点选）：候选 chips 与（旧的窗口内按钮，已迁至工具箱）。 */
private sealed interface StylusTapTarget {
    object Undo : StylusTapTarget

    object Redo : StylusTapTarget

    object Space : StylusTapTarget

    object Enter : StylusTapTarget

    object Backspace : StylusTapTarget

    object Keyboard : StylusTapTarget

    object Close : StylusTapTarget

    data class Chip(val index: Int) : StylusTapTarget
}

/** 一条墨迹笔画：采样点（识别用）+ 每点压力（渲染笔宽用）+ 捕获时的视图屏幕原点（手势还原屏幕坐标用）。 */
private class InkStroke(
    val points: List<StrokePoint>,
    val pressures: List<Float>,
    val originX: Float = 0f,
    val originY: Float = 0f,
)

/** 进行中笔画的积累器（带捕获时的视图屏幕原点）。 */
private class InkStrokeBuilder(val originX: Float, val originY: Float) {
    val points = ArrayList<StrokePoint>()
    val pressures = ArrayList<Float>()
}

/**
 * 触控笔手写会话控制器。
 *
 * 由 `FcitxInputMethodService` 持有（1–2 行锚点），复用键盘手写布局的识别管线与
 * 活动区替换上屏，不与 `HandwritingInputComponent` 共享状态（两者是并列的输入入口）。
 */
class StylusHandwritingController(private val service: FcitxInputMethodService) {

    private val prefs = AppPrefs.getInstance().handwriting

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, throwable ->
            // 兜底：协程里未捕获的异常（含 native 层 Error）若逃逸会杀掉 IME 进程，
            // 触控笔手写出问题只应表现为「识别不可用」。
            Timber.e(throwable, "stylus handwriting: uncaught coroutine exception")
        }
    )


    private var loadJob: Job? = null
    private var idleJob: Job? = null

    /** 系统手写引擎是否已初始化可用（[XiaomiHandwritingEngine.open] 成功）。 */
    @Volatile
    private var systemEngineReady = false

    /** 系统引擎异步预热任务（初始化要加载 native 库 + 模型，不能占主线程）。 */
    private var systemEngineWarmUpJob: Job? = null

    /** 插入模式手势的取消计时（米系 `mGestureTimer` 同款 3s）。 */
    private var insertModeJob: Job? = null

    /** 进行中的手势预览取消信号（米系 `mPreviewCancellationSignal`）。 */
    private var gesturePreviewSignal: android.os.CancellationSignal? = null

    /** 手势预览的推理任务（引擎推理不能占主线程）。 */
    private var gesturePreviewJob: Job? = null

    /**
     * 编辑器「可见文本行」（屏幕坐标）。
     *
     * 来源 `CursorAnchorInfo.getVisibleLineBounds()`（API 34，经 `matrix.mapRect` 映射），
     * 由 `onUpdateCursorAnchorInfo` 下发。**手势与书写的分界就靠它**：
     * 笔画落在已有文字上 = 对文字本身的操作 = **手势**（涂改/圈选…）；
     * 笔画落在空白处 = **书写**。
     */
    private var visibleLineBounds: List<android.graphics.RectF> = emptyList()

    /** 手势判定状态（米系 `com.miui.ime.g.b` / 讯飞 `w63.mCurrentState` 同义）。 */
    private var gestureState = GESTURE_UNDECIDED

    /** 本识别窗口内的落笔次数（米系 `g` / 讯飞 `i`）：手势是**单笔**，第 2 笔起即视为书写。 */
    private var gestureDownCount = 0

    /** 手势路径（屏幕坐标，米系 `mGesturePath`）。 */
    private val gesturePath = android.graphics.Path()
    private val gestureRegion = android.graphics.Region()
    private val gestureClip = android.graphics.Region()
    private val gestureClipRect = android.graphics.Rect()

    /**
     * 本笔书写途中引擎判出过的最后一个手势及其笔画点数。
     *
     * 引擎的手势分类在笔画**末段**可能翻回 null（最后几个抬笔采样点扰动包围盒/形状），
     * 而中段一直稳定判为该手势（实测：265..273 点判 DeleteGesture、275..277 点变 null）。
     * 会话结束时若最终判定为书写，且该手势是在**末段**（[GESTURE_TAIL_CACHE_RATIO] 之后）
     * 判出的，则沿用它 —— 用户意图仍是手势，末段只是收笔噪声。
     */
    private var tailGesture: HandwritingGesture? = null
    private var tailGesturePointCount = 0

    /** 系统引擎连续返回空的次数（用于自降级判定）。 */
    private var systemEngineEmptyStreak = 0

    /** 已放弃系统引擎（连续空结果，通常是包名不在引擎白名单内）。 */
    @Volatile
    private var systemEngineGaveUp = false

    /** 会话进行中（`onStartStylusHandwriting` 返回 true 之后）。 */
    @Volatile
    private var sessionActive = false

    /** 最近一次提交到输入框的字（候选点选时替换它）。 */
    private var lastCommittedText = ""

    /**
     * 最近一次识别的候选（挂浮动工具箱的候选行）。
     *
     * **识别完成、字已上屏后仍保留**（自带模型较弱，用户常需要点选纠错），
     * 直到下一次识别刷新或工具箱收起。
     */
    private var lastSegCandidates: List<HandwritingCandidate> = emptyList()


    private val inkView by lazy {
        StylusInkView(
            service,
            onStroke = ::onStrokeCommitted,
            onTap = ::onTap,
            onStrokeFinished = ::onStrokeFinished,
            // 浮动手写栏已无「收起」按钮：**手指点击墨迹区即收起**（等价旧 Close）
            onFingerTap = ::requestClose,
        )
    }

    /**
     * 浮动工具箱（**米系同款浮窗卡片**，见 [StylusToolboxWindow] 顶部说明）。
     *
     * 宿主是 IME 窗口 decorView 上的透明全屏容器，卡片可拖动、位置按横竖屏记忆；
     * 并**不画在墨迹窗口里**（系统会话结束会 `InkWindow.hide()`，那里的按钮会随之消失）。
     */
    private val toolboxWindow by lazy {
        StylusToolboxWindow(service).apply {
            setOnAction { action -> onToolboxAction(action) }
        }
    }

    /** 工具箱卡片在 IME 窗口坐标中的矩形（米系 `getTouchRegion()` 同义：insets 可触摸区）。 */
    fun toolboxRectInWindow(): android.graphics.Rect? {
        if (!toolboxWindow.isShown) return null
        val decor = runCatching { service.window.window?.decorView }.getOrNull() ?: return null
        decor.getLocationOnScreen(decorOrigin)
        val rect: android.graphics.Rect = toolboxWindow.cardRectOnScreen()
        rect.offset(-decorOrigin[0], -decorOrigin[1])
        return rect
    }

    private val decorOrigin = IntArray(2)

    // ------------------------------------------------------------------
    // 系统回调入口（FcitxInputMethodService 转发）
    // ------------------------------------------------------------------

    /** `onPrepareStylusHandwriting`：预热识别后端（轻量；不保证随后一定启动会话）。 */
    fun prepare() {
        if (!isEnabled()) return
        if (backendReady()) return
        ensureModelLoaded()
    }

    /**
     * 识别后端是否已就绪：**系统手写引擎或自带 ONNX 模型任一可用即可**。
     *
     * 注意不能只看 [HandwritingEngine.isReady]：系统引擎可用时我们**不会加载 ONNX**
     * （省内存），此时 `isReady` 恒为 false，只看它会把整条触控笔路径挡在门外。
     *
     * 只做**状态查询**，不触发耗时的引擎初始化（那走 [warmUpSystemEngine]）。
     */
    private fun backendReady(): Boolean =
        systemEngineReady || HandwritingEngine.isReady

    /**
     * `onUpdateEditorToolType(TOOL_TYPE_STYLUS)`：**米系同款的进入时机**。
     *
     * 米系在检测到触控笔（而非等手写会话开始）就切到触控笔界面：撤下键盘 + 显示浮动工具箱。
     * 这样触控笔点键盘区域时不会与键盘手势冲突（键盘已不在），也无需事件转发。
     * 只做界面切换与预热，**不启动系统手写会话**（那是落笔时 `onStartStylusHandwriting` 的事）。
     */
    fun onToolStylus() {
        if (!isEnabled()) return
        // 后端还没就绪：异步预热（引擎初始化要加载 native 库 + 模型，不能占主线程），
        // 界面切换照做（键盘该撤就得撤），否则首次落笔时键盘还在
        warmUpSystemEngine()
        if (!backendReady()) ensureModelLoaded()
        // 撤下键盘 + 显示浮动工具箱（米系 setStylusMode(true) 的效果）
        service.enterStylusUi()
        setToolboxVisible(prefs.stylusToolboxEnabled.getValue())
        // 候选跨会话保留：工具箱卡片若被重建，把最近一次识别的候选重新挂上
        if (lastSegCandidates.isNotEmpty()) publishToolboxCandidates()
    }

    /** `onUpdateEditorToolType(TOOL_TYPE_FINGER)`：手指输入 → 回到普通 IME 界面。 */
    fun onToolFinger() {
        if (!service.stylusUiActive.value) return
        // 会话仍在时不打断；只把界面交还键盘（用户改用手指了）
        service.exitStylusUi()
        setToolboxVisible(false)
    }

    /**
     * `onStartStylusHandwriting`：进入会话。
     *
     * 手写总开关关闭或模型未就绪时返回 false —— 系统把本次请求视为 no-op
     * （触控笔事件继续归应用）；模型就绪后用户的下一次落笔会再次触发请求。
     *
     * **会话是「一次书写」级别**（米系把会话超时设为 500ms：写完一个停顿即结束，
     * 下次落笔重新进入本方法），因此本方法必须**幂等且不做书写状态复位**：
     * 复位会清掉上一笔的墨迹与活动区文本，表现为「只能触发一次」。
     * 复位只发生在外部动作（[finalizeWindowInternal]）与空闲清窗里。
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun start(): Boolean {
        if (!isEnabled()) return false
        if (!backendReady()) {
            // 主线程不能阻塞等引擎初始化（加载 native 库 + 模型）：异步预热后返回 false，
            // 系统把本次请求视为 no-op，用户下一次落笔时后端已就绪
            warmUpSystemEngine()
            ensureModelLoaded()
            return false
        }
        val window = runCatching { service.getStylusHandwritingWindow() }.getOrNull() ?: return false
        sessionActive = true
        // 记录本次会话实际使用的识别后端（排查「系统引擎到底有没有被调用」只看这一行）
        Timber.i(
            "stylus handwriting: session start, backend=%s, systemReady=%b, onnxReady=%b",
            if (systemEngineReady) "system" else "onnx",
            systemEngineReady, HandwritingEngine.isReady,
        )
        ensureInkAttached(window)
        // 工具箱挂到 IME 窗口之上（米系做法），随会话显隐
        setToolboxVisible(prefs.stylusToolboxEnabled.getValue())
        // 候选跨会话保留：工具箱卡片若被重建，把最近一次识别的候选重新挂上
        if (lastSegCandidates.isNotEmpty()) publishToolboxCandidates()
        // custom: 进入触控笔 UI —— 撤下键盘（米系 `setInputView(空锚点)` 等价做法）
        service.enterStylusUi()
        // 米系 `ImeMenuViewHolder.show()` 末尾的 `requestShowInputView()`
        // （`BaseStylusInputMethodService.requestShowSelf(0)`）：浮动工具箱挂在 IME 窗口上，
        // IME 窗口被系统隐藏时工具箱会一起不可见，故主动保持其显示。
        runCatching { service.forceShowSelf() }
        // 米系同款（讯飞 `w63.E()` = onStartStylusHandwriting）：**每会话重置手势判定**——
        // `mCurrentState = 0; actionDownCount = 0; mGesturePath.reset()`。
        // 手势是**单笔**：本会话第一笔是手势候选，第 2 笔起即视为书写；
        // 会话（500ms 空闲）结束后下一笔重新成为候选。
        resetGestureState()
        // 空闲清窗继续有效：会话切换不得让上一笔的固化计时丢失
        scheduleIdleFinalize()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // 米系同款短会话超时（空写 500ms 即结束会话），写完一个字后系统释放会话，
            // 下一次落笔重新走 canStartStylusHandwriting → 本方法；书写状态跨会话保留。
            // （getStylusHandwritingIdleTimeoutMax 是 Java static，须按类调用；此处用短值）
            runCatching {
                service.setStylusHandwritingSessionTimeout(java.time.Duration.ofMillis(
                    SESSION_IDLE_TIMEOUT_MS
                ))
            }
        }
        return true
    }

    /**
     * 确保墨迹视图挂在当前墨迹窗口上。
     *
     * 墨迹窗口可能被框架重建（`finishAndRemoveStylusHandwritingWindow` 后 `maybeCreateAndInitInkWindow`
     * 会 new 一个新的 InkWindow），因此每次进入会话都核对一次：未挂载、挂在别的窗口、
     * 或窗口当前不可见时重新 `setContentView`（Gboard `StylusModule` 同款判断）。
     */
    private fun ensureInkAttached(window: android.view.Window) {
        val sameWindow = runCatching { inkView.rootView === window.decorView.rootView }
            .getOrDefault(false)
        if (inkView.isAttachedToWindow && sameWindow &&
            window.decorView.visibility == View.VISIBLE
        ) {
            return
        }
        (inkView.parent as? ViewGroup)?.removeView(inkView)
        runCatching {
            window.setContentView(
                inkView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            window.decorView.visibility = View.VISIBLE
        }
        // 挂载后手动 measure/layout（Gboard 同款）：窗口尚未走布局时，
        // 首批笔画/点选的命中区（chips/按钮）也按全屏尺寸就位
        val metrics = service.resources.displayMetrics
        inkView.measure(
            View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY)
        )
        inkView.layout(0, 0, inkView.measuredWidth, inkView.measuredHeight)
    }

    /**
     * 工具箱显示/隐藏。
     *
     * **米系同款**：加在 IME 窗口的 decorView 上（`StylusUtils.addStylusToolbox`），
     * 并配合 `start()` 里的 `forceShowSelf()` 让 IME 窗口保持显示——若不加在 IME 窗口上
     * 而是用附带父窗口的浮窗，IME 隐藏时工具箱会一起不可见。
     */
    private fun setToolboxVisible(visible: Boolean) {
        if (visible) {
            val decor = runCatching { service.window.window?.decorView }.getOrNull() as? ViewGroup
                ?: run {
                    Timber.w("stylus handwriting: toolbox show failed (no decor view)")
                    return
                }
            toolboxWindow.show(decor)
        } else {
            toolboxWindow.hide()
        }
        Timber.d(
            "stylus handwriting: toolbox visible=%b shown=%b", visible, toolboxWindow.isShown,
        )
        // 触摸区域随工具箱显隐变化，必须主动请求重算 insets：
        // 否则 `onComputeInsets` 不再被调用，`touchableRegion` 停在旧值 —— 触控笔模式下
        // `visibleTopInsets = decorView.height`（可见区高度为 0），
        // 落回 `TOUCHABLE_INSETS_VISIBLE` 就等于**整个 IME 窗口不可触摸**，
        // 所有点击（含候选词、工具箱按钮）都会被路由给应用。
        requestInsetsRecompute()
    }

    /** 请求重算 IME insets（触控笔模式的触摸区域依赖它）。 */
    private fun requestInsetsRecompute() {
        service.requestInsetsRecompute()
    }

    /** 配置变化：浮窗重算安全区并夹取位置。 */
    fun onConfigurationChanged() {
        toolboxWindow.onConfigurationChanged()
    }

    /** `onStylusHandwritingMotionEvent`：触控笔事件（主线程）。 */
    fun onMotionEvent(event: MotionEvent) {
        if (!sessionActive) return
        // custom: 笔落在浮动工具箱上 → 转派给工具箱（按钮点击），**不作为手写笔画**。
        // 手写会话期间系统把所有触控笔事件直接投给本回调、不走窗口分发，
        // 所以必须在这里按坐标分流，否则「点工具箱会变成手写」。
        if (routeToToolbox(event)) return
        // custom: 手写只发生在编辑框声明的文本框区域内（应用未声明时不限制）
        if (event.actionMasked == MotionEvent.ACTION_DOWN &&
            outsideEditorBounds(event.rawX, event.rawY)
        ) {
            return
        }
        inkView.feed(event)
        // 米系手势判定状态机（`com.miui.ime.g.j()` / 讯飞 `w63` 每事件同款）：
        // 决定这一笔是「对文字本身的操作（手势）」还是「书写」，见 [advanceGestureState]。
        advanceGestureState(event)
        // 米系同款：**只有判定为手势候选才调引擎判手势并实时预览**
        // （`previewHandwritingGesture`，仅 Select/Delete 等 Previewable 手势有效）。
        // 推理在 [Dispatchers.Default]，且同一时刻只跑一个（见 previewGestureIfPossible）。
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelGesturePreview()
                // 米系同款（`removeCallbacks(mTextEditTimer)`）：**落笔即取消停顿计时**。
                // 否则「上一笔抬笔 → 新笔落笔 → 新笔还在写」这段时间里计时会到点，
                // 把正在写的这一笔之前的笔画当成「写完了」提交（半个字上屏）。
                // 只对触控笔/橡皮擦取消：手指事件不产生笔画，取消会让提交被无限延后。
                val tool = event.getToolType(event.actionIndex)
                if (tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER) {
                    idleJob?.cancel()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (gestureState == GESTURE_GESTURE) previewGestureIfPossible()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelGesturePreview()
                // 米系同款（`postDelayed(mTextEditTimer, 500)`）：**抬笔即排定停顿计时**。
                // 与上面的「落笔取消」成对；覆盖所有抬笔路径——正常完成笔画（onStrokeCommitted
                // 已排一次，这里幂等重排）、单点选中 chips/按钮、ACTION_CANCEL 丢弃该笔。
                val tool = event.getToolType(event.actionIndex)
                if (tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER) {
                    scheduleIdleFinalize()
                }
            }
        }
    }

    /** 取消进行中的手势预览（米系 `mPreviewCancellationSignal.cancel()`）。 */
    private fun cancelGesturePreview() {
        runCatching { gesturePreviewSignal?.cancel() }
        gesturePreviewSignal = null
    }

    /**
     * 实时手势预览（米系 `previewHandwritingGesture` 同款）。
     *
     * 落笔过程中让引擎判一次手势，若是**可预览手势**（`SelectGesture`/`DeleteGesture` 等
     * `PreviewableHandwritingGesture`）就交编辑器预览，用户能看到「这一笔会被当成圈选/删除」。
     * 编辑器不支持预览时 `previewHandwritingGesture` 返回 false，静默忽略即可。
     *
     * 引擎推理（反射 + TFLite）**必须离开主线程**：米系同样把 `getGestureRecognizeResult`
     * 投到 `mBackgroundHandler`，结果再 post 回主线程。这里用 [scope] + `Dispatchers.Default`。
     */
    private fun previewGestureIfPossible() {
        if (!systemEngineReady) return
        if (!prefs.handwritingSystemEngineEnabled.getValue()) return
        // 屏幕坐标：手势区域要交给编辑器，必须是 screen coordinates（见 snapshotScreen）
        val points = inkView.currentPointsScreen() ?: return
        if (gesturePreviewJob?.isActive == true) return
        gesturePreviewJob = scope.launch(Dispatchers.Default) {
            val gesture = XiaomiHandwritingEngine.recognizeGesture(points) ?: return@launch
            withContext(Dispatchers.Main) {
                // 记录末段判出的手势（供会话结束时兜底，见 [tailGesture]）
                tailGesture = gesture
                tailGesturePointCount = points.size
                if (gesture !is android.view.inputmethod.PreviewableHandwritingGesture) {
                    return@withContext
                }
                val ic = service.currentInputConnection ?: return@withContext
                cancelGesturePreview()
                val signal = android.os.CancellationSignal()
                gesturePreviewSignal = signal
                runCatching { ic.previewHandwritingGesture(gesture, signal) }
            }
        }
    }

    /** 把落在工具箱卡片上的触控笔事件转派给工具箱；接管中的整笔都归它。 */
    private fun routeToToolbox(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                toolboxTouchActive = toolboxWindow.hitTest(event.rawX, event.rawY)
                // 同时打出「按下点」与「卡片真实屏幕矩形」，用于判定命中区域是否与画出来的卡片重合
                Timber.d(
                    "stylus handwriting: stylus down at (%.0f,%.0f) toolboxHit=%b cardRect=%s",
                    event.rawX, event.rawY, toolboxTouchActive,
                    toolboxWindow.cardRectOnScreen().toShortString(),
                )
                if (toolboxTouchActive) toolboxWindow.dispatchStylusEvent(event)
                return toolboxTouchActive
            }

            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!toolboxTouchActive) return false
                toolboxWindow.dispatchStylusEvent(event)
                if (event.actionMasked != MotionEvent.ACTION_MOVE) toolboxTouchActive = false
                return true
            }
        }
        return false
    }

    /** 当前触控笔是否正按在工具箱上（整笔归工具箱，避免中途变成笔画）。 */
    private var toolboxTouchActive = false

    /** 上一次 `onStartInput` 看到的输入框标识：区分「同框 resync」与「换框」（见 [onStartInput]）。 */
    private var lastEditorKey: EditorKey? = null

    /**
     * 编辑框声明的**手写区域**（`EditorBoundsInfo.getHandwritingBounds()`，屏幕坐标）。
     *
     * 米系的「限制手写区域」最终就是落到这里：手写只应发生在系统提供的文本框内。
     * 为空时（应用未声明）退回「工具箱以外全部可写」。
     */
    private var handwritingBounds: android.graphics.RectF? = null

    /** 由 `onUpdateCursorAnchorInfo` 下发编辑框手写区域。 */
    fun onEditorBounds(bounds: android.graphics.RectF?) {
        handwritingBounds = bounds
    }

    /**
     * 由 `onUpdateCursorAnchorInfo` 下发**编辑器可见文本行**（屏幕坐标）。
     *
     * 空表表示编辑器没有提供（或 API < 34）：此时米系按「允许手势」处理
     * （`mVisibleLineBounds.isEmpty()` 是放行条件之一）。
     */
    fun onVisibleLineBounds(bounds: List<android.graphics.RectF>) {
        visibleLineBounds = bounds
    }

    /** 手势路径是否与任一可见文本行相交（米系 `g.h(RectF)` / 讯飞 `w63.u()` 同义）。 */
    private fun gesturePathIntersectsVisibleLines(): Boolean {
        if (visibleLineBounds.isEmpty()) return true
        return visibleLineBounds.any { rect ->
            gestureClipRect.set(
                rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt(),
            )
            gestureClip.set(gestureClipRect)
            gestureRegion.setPath(gesturePath, gestureClip)
        }
    }

    /**
     * 推进手势判定状态机（米系 `com.miui.ime.g.j()` / 讯飞 `w63` 每事件同款）。
     *
     * - 第 2 笔起（或已判定为手势后再落笔）→ [GESTURE_WRITING]，本窗口不再判手势；
     * - 未定时若「手势路径 ∩ 可见文本行」或编辑器没给文本行 → [GESTURE_GESTURE]。
     */
    private fun advanceGestureState(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            gestureDownCount++
            if (gestureState == GESTURE_GESTURE || gestureDownCount > 1) {
                gestureState = GESTURE_WRITING
            }
        }
        if (gestureState != GESTURE_WRITING) {
            // 屏幕坐标：与 matrix 映射后的 visibleLineBounds 同口径
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                gesturePath.moveTo(event.rawX, event.rawY)
            } else {
                gesturePath.lineTo(event.rawX, event.rawY)
            }
        }
        if (gestureState == GESTURE_UNDECIDED && gesturePathIntersectsVisibleLines()) {
            gestureState = GESTURE_GESTURE
            Timber.d(
                "stylus handwriting: gesture candidate (downCount=%d, lines=%d)",
                gestureDownCount, visibleLineBounds.size,
            )
        }
    }

    /**
     * 手势的关键坐标（排查用）。
     *
     * AOSP 规定这些坐标是**屏幕坐标**；打印出来即可判断「手势区域是否落在目标文字上」，
     * 以及「是否被窗口原点偏移」（墨迹窗口不在 (0,0) 时最容易出这种错）。
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun describeGesture(g: HandwritingGesture): String = runCatching {
        when (g) {
            is android.view.inputmethod.SelectGesture -> g.selectionArea
            is android.view.inputmethod.DeleteGesture -> g.deletionArea
            is android.view.inputmethod.InsertGesture -> g.insertionPoint
            is android.view.inputmethod.InsertModeGesture -> g.insertionPoint
            is android.view.inputmethod.JoinOrSplitGesture -> g.joinOrSplitPoint
            else -> null
        }?.toString() ?: "?"
    }.getOrDefault("?")

    /**
     * 复位手势判定（清窗/新会话时调用）。
     */
    private fun resetGestureState() {
        gestureState = GESTURE_UNDECIDED
        gestureDownCount = 0
        gesturePath.reset()
        tailGesture = null
        tailGesturePointCount = 0
        cancelGesturePreview()
    }

    /** 屏幕坐标是否在编辑框的手写区域外（为空视为不限）。 */
    private fun outsideEditorBounds(x: Float, y: Float): Boolean {
        val b = handwritingBounds ?: return false
        // 留一点余量：编辑框边界与实际落笔位置常有小数像素误差
        val margin = EDITOR_BOUNDS_MARGIN_DP * service.resources.displayMetrics.density
        return x < b.left - margin || x > b.right + margin ||
                y < b.top - margin || y > b.bottom + margin
    }

    /**
     * `onFinishStylusHandwriting`：**一次书写会话**结束（系统空闲超时 / 主动请求）。
     *
     * 米系同款：**不在此处移除工具箱**（`MiuiHandWritingIMEStylus.onFinishStylusHandwriting`
     * 只移除墨迹 View）——会话是短会话，用户写字停顿就会结束一次会话，
     * 若跟着移除工具箱，工具栏会随每次停顿闪烁消失。工具箱只在
     * [onInputViewFinished]（输入视图结束）与 [release]（服务销毁）时移除。
     *
     * **系统会话与书写状态解耦**：本方法只终止「接收事件」这一段，
     * **不得取消识别与提交计时**（[idleJob]）——系统的会话超时比我们的
     * 停顿提交更早到，取消它们会让这一笔既不上屏也不清窗。书写状态的真正清理只发生在
     * [resetWindow]（外部动作/换框）与 [onInputViewFinished]/[release]（视图结束/销毁）。
     *
     * 幂等：框架可在 `onFinishInput` 等路径结束会话，可能不经过 [start]。
     */
    fun finish() {
        sessionActive = false
        // 只丢弃未完成的那一笔（不会再收到 UP）；识别结果与停顿提交跨会话保留
        inkView.cancelCurrentStroke()
    }

    /**
     * 输入视图结束（`onFinishInputView`）：补交未提交内容、退出触控笔 UI、移除工具箱
     * （米系在此处 `hide()`）。
     *
     * 此处必须**补交**：视图已收起，[scheduleIdleFinalize] 的停顿计时不会再等到用户回到
     * 这个输入框，不补交这一笔就丢了。
     */
    fun onInputViewFinished() {
        finish()
        finalizeWindowInternal()
        setToolboxVisible(false)
        service.exitStylusUi()
        lastEditorKey = null
    }

    /**
     * `onStartInput`：应用**重启输入连接**（resync）或**换了输入框**时的处理。
     *
     * 与键盘侧 [InputView.startInput] 同一口径（共用 [EditorKey]）：
     * `restarting=false` 与「换了输入框」都要复位书写状态，**同一输入框的 resync 则保留**。
     *
     * 同框 resync 时保留窗口是关键：此刻窗口里是用户**还没写完**的字，窗口
     * 只是残缺笔画的识别结果，补交会把半个字上屏。保留后由笔停的自然提交
     * （[scheduleIdleFinalize]）在用户真正写完时才上屏。
     * 换框/新会话则丢弃（不提交）：那些笔画属于旧输入框。
     */
    fun onStartInput(info: EditorInfo, restarting: Boolean) {
        val key = EditorKey.of(info)
        val sameEditor = key.isSameAs(lastEditorKey)
        lastEditorKey = key
        Timber.d(
            "stylus handwriting onStartInput: restarting=%b, sameEditor=%b, strokes=%d, key=%s",
            restarting, sameEditor, inkView.strokeCount, key,
        )
        // 同框 resync：原样保留书写窗口（会话结束一次性提交的模型下无需任何补救）
        if (restarting && sameEditor) return
        discardWindow()
        handwritingBounds = null
    }

    /** IME 销毁：释放会话、工具箱与协程（不调用系统方法）。 */
    fun release() {
        finish()
        setToolboxVisible(false)
        service.exitStylusUi()
        systemEngineReady = false
        systemEngineEmptyStreak = 0
        systemEngineGaveUp = false
        XiaomiHandwritingEngine.close()
        scope.cancel()
    }

    private fun isEnabled(): Boolean = prefs.handwritingInputEnabled.getValue()

    private fun ensureModelLoaded() {
        // 系统引擎已就绪（或正在预热）时不加载 ONNX 模型（省内存与启动时间）；
        // 关掉开关或引擎不可用时才回落加载
        if (prefs.handwritingSystemEngineEnabled.getValue() &&
            (systemEngineReady || systemEngineWarmUpJob?.isActive == true)
        ) {
            return
        }
        if (loadJob?.isActive == true) return
        val modelId = prefs.handwritingModelId.getValue()
        if (HandwritingEngine.isReady && HandwritingEngine.loadedModel() == modelId) return
        loadJob = scope.launch {
            if (!HandwritingMarketCategory.isReady(service, modelId)) return@launch
            HandwritingEngine.load(service, modelId)
        }
    }

    /**
     * 初始化系统手写引擎（幂等，**同步**）。成功时置 [systemEngineReady]。
     *
     * ⚠️ 只在**非主线程**调用：`RecognizeFacade` 构造会加载 8.8MB 算法库与 17MB OCR 模型
     * （`MultiLineRecognize.init` → `nativeInitOCR`），主线程调用会卡住 IME。
     * 主线程路径请用 [warmUpSystemEngine]（异步）。
     *
     * 引擎自带包名白名单（只服务搜狗/百度/讯飞小米版等 12 个包名），
     * 由 [XiaomiHandwritingEngine.open] 内部反射放行，这里无需额外判断。
     */
    private fun ensureSystemEngineBlocking(): Boolean {
        if (systemEngineReady) return true
        if (systemEngineGaveUp) return false
        if (!prefs.handwritingSystemEngineEnabled.getValue()) return false
        val ok = XiaomiHandwritingEngine.open(service)
        systemEngineReady = ok
        if (ok) Timber.i("stylus handwriting: system engine in use")
        return ok
    }

    /**
     * 异步预热系统引擎（主线程安全，幂等）。
     *
     * 引擎初始化耗时可观（加载 native 库 + 模型），放到 [Dispatchers.Default]；
     * 预热完成后 [systemEngineReady] 置位，落笔时即可直接使用。
     */
    private fun warmUpSystemEngine() {
        if (systemEngineReady || systemEngineGaveUp) return
        if (!prefs.handwritingSystemEngineEnabled.getValue()) return
        if (systemEngineWarmUpJob?.isActive == true) return
        systemEngineWarmUpJob = scope.launch(Dispatchers.Default) {
            val ok = ensureSystemEngineBlocking()
            withContext(Dispatchers.Main) {
                Timber.i("stylus handwriting: system engine warm-up done, ready=%b", ok)
            }
        }
    }

    // ------------------------------------------------------------------
    // 交互（墨迹视图回调，主线程）
    // ------------------------------------------------------------------

    private fun onTap(target: StylusTapTarget) {
        when (target) {
            StylusTapTarget.Space -> onSpace()
            StylusTapTarget.Enter -> onEnter()
            StylusTapTarget.Backspace -> onBackspace()
            StylusTapTarget.Undo -> onUndo()
            StylusTapTarget.Redo -> onRedo()
            StylusTapTarget.Keyboard -> finalizeWindowInternal()
            StylusTapTarget.Close -> requestFinish()
            // 候选点选（候选挂在浮动工具箱的候选行，不走墨迹层 chips）
            is StylusTapTarget.Chip -> onChipTap(target.index)
        }
    }

    /** 把当前候选刷到浮动工具箱的候选行（点选走 [onChipTap] 替换刚上屏的字）。 */
    private fun publishToolboxCandidates() {
        toolboxWindow.setCandidates(lastSegCandidates.map { it.char }) { index -> onChipTap(index) }
    }

    /**
     * 候选点选：用选中的候选替换**刚上屏的那个字**，然后**结束这次候选交互**（清掉候选）。
     *
     * 先试精确替换（[FcitxInputMethodService.replaceBeforeCursor] 会先只读校验光标前的文本）；
     * **校验不通过时回落「退格删除 + 提交」** —— 很多应用不实现 `getTextBeforeCursor`，
     * 只依赖只读校验会让点选变成「点了没反应」。
     */
    private fun onChipTap(index: Int) {
        val picked = lastSegCandidates.getOrNull(index)?.char
        if (picked.isNullOrEmpty()) return
        val expected = lastCommittedText
        Timber.d(
            "stylus handwriting: candidate #%d picked=%s (current=%s)",
            index, picked, expected,
        )
        if (expected.isNotEmpty() && expected != picked &&
            service.replaceBeforeCursor(expected, picked)
        ) {
            lastCommittedText = picked
        } else {
            // 回落：删掉刚上屏的那个字再提交选中的候选（不依赖 getTextBeforeCursor）
            if (expected.isNotEmpty()) service.deleteBeforeCursor(expected.length)
            service.commitText(picked)
            lastCommittedText = picked
        }
        // 点选即结束：清掉候选（本窗口不再保留）
        lastSegCandidates = emptyList()
        publishToolboxCandidates()
    }

    /** 工具箱按钮（米系菜单项同义）。 */
    private fun onToolboxAction(action: StylusToolboxAction) {
        when (action) {
            StylusToolboxAction.Undo -> onUndo()
            StylusToolboxAction.Redo -> onRedo()
            StylusToolboxAction.Keyboard -> onKeyboard()
            StylusToolboxAction.Enter -> onEnter()
            StylusToolboxAction.Space -> onSpace()
            StylusToolboxAction.Backspace -> onBackspace()
        }
    }

    /**
     * 工具箱「唤起键盘」：**退出触控笔 UI、恢复键盘，并收起浮动手写栏**。
     *
     * 触控笔 UI 下键盘是被撤下的（米系 `setInputView(空锚点)`），因此这里必须
     * 先 `exitStylusUi()` 把键盘装回去，再结束系统手写会话；**浮动手写栏随之隐藏**
     * （键盘已唤起，手写栏不应再悬浮在键盘之上）。
     */
    private fun onKeyboard() {
        finalizeWindowInternal {
            service.exitStylusUi()
            setToolboxVisible(false)
            runCatching { service.finishStylusHandwriting() }
        }
    }

    /**
     * 关闭浮动手写栏（浮动手写栏已无「收起」按钮，由手指点击 / 点击非书写区域触发）。
     */
    private fun requestClose() {
        finalizeWindowInternal {
            service.exitStylusUi()
            setToolboxVisible(false)
            runCatching { service.finishStylusHandwriting() }
        }
    }

    /**
     * 切换/清空前：把窗口里已写的字补交上屏，再清窗（米系 `onFinishStylusHandwriting` 的补交同义）。
     *
     * 文字识别要离开主线程，故 [after] 在提交完成后再执行，保证「先上屏已写的字，再做动作」
     * 的顺序（否则点空格/回车会把刚写的墨迹连字一起清掉）。
     *
     * **同步取走墨迹并清窗**：异步识别期间若又落笔，新笔应进**新窗口**，
     * 而不是被后续清窗一起抹掉；也顺带取消停顿计时，避免同一段墨迹被提交两次。
     *
     * @param after 提交完成后的动作
     */
    private fun finalizeWindowInternal(after: (() -> Unit)? = null) {
        val strokes = inkView.snapshot()
        resetWindow()
        if (strokes.isEmpty()) {
            after?.invoke()
            return
        }
        scope.launch {
            val candidates = withContext(Dispatchers.Default) { recognizeWholeInk(strokes) }
            val text = candidates.firstOrNull()?.char
            if (!text.isNullOrEmpty()) {
                service.commitText(text)
                lastCommittedText = text
                lastSegCandidates = candidates
                publishToolboxCandidates()
            }
            after?.invoke()
        }
    }

    /** 丢弃书写窗口（不提交）：换框/新会话时旧框的未完成笔画不应落到新框。 */
    private fun discardWindow() {
        resetWindow()
        // 进行中的那一笔也属于旧框：显式丢弃（clearWindow 保留它是为了「提交后继续写」的场景）
        inkView.cancelCurrentStroke()
    }

    /** 清空书写窗口与相关状态（是否提交由调用方决定）。 */
    private fun resetWindow() {
        lastCommittedText = ""
        clearWindowState()
        idleJob?.cancel()
    }

    /**
     * 清识别窗口 + 重置手势判定（**所有清窗点必须走这里**）。
     *
     * 手势是**单笔**：一次「落笔 → 识别 → 上屏」循环结束后，下一笔应重新成为手势候选
     * （米系 `resetRecognizeManager()` → `InkRecognizeManager.reset()` 每会话结束时把
     * `mIsGesturing` 置回 true / 讯飞每会话重置 `mCurrentState`）。
     * 若只清墨迹而不重置，`gestureDownCount` 会跨多次上屏一路累加，
     * 第 2 笔之后 [GESTURE_WRITING] 永久成立 ⇒ **手势再也判不出来**。
     */
    private fun clearWindowState() {
        inkView.clearWindow()
        resetGestureState()
    }

    /** 空格：先固化活动区（避免空格插到活动字中间），再上屏空格。 */
    private fun onSpace() {
        finalizeWindowInternal { service.commitText(" ") }
    }

    /** 回车：走 IME 自己的回车逻辑（编辑器 action / 换行与主键盘同口径）。 */
    private fun onEnter() {
        finalizeWindowInternal { service.handleReturnKeyForStylus() }
    }

    /**
     * ⌫：后退删除。
     *
     * **有候选词时先清候选**（这一下不删字，符合「候选还在 ⇒ 先收起候选」的直觉）；
     * 没有候选才按顺序：窗口里还留着没提交的笔画 → 丢弃它们（撤销这一笔）
     * → 否则删除最近提交的那一个字 → 再没有则退格一格。
     */
    private fun onBackspace() {
        if (lastSegCandidates.isNotEmpty()) {
            lastSegCandidates = emptyList()
            publishToolboxCandidates()
            return
        }
        if (inkView.strokeCount > 0) {
            clearWindowState()
        } else if (lastCommittedText.isNotEmpty()) {
            // 按字符数删除上一次提交的内容（多为单字）
            service.deleteBeforeCursor(lastCommittedText.length)
            lastCommittedText = ""
        } else {
            service.deleteBeforeCursor(1)
        }
        idleJob?.cancel()
    }

    /** 撤销：Ctrl+Z（米系随手写同款 KeyEvent 54 + META_CTRL）。 */
    private fun onUndo() {
        finalizeWindowInternal { service.sendCombinationKeyEvents(KeyEvent.KEYCODE_Z, ctrl = true) }
    }

    /** 重做：Ctrl+Y（米系随手写同款 KeyEvent 53 + META_CTRL）。 */
    private fun onRedo() {
        finalizeWindowInternal { service.sendCombinationKeyEvents(KeyEvent.KEYCODE_Y, ctrl = true) }
    }

    /** ✕：请求系统结束会话（随后回调 `onFinishStylusHandwriting` → [finish]）。 */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun requestFinish() {
        finalizeWindowInternal()
        runCatching { service.finishStylusHandwriting() }
    }

    // ------------------------------------------------------------------
    // 一笔手势（系统引擎优先；不可用时退回本地几何启发式）
    // ------------------------------------------------------------------

    /**
     * 笔画完成回调（主线程）：返回 true 表示本笔已被手势消费、不进识别窗口。
     *
     * **一律返回 false**（本笔先正常进识别窗口）：米系 `MiuiHandWritingIMEStylus` 的手势判定
     * 不在落笔时做，而是在**会话结束**（`onFinishStylusHandwriting` → `getGestureRecognizeResult`）
     * 对最后一笔判一次，然后二选一：是手势 → `performHandwritingGesture`；否 → 文字识别。
     * 因此这里只让笔画入窗，手势交由 [scheduleIdleFinalize] 的会话结束逻辑处理 ——
     * 这样手势判定与文字识别后端完全解耦（系统引擎或自带 ONNX 都用同一套）。
     */
    private fun onStrokeFinished(stroke: InkStroke): Boolean {
        return false
    }

    /**
     * 本地几何启发式手势（系统引擎不可用时的手势来源）。
     *
     * 与系统引擎路径共用同一套屏幕坐标语义：入参 [points] 已是屏幕坐标。
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun performLocalGesture(kind: HandwritingStrokeKind, points: List<StrokePoint>) {
        val ic = service.currentInputConnection ?: return
        val box = HandwritingStrokeFx.boxOf(points)
        val rect = RectF(box.minX, box.minY, box.maxX, box.maxY)
        val executor = ContextCompat.getMainExecutor(service)
        when (kind) {
            HandwritingStrokeKind.Newline -> sendGesture(
                ic, executor, "\n",
                InsertGesture.Builder()
                    .setTextToInsert("\n")
                    .setInsertionPoint(PointF(rect.centerX(), rect.bottom))
                    .setFallbackText("\n")
                    .build(),
            )

            HandwritingStrokeKind.Insert -> sendGesture(
                ic, executor, " ",
                InsertGesture.Builder()
                    .setTextToInsert(" ")
                    .setInsertionPoint(PointF(rect.centerX(), rect.centerY()))
                    .setFallbackText(" ")
                    .build(),
            )

            HandwritingStrokeKind.Delete, HandwritingStrokeKind.Select -> scope.launch {
                // 回落文本 = 该笔被当成字符时的识别结果（编辑器不支持手势时提交它）
                val fallback = withContext(Dispatchers.Default) {
                    runCatching {
                        HandwritingEngine.predict(listOf(points)).firstOrNull()?.char.orEmpty()
                    }.getOrDefault("")
                }
                val gesture = if (kind == HandwritingStrokeKind.Delete) {
                    DeleteGesture.Builder()
                        .setGranularity(DeleteGesture.GRANULARITY_CHARACTER)
                        .setDeletionArea(rect)
                        .setFallbackText(fallback)
                        .build()
                } else {
                    SelectGesture.Builder()
                        .setGranularity(SelectGesture.GRANULARITY_CHARACTER)
                        .setSelectionArea(rect)
                        .setFallbackText(fallback)
                        .build()
                }
                sendGesture(ic, executor, fallback, gesture)
            }

            HandwritingStrokeKind.Character -> Unit
        }
    }

    /**
     * 交编辑器执行手势，并按结果回落。
     *
     * 编辑器返回值语义（[InputConnection] 常量）：
     * - `SUCCESS`(1) / `CANCELLED`(4) = 已处理；
     * - `FALLBACK`(5) = 手势失败但**已提交 `getFallbackText()`** —— 引擎给的 gesture 回落文本
     *   是空串，此时等于什么都没做，必须补上动作；
     * - `UNSUPPORTED`(2) / `FAILED`(3) / `UNKNOWN`(0) = 没做成，由本方法本地补上动作。
     *
     * @param onNotHandled 手势未被真正执行且无回落文本时的补救（按类型做本端等价动作）
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun sendGesture(
        ic: android.view.inputmethod.InputConnection,
        executor: java.util.concurrent.Executor,
        fallbackText: String,
        gesture: HandwritingGesture,
        onNotHandled: (() -> Unit)? = null,
    ) {
        val consumer = IntConsumer { result ->
            Timber.d("stylus handwriting: gesture result=%d", result)
            val handled = result == android.view.inputmethod.InputConnection.HANDWRITING_GESTURE_RESULT_SUCCESS ||
                    result == android.view.inputmethod.InputConnection.HANDWRITING_GESTURE_RESULT_CANCELLED
            // FALLBACK 时编辑器已提交 gesture 自带的回落文本；引擎给的是空串 ⇒ 视为未处理
            val gestureFallback = gesture.fallbackText
            val committedFallback = result ==
                    android.view.inputmethod.InputConnection.HANDWRITING_GESTURE_RESULT_FALLBACK &&
                    !gestureFallback.isNullOrEmpty()
            if (handled || committedFallback) return@IntConsumer

            val text = gestureFallback ?: fallbackText
            if (text == "\n") {
                service.sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            } else if (text.isNotEmpty()) {
                service.commitText(text)
            } else {
                // 无回落文本可提交：手势确实没做成，按类型做本端等价动作
                onNotHandled?.invoke()
            }
        }
        runCatching { ic.performHandwritingGesture(gesture, executor, consumer) }

        // 插入模式是「长时手势」（米系 `isLongTimeProcessGesture`）：进入后保持进行中，
        // 3 秒无操作则以 CancellationSignal 取消（`resetGestureTimer` + `gestureFinish` 同款）。
        if (gesture is android.view.inputmethod.InsertModeGesture) {
            scheduleInsertModeCancel(gesture.cancellationSignal)
        }
    }

    /**
     * 插入模式手势的取消计时（米系 `mGestureTimer` = 3000ms）。
     *
     * 插入模式进入后编辑器会保持该状态，用户可直接书写插入内容；
     * 长时间无操作则取消，避免一直停留在插入态。
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun scheduleInsertModeCancel(signal: android.os.CancellationSignal?) {
        insertModeJob?.cancel()
        insertModeJob = scope.launch {
            delay(INSERT_MODE_TIMEOUT_MS)
            runCatching { signal?.cancel() }
            Timber.d("stylus handwriting: insert mode cancelled after timeout")
        }
    }

    /**
     * 系统手势未被编辑器执行时的回落：**用系统文字识别**把这一笔当字上屏。
     *
     * 米系 `MiuiHandWritingIMEStylus.lambda$onFinishStylusHandwriting$3` 同款：
     * ```java
     * if (actualResult != expectedResult) {          // 手势没成功
     *     mRecognizeManager.getRecognizeText(... submitText ...);
     * }
     * ```
     * 即失败手势的回落同样走系统引擎（`recognizeText`），不使用任何本地判定。
     */
    private fun fallbackToSystemRecognition(strokes: List<List<StrokePoint>>) {
        scope.launch {
            // 与文字路径同一套后端分派（系统引擎优先、不可用回落自带 ONNX）
            val candidates = withContext(Dispatchers.Default) { recognizeWholeInk(strokes) }
            val text = candidates.firstOrNull()?.char
            if (text.isNullOrEmpty()) {
                Timber.d("stylus handwriting: gesture unhandled and recognition empty")
                return@launch
            }
            Timber.d("stylus handwriting: gesture unhandled, recognized %s", text)
            service.commitText(text)
            lastCommittedText = text
            lastSegCandidates = candidates
            publishToolboxCandidates()
        }
    }

    // ------------------------------------------------------------------
    // 识别管线（与键盘手写布局同一套窗口固化规则）
    // ------------------------------------------------------------------

    private fun onStrokeCommitted() {
        scheduleIdleFinalize()
    }

    /**
     * 会话结束处理（米系 `onFinishStylusHandwriting` 同款）：**手势与文字二选一**。
     *
     * 米系流程：
     * ```java
     * mHandler.postDelayed(mTextEditTimer, 500);          // UP 后 500ms → finish()
     *   → getGestureRecognizeResult((status, gesture) -> {
     *         if (有手势) performHandwritingGesture(gesture, result -> {
     *                 if (result != SUCCESS) getRecognizeText(... submitText ...);
     *             });
     *         else getRecognizeText(... submitText ...);
     *     });
     * ```
     * 即：**先判最后一笔是不是手势**；是则执行（未成功再回落文字识别），
     * 否则把**整段累积墨迹**一次性出字。
     *
     * **与识别后端无关**：手势判定与文字识别都走同一条路径，
     * 系统引擎（小米随手写）不可用时由自带 ONNX 模型承担文字识别、由本地几何启发式承担手势。
     *
     * **阈值与系统会话超时对齐**（米系 `mTextEditTimer` 同款 500ms）：平台判定「落笔已停」的
     * 唯一信号就是「距最后一个事件 500ms」（每个事件都会重排该计时），这正是「一个字写完」的天然边界。
     *
     * **不依赖 [sessionActive]**：计时器跨系统会话存活，否则会话结束时提交会被丢掉。
     */
    private fun scheduleIdleFinalize() {
        idleJob?.cancel()
        val count = inkView.strokeCount
        if (count <= 0) return
        idleJob = scope.launch {
            delay(STYLUS_SETTLE_MS)
            // 期间又落了新笔：该笔有自己的计时，本轮作废
            if (inkView.strokeCount != count) return@launch
            // 正有一笔在写（已 DOWN 未 UP）：绝不能把「写了一半」的笔画当成写完提交，
            // 该笔 UP 时会重新排定计时
            if (inkView.hasActiveStroke()) return@launch
            // 手势优先；不是手势则整段墨迹一次性出字（米系二选一）
            if (tryConsumeAsGesture()) return@launch
            commitRecognition()
        }
    }

    /** 系统引擎是否正在使用（开关开启 + 已就绪）。 */
    private fun systemEngineInUse(): Boolean =
        prefs.handwritingSystemEngineEnabled.getValue() && systemEngineReady

    /**
     * 会话结束时把**整段累积墨迹**一次性出字并上屏
     * （米系 `getRecognizeText` → `submitText(str)` → `commitText(str, 1)` 分支）。
     *
     * 自带模型较弱，故**取前几名候选挂在浮动工具箱的候选行**供纠错；
     * 候选**识别完成后保留**（点选替换刚上屏的那个字），直到下次识别刷新或工具箱收起。
     * 墨迹层不出 chips。
     */
    private suspend fun commitRecognition() {
        val strokes = inkView.snapshot()
        clearWindowState()
        if (strokes.isEmpty()) return
        val candidates = withContext(Dispatchers.Default) { recognizeWholeInk(strokes) }
        if (candidates.isEmpty()) {
            Timber.d("stylus handwriting: session end, recognition empty (strokes=%d)", strokes.size)
            return
        }
        val text = candidates.first().char
        Timber.d(
            "stylus handwriting: session end, recognized %s (candidates=%s)",
            text, candidates.joinToString("") { it.char },
        )
        service.commitText(text)
        lastCommittedText = text
        lastSegCandidates = candidates
        publishToolboxCandidates()
    }

    /**
     * 整段墨迹 → 候选列表（前 [HANDWRITING_TOP_K] 个）。
     *
     * 系统引擎优先（**只给单结果**，候选表就一项）；不可用（非小米设备/开关关闭/已降级）时用
     * 自带 ONNX 模型，把整个窗口当作**一个字**送模型取前几名（模型是单字分类器，无 CTC、不做多字解码）。
     *
     * **连续空结果自降级**：万一引擎不服务本应用（白名单之外的兜底情况），
     * 连续 [SYSTEM_ENGINE_GIVE_UP] 次拿不到结果即改走 ONNX，避免每次都白跑反射。
     *
     * 在 [Dispatchers.Default] 上被调用，故可用同步版引擎初始化。
     */
    private suspend fun recognizeWholeInk(strokes: List<List<StrokePoint>>): List<HandwritingCandidate> {
        val systemEnabled = prefs.handwritingSystemEngineEnabled.getValue()
        if (systemEnabled && !systemEngineGaveUp && ensureSystemEngineBlocking()) {
            val text = XiaomiHandwritingEngine.recognizeText(strokes)
            if (!text.isNullOrEmpty()) {
                systemEngineEmptyStreak = 0
                return listOf(HandwritingCandidate(text, SYSTEM_ENGINE_SCORE))
            }
            systemEngineEmptyStreak++
            Timber.d(
                "stylus handwriting: system engine returned nothing (%d/%d), falling back to ONNX",
                systemEngineEmptyStreak, SYSTEM_ENGINE_GIVE_UP,
            )
            if (systemEngineEmptyStreak >= SYSTEM_ENGINE_GIVE_UP) {
                systemEngineGaveUp = true
                Timber.w(
                    "stylus handwriting: system engine gave up after %d empty results",
                    SYSTEM_ENGINE_GIVE_UP,
                )
            }
        }
        if (!HandwritingEngine.isReady) return emptyList()
        return HandwritingEngine.predict(strokes, HANDWRITING_TOP_K)
    }

    /**
     * 会话结束时把「最后一笔」当手势尝试执行（米系 `getGestureRecognizeResult` 分支）。
     *
     * 手势判定用**最后一笔**（米系 `mGestureStroke` 就是当前这一笔），
     * 但手势未成功时的回落用**整段累积墨迹**（米系 `getRecognizeText` 用 `mInkBuilder.build()`），
     * 否则本会话前面写的笔画会丢。
     *
     * @return true = 已按手势处理（调用方不应再走文字上屏）
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun tryConsumeAsGesture(): Boolean {
        // 米系 `A()` / `onFinishStylusHandwriting` 同款：结束时若已是手势候选，**再判一次**
        // 「路径 ∩ 可见文本行」；不成立则降级为书写（编辑器给了文本行却画在空白处 = 写字）。
        if (gestureState == GESTURE_GESTURE && !gesturePathIntersectsVisibleLines()) {
            gestureState = GESTURE_WRITING
        }
        if (gestureState != GESTURE_GESTURE) {
            Timber.d(
                "stylus handwriting: not a gesture stroke (state=%d, downCount=%d, lines=%d)",
                gestureState, gestureDownCount, visibleLineBounds.size,
            )
            return false
        }
        val all = inkView.snapshotScreen()
        val last = all.lastOrNull()?.takeIf { it.isNotEmpty() } ?: return false
        // 系统引擎可用 → 用引擎判手势；不可用（非小米设备/开关关闭/已降级）→ 本地几何启发式。
        // 两条路径产出**同一个 AOSP `HandwritingGesture` 类型**，下游执行/回落完全统一。
        if (!systemEngineInUse()) {
            val kind = HandwritingGestures.detect(last, service.resources.displayMetrics.widthPixels)
            if (kind == HandwritingStrokeKind.Character) {
                Timber.d("stylus handwriting: local heuristics say writing")
                return false
            }
            Timber.d("stylus handwriting: session end local gesture=%s", kind)
            clearWindowState()
            performLocalGesture(kind, last)
            return true
        }
        var gesture = withContext(Dispatchers.Default) {
            XiaomiHandwritingEngine.recognizeGesture(last)
        }
        if (gesture == null) {
            // 末段曾判出过手势（见 [tailGesture]）：沿用它，避免收笔噪声把整笔降级成文字
            val cached = tailGesture
            val minPoints = (last.size * GESTURE_TAIL_CACHE_RATIO).toInt()
            if (cached != null && tailGesturePointCount >= minPoints) {
                Timber.d(
                    "stylus handwriting: engine says writing at end, reuse tail gesture=%s (%d/%d points)",
                    cached.javaClass.simpleName, tailGesturePointCount, last.size,
                )
                gesture = cached
            }
        }
        if (gesture == null) {
            // 引擎判为普通书写（GestureType.WRITING/NONE，只解析 5 种手势）
            Timber.d("stylus handwriting: session end, engine says writing")
            return false
        }
        val ic = service.currentInputConnection ?: return false
        Timber.d(
            "stylus handwriting: session end gesture=%s at %s",
            gesture.javaClass.simpleName, describeGesture(gesture),
        )
        // 手势笔不参与文字识别，清窗
        clearWindowState()
        // 未真正执行时回落系统文字识别，用整段墨迹（米系 `getRecognizeText`）
        sendGesture(
            ic,
            ContextCompat.getMainExecutor(service),
            "",
            gesture,
            onNotHandled = { fallbackToSystemRecognition(all) },
        )
        return true
    }


    private companion object {
        /**
         * 会话结束延时（ms）：**米系 `mTextEditTimer` 同款 500ms**。
         *
         * 米系在 `ACTION_UP` 后 `postDelayed(mTextEditTimer, 500)` → `finish()` →
         * `onFinishStylusHandwriting` 里做「手势 or 文字」的二选一；这个值同时也是平台
         * 会话空闲超时（[SESSION_IDLE_TIMEOUT_MS]）——「距最后一个事件 500ms」正是
         * 「一个字写完」的天然边界。本计时**跨系统会话存活**（`finish()` 不取消它），
         * 否则提交永远等不到。
         */
        const val STYLUS_SETTLE_MS = 500L

        /**
         * 系统引擎连续返回空结果多少次后放弃（改走 ONNX）。
         *
         * 非白名单包名下引擎会「构造成功但恒返回空」；连续几次即可判定不值得再试。
         */
        const val SYSTEM_ENGINE_GIVE_UP = 3

        /** 自带 ONNX 模型返回的候选个数（工具箱候选行只显示这么多，卡片宽度有限）。 */
        const val HANDWRITING_TOP_K = 5

        /** 系统引擎只给文本、不给概率，补一个固定置信度作首选排序用。 */
        const val SYSTEM_ENGINE_SCORE = 0.95f

        /** 插入模式手势的无操作超时（ms）：米系 `mGestureTimer` 同款 3000ms。 */
        const val INSERT_MODE_TIMEOUT_MS = 3000L

        /** 手势判定：未定（米系 `mCurrentState == 0`）。 */
        const val GESTURE_UNDECIDED = 0

        /** 手势判定：**手势候选**（米系 `mCurrentState == 1` / `g.b == 1`）。 */
        const val GESTURE_GESTURE = 1

        /** 手势判定：**书写**（米系 `mCurrentState == 2`）——本窗口不再判手势。 */
        const val GESTURE_WRITING = 2

        /**
         * 末段手势缓存的生效下限（占最终笔画点数的比例）。
         *
         * 只有「笔画末段仍判为该手势」才沿用；中段一闪而过的手势不算，
         * 避免书写时偶发误判把整笔当手势执行。
         */
        const val GESTURE_TAIL_CACHE_RATIO = 0.8f

        /**
         * 系统引擎的会话结束延时（ms）：**米系 `mTextEditTimer` 同款 500ms**。
         *
         * 米系在 `ACTION_UP` 后 `postDelayed(mTextEditTimer, 500)` → `finish()` →
         * `onFinishStylusHandwriting` 里做「手势 or 文字」的二选一。
         */
        const val SYSTEM_ENGINE_SETTLE_MS = 500L

        /** 编辑框手写区域的判定余量（dp）：边界与实际落点常有小数像素误差。 */
        const val EDITOR_BOUNDS_MARGIN_DP = 8f

        /**
         * 手写会话空闲超时（ms）——**米系同款 500ms 短会话**
         * （`MiuiHandWritingIMEStylus.onCreate` → `doSetStylusHandwritingSessionTimeout(Duration.ofMillis(500))`，
         * 讯飞小米版同样 `setStylusHandwritingSessionTimeout(Duration.ofMillis(500L))`）。
         *
         * 短会话 + 每次落笔重新 `canStartStylusHandwriting` 是这份协议的正常工作方式：
         * 书写状态（活动区/识别窗口）由我们跨会话保留，故用户感知上是连续的。
         * 若设成系统上限（30s），会话迟迟不释放，写完一次后系统会因
         * `mHandwritingRequestId` 仍占用而忽略后续请求 —— 表现为「只能触发一次」。
         */
        const val SESSION_IDLE_TIMEOUT_MS = 500L
    }
}

/**
 * 原生墨迹视图：挂在系统墨迹窗口（`getStylusHandwritingWindow().setContentView`）上。
 *
 * 触控笔事件经 `onStylusHandwritingMotionEvent` 重写直接喂进 [feed]（不依赖窗口可见时机），
 * 自身不挂触摸监听（手指事件穿透给应用/键盘）。笔迹用压力调制笔宽；
 * 单点按下抬起视为点选（候选 chips / 右下角控制钮）。
 *
 * 笔画列表由主线程写（事件/清理）、识别协程读（[snapshot]），统一用 [lock] 同步。
 */
private class StylusInkView(
    context: android.content.Context,
    private val onStroke: () -> Unit,
    private val onTap: (StylusTapTarget) -> Unit,
    /** 笔画完成回调：返回 true = 已按一笔手势消费（不写入识别窗口）。 */
    private val onStrokeFinished: (InkStroke) -> Boolean,
    /** 手指点击回调（墨迹区上）：用于收起浮动手写栏。 */
    private val onFingerTap: () -> Unit = {},
) : View(context) {

    private val density = context.resources.displayMetrics.density

    private val lock = Any()

    /** 手指按下的位置（判定「点击」而非「拖动」）。 */
    private var fingerDownX = 0f
    private var fingerDownY = 0f

    /**
     * 手指事件：墨迹窗口是全屏且在最上层的，手指点击若不在这里消费就会「石沉大海」。
     * 浮动手写栏已无「收起」按钮，因此**手指在墨迹区点击即收起**（拖动不算）。
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val tool = event.getToolType(event.actionIndex)
        if (tool != MotionEvent.TOOL_TYPE_FINGER) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fingerDownX = event.rawX
                fingerDownY = event.rawY
            }

            MotionEvent.ACTION_UP -> {
                val slop = FINGER_TAP_SLOP_DP * density
                if (abs(event.rawX - fingerDownX) <= slop && abs(event.rawY - fingerDownY) <= slop) {
                    onFingerTap()
                }
            }
        }
        return true
    }

    /** 识别窗口笔画（时间序；头部不可变，固化从头部裁剪）。 */
    private val strokes = ArrayList<InkStroke>()

    private var current: InkStrokeBuilder? = null
    private var currentIsEraser = false

    /** 手势墨迹（短暂显示，不进识别窗口）。 */
    private var transient: InkStroke? = null

    /** 已固化前缀笔画数（渲染变淡）。 */
    private var donePrefix = 0

    private var chips: List<HandwritingCandidate> = emptyList()

    /** 本视图的屏幕位置缓存（事件坐标换算用，feed 每次刷新）。 */
    private val viewOrigin = IntArray(2)

    /** 点选命中区（onDraw 时重建）。 */
    private val chipRects = ArrayList<Pair<RectF, Int>>()
    private val buttonRects = ArrayList<Pair<RectF, StylusTapTarget>>()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    // ------------------------------------------------------------------
    // 触控笔事件（主线程）
    // ------------------------------------------------------------------

    fun feed(event: MotionEvent) {
        synchronized(lock) {
            // 事件坐标统一换算到本视图坐标系（Gboard 用视图间 Matrix 变换，同口径）：
            // - rawX/rawY 恒为屏幕坐标；应用内回放的转发事件其 X/Y 可能仍在宿主视图空间，
            //   故一律以 raw 为准；
            // - 历史点没有 raw 访问器，用「当点 raw−x」补偿同一事件内的视图变换；
            // - 再减去本视图的屏幕位置 —— 墨迹窗口的屏幕位置/内容 insets/父级偏移全部免疫。
            getLocationOnScreen(viewOrigin)
            val originX = viewOrigin[0].toFloat()
            val originY = viewOrigin[1].toFloat()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val tool = event.getToolType(event.actionIndex)
                    if (tool != MotionEvent.TOOL_TYPE_STYLUS && tool != MotionEvent.TOOL_TYPE_ERASER) return
                    currentIsEraser = tool == MotionEvent.TOOL_TYPE_ERASER
                    current = InkStrokeBuilder(originX, originY)
                    addPoint(event.rawX - originX, event.rawY - originY, event.eventTime, event.pressure)
                    invalidate()
                }

                MotionEvent.ACTION_MOVE -> {
                    val c = current ?: return
                    if (c.points.isEmpty()) return
                    // 历史采样补齐（批量合帧的 MOVE 事件），保证笔画保真
                    val dx = event.rawX - event.x
                    val dy = event.rawY - event.y
                    for (h in 0 until event.historySize) {
                        addPoint(
                            event.getHistoricalX(h) + dx - originX,
                            event.getHistoricalY(h) + dy - originY,
                            event.getHistoricalEventTime(h),
                            event.getHistoricalPressure(h),
                        )
                    }
                    addPoint(event.rawX - originX, event.rawY - originY, event.eventTime, event.pressure)
                    invalidate()
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val c = current ?: return
                    current = null
                    if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                        invalidate()
                        return
                    }
                    val stroke = InkStroke(c.points.toList(), c.pressures.toList(), c.originX, c.originY)
                    when {
                        currentIsEraser -> eraseStrokeLocked(stroke)
                        stroke.points.size == 1 -> {
                            // 单点：先判按钮/候选点选，未命中按落点成「点」笔画
                            val p = stroke.points[0]
                            val target = hitTestLocked(p.x, p.y)
                            if (target != null) {
                                onTap(target)
                            } else {
                                finishStrokeLocked(stroke)
                            }
                        }

                        else -> finishStrokeLocked(stroke)
                    }
                    invalidate()
                }
            }
        }
    }

    private fun addPoint(x: Float, y: Float, timeMs: Long, pressure: Float) {
        val c = current ?: return
        c.points.add(StrokePoint(x, y, timeMs))
        c.pressures.add(pressure)
    }

    private fun commitStrokeLocked(stroke: InkStroke) {
        if (stroke.points.isEmpty()) return
        strokes.add(stroke)
        onStroke()
    }

    /** 普通笔画完成：先给手势判定机会（消费掉就不进识别窗口）。 */
    private fun finishStrokeLocked(stroke: InkStroke) {
        if (stroke.points.isEmpty()) return
        if (onStrokeFinished(stroke)) return
        commitStrokeLocked(stroke)
    }

    /** 橡皮擦：擦除与擦痕相交的一条墨迹（从最新往回找，撤销该笔，不删文本）。 */
    private fun eraseStrokeLocked(eraser: InkStroke) {
        if (eraser.points.isEmpty()) return
        val eraserBox = HandwritingStrokeFx.boxOf(eraser.points)
        val tolerance = ERASE_TOUCH_GAP_DP * density
        for (i in strokes.indices.reversed()) {
            val box = HandwritingStrokeFx.boxOf(strokes[i].points)
            if (HandwritingStrokeFx.boxGap(box, eraserBox) <= tolerance) {
                strokes.removeAt(i)
                if (donePrefix > i) donePrefix--
                onStroke()
                return
            }
        }
    }

    // ------------------------------------------------------------------
    // 状态（控制器调用）
    // ------------------------------------------------------------------

    val strokeCount: Int
        get() = synchronized(lock) { strokes.size }

    /** 进行中笔画已采集的点（手势实时预览用；无进行中笔画时为 null）。 */
    fun currentPoints(): List<StrokePoint>? = synchronized(lock) {
        current?.points?.toList()?.takeIf { it.isNotEmpty() }
    }

    /** 进行中笔画已采集的点，**屏幕坐标**（手势识别用，理由见 [snapshotScreen]）。 */
    fun currentPointsScreen(): List<StrokePoint>? = synchronized(lock) {
        current?.let { c ->
            c.points.map { StrokePoint(it.x + c.originX, it.y + c.originY, it.timeMs) }
        }?.takeIf { it.isNotEmpty() }
    }

    /** 是否正有一笔在写（已 DOWN、尚未 UP/CANCEL）。 */
    fun hasActiveStroke(): Boolean = synchronized(lock) { current != null }

    fun snapshot(): List<List<StrokePoint>> = synchronized(lock) {
        strokes.map { it.points.toList() }
    }

    /**
     * 已完成笔画，**屏幕坐标**（视图坐标 + 该笔捕获时的视图屏幕原点）。
     *
     * 手势识别必须用屏幕坐标：引擎把笔画坐标直接填进 `HandwritingGesture`
     * （`GestureParserUtils.parsePointToRectF`），而 AOSP 规定手势区域/点一律是
     * **screen coordinates**（`SelectGesture.getSelectionArea()` 文档）。
     * 墨迹窗口不在屏幕原点时（多窗口/分屏），用视图坐标会让编辑器在错误位置找文字
     * ⇒ `HANDWRITING_GESTURE_RESULT_FAILED`。文字识别不受影响（引擎按包围盒归一化渲染）。
     */
    fun snapshotScreen(): List<List<StrokePoint>> = synchronized(lock) {
        strokes.map { s -> s.points.map { StrokePoint(it.x + s.originX, it.y + s.originY, it.timeMs) } }
    }

    /**
     * 清空已完成的笔画（提交完成后调用）。
     *
     * **保留正在写的那一笔**（[current]）：提交发生在抬笔之后，若此时笔又落下，
     * 这一笔属于**下一个字**；把它一起清掉会让该笔的采样点在 UP 时因 `current == null`
     * 被整笔丢弃。需要连进行中笔画一起丢的场景（换框/新会话/销毁）另调 [cancelCurrentStroke]。
     */
    fun clearWindow() = synchronized(lock) {
        strokes.clear()
        transient = null
        donePrefix = 0
        invalidate()
    }

    /** 手势墨迹：短暂显示后由控制器清除。 */
    fun showTransient(stroke: InkStroke) = synchronized(lock) {
        transient = stroke
        invalidate()
    }

    fun clearTransient() = synchronized(lock) {
        if (transient == null) return
        transient = null
        invalidate()
    }

    /** 会话结束时丢弃未完成的笔画（该笔不会再收到 UP）。 */
    fun cancelCurrentStroke() = synchronized(lock) {
        if (current == null) return
        current = null
        invalidate()
    }

    fun trimFront(k: Int) = synchronized(lock) {
        repeat(k) { if (strokes.isNotEmpty()) strokes.removeAt(0) }
        donePrefix = 0
        invalidate()
    }

    fun setDonePrefix(n: Int) = synchronized(lock) {
        if (donePrefix == n) return
        donePrefix = n
        invalidate()
    }

    fun showChips(candidates: List<HandwritingCandidate>) = synchronized(lock) {
        chips = candidates
        chipRects.clear()
        invalidate()
    }

    private fun hitTestLocked(x: Float, y: Float): StylusTapTarget? {
        for ((rect, index) in chipRects) {
            if (rect.contains(x, y)) return StylusTapTarget.Chip(index)
        }
        return null
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val color = ThemeManager.activeTheme.keyTextColor
        synchronized(lock) {
            val fade = donePrefix.coerceIn(0, strokes.size)
            for (i in 0 until fade) drawInk(canvas, strokes[i], withAlpha(color, INK_FADE_ALPHA))
            for (i in fade until strokes.size) drawInk(canvas, strokes[i], color)
            transient?.let { drawInk(canvas, it, withAlpha(color, TRANSIENT_INK_ALPHA)) }
            current?.let {
                drawInk(
                    canvas,
                    InkStroke(it.points.toList(), it.pressures.toList(), it.originX, it.originY),
                    color,
                )
            }
        }
        drawChips(canvas, color)
    }

    /** 压力调制笔宽（快慢平滑同键盘手写布局）。 */
    private fun drawInk(canvas: Canvas, stroke: InkStroke, color: Int) {
        if (stroke.points.isEmpty()) return
        paint.color = color
        if (stroke.points.size == 1) {
            fillPaint.color = color
            val pressure = stroke.pressures.firstOrNull() ?: 1f
            val r = (INK_MIN_WIDTH_DP + (INK_MAX_WIDTH_DP - INK_MIN_WIDTH_DP) * pressure) * density / 2f
            canvas.drawCircle(stroke.points[0].x, stroke.points[0].y, r, fillPaint)
            return
        }
        var lastWidth = INK_MAX_WIDTH_DP * density
        for (i in 1 until stroke.points.size) {
            val p0 = stroke.points[i - 1]
            val p1 = stroke.points[i]
            val pressure = stroke.pressures.getOrElse(i) { 1f }
            val width = (INK_MIN_WIDTH_DP + (INK_MAX_WIDTH_DP - INK_MIN_WIDTH_DP) * pressure) *
                    density * 0.35f + lastWidth * 0.65f
            lastWidth = width
            paint.strokeWidth = width
            canvas.drawLine(p0.x, p0.y, p1.x, p1.y, paint)
        }
    }

    /** 候选 chips（顶栏居中）：点选替换当前正在写的字。 */
    private fun drawChips(canvas: Canvas, color: Int) {
        if (chips.isEmpty()) return
        val shown = chips.take(MAX_CHIPS)
        val w = CHIP_WIDTH_DP * density
        val h = CHIP_HEIGHT_DP * density
        val gap = CHIP_GAP_DP * density
        val total = shown.size * w + (shown.size - 1) * gap
        var x = (width - total) / 2f
        val y = CHIP_TOP_DP * density
        chipRects.clear()
        fillPaint.color = withAlpha(color, CHIP_BG_ALPHA)
        textPaint.color = color
        textPaint.textSize = CHIP_TEXT_DP * density
        shown.forEachIndexed { index, candidate ->
            val rect = RectF(x, y, x + w, y + h)
            chipRects.add(rect to index)
            canvas.drawRoundRect(rect, h / 2f, h / 2f, fillPaint)
            val baseline = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(candidate.char, rect.centerX(), baseline, textPaint)
            x += w + gap
        }
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or ((alpha * 255f).toInt().coerceIn(0, 255) shl 24)

    private companion object {
        const val INK_MIN_WIDTH_DP = 2.5f
        const val INK_MAX_WIDTH_DP = 10f
        const val INK_FADE_ALPHA = 0.35f

        /** 手指「点击」（用于收起浮动手写栏）的最大位移（dp）；超过即视为拖动。 */
        const val FINGER_TAP_SLOP_DP = 12f

        /** 手势墨迹（短暂显示）的透明度。 */
        const val TRANSIENT_INK_ALPHA = 0.5f

        /** 橡皮擦擦除的相交判定容差（dp）。 */
        const val ERASE_TOUCH_GAP_DP = 8f

        const val CHIP_WIDTH_DP = 48f
        const val CHIP_HEIGHT_DP = 40f
        const val CHIP_GAP_DP = 8f
        const val CHIP_TOP_DP = 16f
        const val CHIP_TEXT_DP = 22f
        const val CHIP_BG_ALPHA = 0.18f
        const val MAX_CHIPS = 5

        const val BUTTON_RADIUS_DP = 20f
        const val BUTTON_MARGIN_DP = 14f
        const val BUTTON_GAP_DP = 10f
        const val BUTTON_BG_ALPHA = 0.18f
    }
}
