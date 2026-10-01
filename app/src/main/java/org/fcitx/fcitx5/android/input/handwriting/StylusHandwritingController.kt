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
 * 识别复用与键盘手写布局同一套管线（`HandwritingSegmenter` + `HandwritingEngine` +
 * `HandwritingStrokeFx` 窗口固化规则），上屏走 `FcitxInputMethodService.replaceBeforeCursor`
 * 活动区替换式上屏。与键盘布局不同：墨迹窗口盖住应用、没有常驻候选栏，
 * 因此**始终边写边上屏**（不读 `handwritingAutoCommit`），错字用窗口内候选 chips / 退格修正。
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.data.handwriting.HandwritingCandidate
import org.fcitx.fcitx5.android.data.handwriting.HandwritingEngine
import org.fcitx.fcitx5.android.data.handwriting.HandwritingGestures
import org.fcitx.fcitx5.android.data.handwriting.HandwritingMarketCategory
import org.fcitx.fcitx5.android.data.handwriting.HandwritingSegmenter
import org.fcitx.fcitx5.android.data.handwriting.HandwritingStrokeFx
import org.fcitx.fcitx5.android.data.handwriting.HandwritingStrokeKind
import org.fcitx.fcitx5.android.data.handwriting.StrokePoint
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
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

    // 触控笔模式：字内刻意提笔的时间信号不可靠，切分阈值整体上调（见 HandwritingSegmenter.stylusMode）
    private val recognizer = HandwritingSegmenter(stylusMode = true) { s, k -> HandwritingEngine.predict(s, k) }

    private var loadJob: Job? = null
    private var recognizeJob: Job? = null
    private var idleJob: Job? = null
    private var transientJob: Job? = null

    /** 会话进行中（`onStartStylusHandwriting` 返回 true 之后）。 */
    @Volatile
    private var sessionActive = false

    /** 屏上活动区文本（光标前紧邻）：下一次识别整体替换它。 */
    private var active = ""

    /** 当前正在写的字（最后一段首选字）：候选 chips 点选替换它。 */
    private var lastSegText = ""

    /** 候选 chips 数据（最后一段的候选）。 */
    private var lastSegCandidates: List<HandwritingCandidate> = emptyList()

    private val inkView by lazy {
        StylusInkView(
            service,
            onStroke = ::onStrokeCommitted,
            onTap = ::onTap,
            onStrokeFinished = ::onStrokeFinished,
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

    /** `onPrepareStylusHandwriting`：预热识别模型（轻量；不保证随后一定启动会话）。 */
    fun prepare() {
        if (!isEnabled()) return
        if (HandwritingEngine.isReady) return
        ensureModelLoaded()
    }

    /**
     * `onUpdateEditorToolType(TOOL_TYPE_STYLUS)`：**米系同款的进入时机**。
     *
     * 米系在检测到触控笔（而非等手写会话开始）就切到触控笔界面：撤下键盘 + 显示浮动工具箱。
     * 这样触控笔点键盘区域时不会与键盘手势冲突（键盘已不在），也无需事件转发。
     * 只做界面切换与预热，**不启动系统手写会话**（那是落笔时 `onStartStylusHandwriting` 的事）。
     */
    fun onToolStylus() {
        if (!isEnabled()) return
        if (!HandwritingEngine.isReady) {
            ensureModelLoaded()
            return
        }
        // 撤下键盘 + 显示浮动工具箱（米系 setStylusMode(true) 的效果）
        service.enterStylusUi()
        setToolboxVisible(prefs.stylusToolboxEnabled.getValue())
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
        if (!HandwritingEngine.isReady) {
            ensureModelLoaded()
            return false
        }
        val window = runCatching { service.getStylusHandwritingWindow() }.getOrNull() ?: return false
        sessionActive = true
        ensureInkAttached(window)
        // 工具箱挂到 IME 窗口之上（米系做法），随会话显隐
        setToolboxVisible(prefs.stylusToolboxEnabled.getValue())
        // custom: 进入触控笔 UI —— 撤下键盘（米系 `setInputView(空锚点)` 等价做法）
        service.enterStylusUi()
        // custom: **主动保持 IME 窗口可见**（米系 `ImeMenuViewHolder.show()` 末尾的
        // `requestShowInputView()` → `BaseStylusInputMethodService.requestShowSelf(0)`）。
        // 浮动工具箱挂在 IME 窗口上，若 IME 窗口被系统隐藏，工具箱/候选都会跟着消失
        // （实测「手写时悬浮键盘不显示」）。米系即使融笔模式不对 SDK<36 也补这一下。
        runCatching { service.forceShowSelf() }
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
                ?: return
            toolboxWindow.show(decor)
        } else {
            toolboxWindow.hide()
        }
    }

    /** 配置变化：浮窗重算安全区并夹取位置。 */
    fun onConfigurationChanged() {
        toolboxWindow.onConfigurationChanged()
    }

    /** `onStylusHandwritingMotionEvent`：触控笔事件（主线程）。 */
    fun onMotionEvent(event: MotionEvent) {
        if (!sessionActive) return
        inkView.feed(event)
    }

    /**
     * `onFinishStylusHandwriting`：**一次书写会话**结束（系统 500ms 空闲超时 / 主动请求）。
     *
     * 米系同款：**不在此处移除工具箱**（`MiuiHandWritingIMEStylus.onFinishStylusHandwriting`
     * 只移除墨迹 View）——因为会话是 500ms 短会话，用户写字停顿就会结束一次会话，
     * 若跟着移除工具箱，工具栏会随每次停顿闪烁消失。工具箱只在
     * [onInputViewFinished]（输入视图结束）与 [release]（服务销毁）时移除。
     *
     * 幂等：框架可在 `onFinishInput` 等路径结束会话，可能不经过 [start]。
     */
    fun finish() {
        sessionActive = false
        // 只清临时态与在写笔画，**保留活动区文本与工具箱**：
        // 会话是「一次书写」级别，用户停顿后再写应能继续替换上一段。
        inkView.cancelCurrentStroke()
        transientJob?.cancel()
        recognizeJob?.cancel()
        idleJob?.cancel()
    }

    /** 输入视图结束（`onFinishInputView`）：退出触控笔 UI、移除工具箱（米系在此处 `hide()`）。 */
    fun onInputViewFinished() {
        finish()
        setToolboxVisible(false)
        service.exitStylusUi()
    }

    /** IME 销毁：释放会话、工具箱与协程（不调用系统方法）。 */
    fun release() {
        finish()
        setToolboxVisible(false)
        service.exitStylusUi()
        scope.cancel()
    }

    private fun isEnabled(): Boolean = prefs.handwritingInputEnabled.getValue()

    private fun ensureModelLoaded() {
        if (loadJob?.isActive == true) return
        val modelId = prefs.handwritingModelId.getValue()
        if (HandwritingEngine.isReady && HandwritingEngine.loadedModel() == modelId) return
        loadJob = scope.launch {
            if (!HandwritingMarketCategory.isReady(service, modelId)) return@launch
            HandwritingEngine.load(service, modelId)
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
            is StylusTapTarget.Chip -> onChipTap(target.index)
        }
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
            StylusToolboxAction.Close -> requestClose()
        }
    }

    /**
     * 工具箱「键盘」：**退出触控笔 UI、恢复键盘**（米系 `switchKeyboardByStylus` 语义）。
     *
     * 触控笔 UI 下键盘是被撤下的（米系 `setInputView(空锚点)`），因此这里必须
     * 先 `exitStylusUi()` 把键盘装回去，再结束系统手写会话。
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun onKeyboard() {
        finalizeWindowInternal()
        service.exitStylusUi()
        runCatching { service.finishStylusHandwriting() }
    }

    /** 关闭：同「键盘」（都回到普通键盘界面）。 */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun requestClose() {
        finalizeWindowInternal()
        service.exitStylusUi()
        runCatching { service.finishStylusHandwriting() }
    }

    /** ✓/切换前：固化活动区并清窗（清候选 chips），下一个字从空白开始。 */
    private fun finalizeWindowInternal() {
        active = ""
        lastSegText = ""
        publishChips(emptyList())
        inkView.clearWindow()
        recognizer.reset()
    }

    /** 空格：先固化活动区（避免空格插到活动字中间），再上屏空格。 */
    private fun onSpace() {
        finalizeWindowInternal()
        service.commitText(" ")
    }

    /** 回车：走 IME 自己的回车逻辑（编辑器 action / 换行与主键盘同口径）。 */
    private fun onEnter() {
        finalizeWindowInternal()
        service.handleReturnKeyForStylus()
    }

    /** ⌫：后退删除。活动区非空时整体退格（通常 1–2 字），否则退格上一个已固化字；清窗重来。 */
    private fun onBackspace() {
        if (active.isNotEmpty()) {
            service.deleteBeforeCursor(active.length)
            active = ""
        } else {
            service.deleteBeforeCursor(1)
        }
        lastSegText = ""
        publishChips(emptyList())
        inkView.clearWindow()
        recognizer.reset()
        recognizeJob?.cancel()
        idleJob?.cancel()
    }

    /** 撤销：Ctrl+Z（米系随手写同款 KeyEvent 54 + META_CTRL）。 */
    private fun onUndo() {
        finalizeWindowInternal()
        service.sendCombinationKeyEvents(KeyEvent.KEYCODE_Z, ctrl = true)
    }

    /** 重做：Ctrl+Y（米系随手写同款 KeyEvent 53 + META_CTRL）。 */
    private fun onRedo() {
        finalizeWindowInternal()
        service.sendCombinationKeyEvents(KeyEvent.KEYCODE_Y, ctrl = true)
    }

    /** ✕：请求系统结束会话（随后回调 `onFinishStylusHandwriting` → [finish]）。 */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun requestFinish() {
        finalizeWindowInternal()
        runCatching { service.finishStylusHandwriting() }
    }

    /**
     * 候选 chips 点选：替换当前正在写的字（或已固化的最后一个字）。
     *
     * 语义与键盘手写布局的 `pickCandidate` 边写边上屏分支一致：首选已自动上屏、
     * 活动区内整体替换、活动区固化后直接替换屏上最后一个字。点选后固化（清窗），
     * chips 保留供继续点选纠错。
     */
    private fun onChipTap(index: Int) {
        val picked = lastSegCandidates.getOrNull(index)?.char
        if (picked.isNullOrEmpty()) return
        when {
            index <= 0 -> Unit // 首选已自动上屏，点选只固化
            active.isNotEmpty() && lastSegText.isNotEmpty() &&
                    active.length >= lastSegText.length -> {
                if (applyActive(active.dropLast(lastSegText.length) + picked)) lastSegText = picked
            }

            lastSegText.isNotEmpty() -> {
                if (service.replaceBeforeCursor(lastSegText, picked)) lastSegText = picked
            }

            else -> {
                if (applyActive(active + picked)) lastSegText = picked
            }
        }
        active = ""
        inkView.clearWindow()
        recognizer.reset()
    }

    // ------------------------------------------------------------------
    // 一笔手势（涂改删除 / 圈选 / 回车钩 / 插入尖角）
    // ------------------------------------------------------------------

    /**
     * 笔画完成回调（主线程）：命中手势则由本控制器消费（不写入识别窗口）。
     *
     * 手势判定用几何启发式（识别模型只有单字类别），命中后构造标准
     * `HandwritingGesture` 交编辑器执行（坐标屏幕坐标），编辑器不支持时本地回落。
     */
    private fun onStrokeFinished(stroke: InkStroke): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        val kind = HandwritingGestures.detect(
            stroke.points,
            service.resources.displayMetrics.widthPixels,
        )
        if (kind == HandwritingStrokeKind.Character) return false
        Timber.d("stylus handwriting: gesture=%s strokes=%d", kind, stroke.points.size)
        // 手势墨迹短暂显示（不算书写笔画，不改活动区）
        inkView.showTransient(stroke)
        transientJob?.cancel()
        transientJob = scope.launch {
            delay(GESTURE_INK_LINGER_MS)
            inkView.clearTransient()
        }
        performGesture(kind, stroke)
        return true
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun performGesture(kind: HandwritingStrokeKind, stroke: InkStroke) {
        val ic = service.currentInputConnection ?: return
        val box = HandwritingStrokeFx.boxOf(stroke.points)
        // HandwritingGesture 的区域/点一律屏幕坐标：视图坐标 + 捕获时的视图屏幕原点
        val rect = RectF(
            box.minX + stroke.originX,
            box.minY + stroke.originY,
            box.maxX + stroke.originX,
            box.maxY + stroke.originY,
        )
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
                val fallback = runCatching {
                    HandwritingEngine.predict(listOf(stroke.points)).firstOrNull()?.char.orEmpty()
                }.getOrDefault("")
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
     * 编辑器返回值语义（[InputConnection] 常量）：SUCCESS = 已执行；FALLBACK = 已提交回落文本；
     * UNSUPPORTED / FAILED / UNKNOWN = 没做成（默认实现就是 UNSUPPORTED），此时由本方法本地补上动作。
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun sendGesture(
        ic: android.view.inputmethod.InputConnection,
        executor: java.util.concurrent.Executor,
        fallbackText: String,
        gesture: HandwritingGesture,
    ) {
        val consumer = IntConsumer { result ->
            Timber.d("stylus handwriting: gesture result=%d", result)
            val done = result == android.view.inputmethod.InputConnection.HANDWRITING_GESTURE_RESULT_SUCCESS ||
                    result == android.view.inputmethod.InputConnection.HANDWRITING_GESTURE_RESULT_FALLBACK ||
                    result == android.view.inputmethod.InputConnection.HANDWRITING_GESTURE_RESULT_CANCELLED
            if (!done) {
                val text = gesture.fallbackText ?: fallbackText
                if (text == "\n") {
                    service.sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                } else if (text.isNotEmpty()) {
                    service.commitText(text)
                }
            }
        }
        runCatching { ic.performHandwritingGesture(gesture, executor, consumer) }
    }

    // ------------------------------------------------------------------
    // 识别管线（与键盘手写布局同一套窗口固化规则）
    // ------------------------------------------------------------------

    private fun onStrokeCommitted() {
        scheduleIdleFinalize()
        scheduleRecognition()
    }

    /** 空闲清窗：停顿达阈值后固化活动区（文本已上屏），清窗；chips 保留。
     *
     * 触控笔思考停顿普遍 1–2s（字内/字间都远长于手指连写），
     * 清窗下限上调到 [STYLUS_IDLE_CLEAR_MS]，避免写到一半窗口被清、
     * 后半截被当成新字（「拆开单字为多字」的主要来源之一）。
     */
    private fun scheduleIdleFinalize() {
        idleJob?.cancel()
        val count = inkView.strokeCount
        if (count <= 0) return
        idleJob = scope.launch {
            val delayMs = max(HandwritingStrokeFx.splitPauseMs(count) + HW_CLEAR_IDLE_MS, STYLUS_IDLE_CLEAR_MS)
            delay(delayMs)
            if (!sessionActive) return@launch
            active = ""
            inkView.clearWindow()
            recognizer.reset()
        }
    }

    private fun scheduleRecognition() {
        recognizeJob?.cancel()
        recognizeJob = scope.launch {
            val self = coroutineContext[Job]
            var round = 0
            while (self?.isActive == true && round++ < MAX_SETTLE_ROUNDS) {
                val window = inkView.snapshot()
                if (window.isEmpty()) {
                    publishChips(emptyList())
                    return@launch
                }
                val gaps = HandwritingStrokeFx.windowGaps(window)
                val result = withContext(Dispatchers.Default) {
                    recognizer.recognize(window, gaps)
                }
                if (self?.isActive != true) return@launch
                val segments = result.segments
                publishChips(segments.lastOrNull()?.candidates ?: emptyList())
                if (segments.isEmpty()) return@launch

                val segText = segments.joinToString("") { it.candidates.firstOrNull()?.char.orEmpty() }
                val last = segments.last().candidates.firstOrNull()?.char.orEmpty()
                if (applyActive(segText)) lastSegText = last

                // 已完成前缀只做视觉变淡
                val done = HandwritingStrokeFx.settledStrokesBeforeCurrent(segments, gaps)
                inkView.setDonePrefix(done)

                // 固化：窗口超限，或段数饱和且有已完成前缀
                val over = HandwritingStrokeFx.isWindowOverLimit(window.size)
                val saturated = HandwritingStrokeFx.needsSettleOnSaturation(segments.size, done)
                if (!over && !saturated) return@launch
                val settleStrokes = if (over) segments.first().strokeCount else done
                if (settleStrokes <= 0) return@launch
                val settledText = segments
                    .filter { it.startStroke + it.strokeCount <= settleStrokes }
                    .mapNotNull { it.candidates.firstOrNull()?.char }
                    .joinToString("")
                if (settledText.isNotEmpty()) {
                    // 已固化的文本退出可替换区
                    active = if (active.length >= settledText.length) active.drop(settledText.length) else ""
                }
                inkView.trimFront(settleStrokes)
                recognizer.onStrokesTrimmed(settleStrokes)
            }
        }
    }

    /**
     * 替换式上屏：把屏上 [active] 整体换成 [newText]。
     *
     * 校验失败（用户移动过光标/文本被外部改动）时清空活动区并把画布一并清掉——
     * 否则窗口里的旧笔画会在下一轮识别时把旧字重复上屏。
     */
    private fun applyActive(newText: String): Boolean {
        if (active == newText) return true
        val ok = service.replaceBeforeCursor(active, newText)
        active = if (ok) newText else ""
        if (!ok) {
            lastSegText = ""
            publishChips(emptyList())
            inkView.clearWindow()
            recognizer.reset()
        }
        return ok
    }

    private fun publishChips(candidates: List<HandwritingCandidate>) {
        lastSegCandidates = candidates
        // 墨迹层 chips：仅在墨迹可见时可见（会随系统 InkWindow.hide 消失）
        inkView.showChips(candidates)
        // **候选同时挂到浮动工具箱**（独立浮窗、跨短会话常驻），避免「候选只在墨迹显示时才有」；
        // 点选走同一个 onChipTap。
        toolboxWindow.setCandidates(candidates.map { it.char }) { index -> onChipTap(index) }
    }

    private companion object {
        /** 空闲清窗延时（ms），与键盘手写布局同口径。 */
        const val HW_CLEAR_IDLE_MS = 300L

        /** 触控笔模式空闲清窗下限（ms）：字内思考停顿普遍 1–2s，低于它不清窗。 */
        const val STYLUS_IDLE_CLEAR_MS = 2000L

        /** 手势墨迹的留存时长（ms）：给用户「动作已被识别」的反馈。 */
        const val GESTURE_INK_LINGER_MS = 350L

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

        /** 单次识别任务里最多连续固化的轮数。 */
        const val MAX_SETTLE_ROUNDS = 8
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
) : View(context) {

    private val density = context.resources.displayMetrics.density

    private val lock = Any()

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

    fun snapshot(): List<List<StrokePoint>> = synchronized(lock) {
        strokes.map { it.points.toList() }
    }

    fun clearWindow() = synchronized(lock) {
        strokes.clear()
        current = null
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
