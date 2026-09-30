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
import android.graphics.RectF
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
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
import org.fcitx.fcitx5.android.data.handwriting.HandwritingMarketCategory
import org.fcitx.fcitx5.android.data.handwriting.HandwritingSegmenter
import org.fcitx.fcitx5.android.data.handwriting.HandwritingStrokeFx
import org.fcitx.fcitx5.android.data.handwriting.StrokePoint
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import timber.log.Timber

/** 墨迹视图命中目标（单点按下抬起视为点选）。 */
private sealed interface StylusTapTarget {
    object Done : StylusTapTarget

    object Backspace : StylusTapTarget

    object Close : StylusTapTarget

    data class Chip(val index: Int) : StylusTapTarget
}

/** 一条墨迹笔画：采样点（识别用）+ 每点压力（渲染笔宽用）。 */
private class InkStroke(val points: List<StrokePoint>, val pressures: List<Float>)

/** 进行中笔画的积累器。 */
private class InkStrokeBuilder {
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

    private val recognizer = HandwritingSegmenter { s, k -> HandwritingEngine.predict(s, k) }

    private var loadJob: Job? = null
    private var recognizeJob: Job? = null
    private var idleJob: Job? = null

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
        StylusInkView(service, onStroke = ::onStrokeCommitted, onTap = ::onTap)
    }

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
     * `onStartStylusHandwriting`：进入会话。
     *
     * 手写总开关关闭或模型未就绪时返回 false —— 系统把本次请求视为 no-op
     * （触控笔事件继续归应用）；模型就绪后用户的下一次落笔会再次触发请求。
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun start(): Boolean {
        if (!isEnabled()) return false
        if (sessionActive) return true
        if (!HandwritingEngine.isReady) {
            ensureModelLoaded()
            return false
        }
        val window = runCatching { service.getStylusHandwritingWindow() }.getOrNull() ?: return false
        sessionActive = true
        active = ""
        lastSegText = ""
        publishChips(emptyList())
        inkView.clearWindow()
        // Gboard（StylusModule.startStylusHandwritingInternal）同款挂载：墨迹窗口可能被框架
        // 重建/复用，仅当墨迹视图未挂载或挂在别的窗口时才重新挂载（先脱离旧父级），
        // 并以显式布局参数 setContentView
        if (!inkView.isAttachedToWindow || inkView.rootView !== window.decorView.rootView) {
            (inkView.parent as? ViewGroup)?.removeView(inkView)
            runCatching {
                window.setContentView(
                    inkView,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }
        }
        // 挂载后手动 measure/layout（Gboard 同款）：窗口尚未走布局时，
        // 首批笔画/点选的命中区（chips/按钮）也按全屏尺寸就位
        val metrics = service.resources.displayMetrics
        inkView.measure(
            View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY)
        )
        inkView.layout(0, 0, inkView.measuredWidth, inkView.measuredHeight)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // 空闲超时拉满（系统钳制上限），减少写字中途被系统结束会话
            // （getStylusHandwritingIdleTimeoutMax 是 Java static，须按类调用）
            runCatching {
                service.setStylusHandwritingSessionTimeout(
                    android.inputmethodservice.InputMethodService.getStylusHandwritingIdleTimeoutMax()
                )
            }
        }
        return true
    }

    /** `onStylusHandwritingMotionEvent`：触控笔事件（主线程）。 */
    fun onMotionEvent(event: MotionEvent) {
        if (!sessionActive) return
        inkView.feed(event)
    }

    /**
     * `onFinishStylusHandwriting`：会话结束（系统空闲超时 / 输入结束 / 主动请求）。
     *
     * 固化活动区：已上屏文本保留（退出可替换区），清窗清候选 chips；幂等。
     */
    fun finish() {
        if (!sessionActive) return
        sessionActive = false
        finalizeWindowInternal()
        recognizeJob?.cancel()
        idleJob?.cancel()
    }

    /** IME 销毁：释放协程与状态（不调用系统方法）。 */
    fun release() {
        if (sessionActive) finish()
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
            StylusTapTarget.Done -> finalizeWindowInternal()
            StylusTapTarget.Backspace -> onBackspace()
            StylusTapTarget.Close -> requestFinish()
            is StylusTapTarget.Chip -> onChipTap(target.index)
        }
    }

    /** ✓：固化活动区并清窗（清候选 chips），下一个字从空白开始。 */
    private fun finalizeWindowInternal() {
        active = ""
        lastSegText = ""
        publishChips(emptyList())
        inkView.clearWindow()
        recognizer.reset()
    }

    /** ⌫：撤销。活动区非空时整体退格（通常 1–2 字），否则退格上一个已固化字；清窗重来。 */
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
    // 识别管线（与键盘手写布局同一套窗口固化规则）
    // ------------------------------------------------------------------

    private fun onStrokeCommitted() {
        scheduleIdleFinalize()
        scheduleRecognition()
    }

    /** 空闲清窗：停顿达阈值后固化活动区（文本已上屏），清窗；chips 保留。 */
    private fun scheduleIdleFinalize() {
        idleJob?.cancel()
        val count = inkView.strokeCount
        if (count <= 0) return
        idleJob = scope.launch {
            delay(HandwritingStrokeFx.splitPauseMs(count) + HW_CLEAR_IDLE_MS)
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
        inkView.showChips(candidates)
    }

    private companion object {
        /** 空闲清窗延时（ms），与键盘手写布局同口径。 */
        const val HW_CLEAR_IDLE_MS = 300L

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
) : View(context) {

    private val density = context.resources.displayMetrics.density

    private val lock = Any()

    /** 识别窗口笔画（时间序；头部不可变，固化从头部裁剪）。 */
    private val strokes = ArrayList<InkStroke>()

    private var current: InkStrokeBuilder? = null
    private var currentIsEraser = false

    /** 已固化前缀笔画数（渲染变淡）。 */
    private var donePrefix = 0

    private var chips: List<HandwritingCandidate> = emptyList()

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
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val tool = event.getToolType(event.actionIndex)
                    if (tool != MotionEvent.TOOL_TYPE_STYLUS && tool != MotionEvent.TOOL_TYPE_ERASER) return
                    currentIsEraser = tool == MotionEvent.TOOL_TYPE_ERASER
                    current = InkStrokeBuilder()
                    addPoint(event.x, event.y, event.eventTime, event.pressure)
                    invalidate()
                }

                MotionEvent.ACTION_MOVE -> {
                    val c = current ?: return
                    if (c.points.isEmpty()) return
                    // 历史采样补齐（批量合帧的 MOVE 事件），保证笔画保真
                    for (h in 0 until event.historySize) {
                        addPoint(
                            event.getHistoricalX(h),
                            event.getHistoricalY(h),
                            event.getHistoricalEventTime(h),
                            event.getHistoricalPressure(h),
                        )
                    }
                    addPoint(event.x, event.y, event.eventTime, event.pressure)
                    invalidate()
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val c = current ?: return
                    current = null
                    if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                        invalidate()
                        return
                    }
                    val stroke = InkStroke(c.points.toList(), c.pressures.toList())
                    when {
                        currentIsEraser -> eraseStrokeLocked(stroke)
                        stroke.points.size == 1 -> {
                            // 单点：先判按钮/候选点选，未命中按落点成「点」笔画
                            val p = stroke.points[0]
                            val target = hitTestLocked(p.x, p.y)
                            if (target != null) {
                                onTap(target)
                            } else {
                                commitStrokeLocked(stroke)
                            }
                        }

                        else -> commitStrokeLocked(stroke)
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
        donePrefix = 0
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
        for ((rect, target) in buttonRects) {
            if (rect.contains(x, y)) return target
        }
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
            current?.let { drawInk(canvas, InkStroke(it.points.toList(), it.pressures.toList()), color) }
        }
        drawChips(canvas, color)
        drawButtons(canvas, color)
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

    /** 控制钮（右下角竖排）：✓ 固化 / ⌫ 撤销 / ✕ 结束会话。 */
    private fun drawButtons(canvas: Canvas, color: Int) {
        buttonRects.clear()
        val r = BUTTON_RADIUS_DP * density
        val margin = BUTTON_MARGIN_DP * density
        val gap = BUTTON_GAP_DP * density
        val cx = width - margin - r
        val targets = listOf(StylusTapTarget.Done, StylusTapTarget.Backspace, StylusTapTarget.Close)
        targets.forEachIndexed { index, target ->
            val cy = height - margin - r - index * (2 * r + gap)
            val rect = RectF(cx - r, cy - r, cx + r, cy + r)
            buttonRects.add(rect to target)
            fillPaint.color = withAlpha(color, BUTTON_BG_ALPHA)
            canvas.drawCircle(cx, cy, r, fillPaint)
            paint.color = color
            paint.strokeWidth = 2.5f * density
            when (target) {
                StylusTapTarget.Done -> {
                    canvas.drawLine(cx - r * 0.4f, cy, cx - r * 0.1f, cy + r * 0.3f, paint)
                    canvas.drawLine(cx - r * 0.1f, cy + r * 0.3f, cx + r * 0.45f, cy - r * 0.35f, paint)
                }

                StylusTapTarget.Backspace -> {
                    // ⌫：左尖箭头 + 内部 ×
                    val left = cx - r * 0.55f
                    val right = cx + r * 0.5f
                    val top = cy - r * 0.35f
                    val bottom = cy + r * 0.35f
                    val path = Path().apply {
                        moveTo(right, top)
                        lineTo(left + r * 0.25f, top)
                        lineTo(left, cy)
                        lineTo(left + r * 0.25f, bottom)
                        lineTo(right, bottom)
                        close()
                    }
                    canvas.drawPath(path, paint)
                    canvas.drawLine(cx - r * 0.05f, cy - r * 0.15f, cx + r * 0.25f, cy + r * 0.15f, paint)
                    canvas.drawLine(cx - r * 0.05f, cy + r * 0.15f, cx + r * 0.25f, cy - r * 0.15f, paint)
                }

                StylusTapTarget.Close -> {
                    canvas.drawLine(cx - r * 0.3f, cy - r * 0.3f, cx + r * 0.3f, cy + r * 0.3f, paint)
                    canvas.drawLine(cx - r * 0.3f, cy + r * 0.3f, cx + r * 0.3f, cy - r * 0.3f, paint)
                }

                is StylusTapTarget.Chip -> Unit // 按钮区不渲染候选
            }
        }
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or ((alpha * 255f).toInt().coerceIn(0, 255) shl 24)

    private companion object {
        const val INK_MIN_WIDTH_DP = 2.5f
        const val INK_MAX_WIDTH_DP = 10f
        const val INK_FADE_ALPHA = 0.35f

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
