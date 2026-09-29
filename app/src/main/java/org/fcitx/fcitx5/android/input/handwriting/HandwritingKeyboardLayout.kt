/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 手写输入作为**第三种键盘布局**（与文本/数字并列）。
 *
 * - 布局：状态行 → 手写画布 → 底部键行（复用项目既有 `KeyDef` 与 `ComposeKeyRow`）；
 * - 候选由 `HandwritingInputComponent` 推给候选栏，不绘制在本布局；
 * - 识别窗口：每落一笔全窗重新识别；停顿只把笔画变淡，
 *   继续闲置 [HW_CLEAR_IDLE_MS] 才清空窗口（墨迹同步消失）并回调 `onFinalize`；
 * - 渲染：`[0,fade)` 变淡、`[fade,end)` 正常；笔画离开识别窗口（固化/清窗）时墨迹同步消失；
 * - 固化：窗口笔画数超过 `HW_RECOGNIZE_WINDOW_LIMIT`，或段数饱和且存在已完成前缀时，
 *   把最早段移出窗口并回调 `onSegmentSettled`；
 * - ⚠️ `Canvas` 的 `pointerInput` 不挂 `key(...)`：否则每个采样点都会重建手势节点并
 *   CANCEL 当前笔画。
 */
package org.fcitx.fcitx5.android.input.handwriting

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.handwriting.HandwritingEngine
import org.fcitx.fcitx5.android.data.handwriting.HandwritingSegmenter
import org.fcitx.fcitx5.android.data.handwriting.HandwritingStrokeFx
import org.fcitx.fcitx5.android.data.handwriting.StrokePoint
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.keyboard.BackspaceKey
import org.fcitx.fcitx5.android.input.keyboard.ComposeKey
import org.fcitx.fcitx5.android.input.keyboard.ComposeKeyRow
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyboardLayoutNames
import org.fcitx.fcitx5.android.input.keyboard.preferenceState
import org.fcitx.fcitx5.android.input.keyboard.LayoutSwitchKey
import org.fcitx.fcitx5.android.input.keyboard.ReturnKey
import org.fcitx.fcitx5.android.input.keyboard.SpaceKey
import org.fcitx.fcitx5.android.input.keyboard.spaceAndBackspaceGestureListener
import org.fcitx.fcitx5.android.input.keyboard.spaceAndBackspaceSwipeSpec
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.max
import kotlin.math.sqrt

/** 起笔判定阈值（px）：小于它视为点击而不是笔画。 */
private const val STROKE_START_THRESHOLD_PX = 12f

/**
 * 变淡后到清空识别窗口（墨迹随之消失）的延时（ms）。
 */
private const val HW_CLEAR_IDLE_MS = 300L

/** 单次识别任务里最多连续固化的轮数。 */
private const val MAX_SETTLE_ROUNDS = 8

/** 字迹粗细范围（px）。 */
private const val STROKE_MIN_WIDTH = 8f
private const val STROKE_MAX_WIDTH = 22f

/**
 * 手写键盘。
 *
 * @param modelReady 模型是否就绪（false 时画布不接收笔画，只显示提示）
 * @param statusText 状态文案（加载中 / 识别中 / 空闲 / 错误）
 * @param clearSignal 外部清空请求（固化/上屏失败时递增；本布局清笔画并重置段缓存）
 * @param onRecognition 识别结果（时间序；最后一段=当前正在写的字）
 * @param onSegmentSettled 最早段被固化出窗（文本已上屏，参数为固化的文本）
 * @param onUndoActive 撤销到窗口为空：屏上活动区文本应一并撤销
 * @param onRecognizing 识别忙闲（状态行用）
 * @param keyActionListener 底部键行的按键出口
 */
@Composable
fun HandwritingKeyboardLayout(
    modelReady: Boolean,
    statusText: String,
    clearSignal: Int,
    onFinalize: () -> Unit,
    onRecognition: (List<HandwritingSegmenter.Segment>) -> Unit,
    onSegmentSettled: (String) -> Unit,
    onUndoActive: () -> Unit,
    onRecognizing: (Boolean) -> Unit,
    keyActionListener: KeyActionListener?,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val strokes = remember { mutableStateListOf<List<StrokePoint>>() }
    var currentStroke by remember { mutableStateOf<List<StrokePoint>>(emptyList()) }
    var strokeCount by remember { mutableIntStateOf(0) }

    /** 已完成前缀（段边界间隔 ≥ [HW_FADE_GAP_MS] 之前），仅影响渲染。 */
    var donePrefix by remember { mutableIntStateOf(0) }

    /** 停顿提示：整窗变淡（不清笔画）。 */
    var idleHidden by remember { mutableStateOf(false) }

    var lastStrokeEndMs by remember { mutableLongStateOf(0L) }
    val view = LocalView.current
    // 手势协程在首个指针事件时启动、重组不会重启，块内直接读参数会取到启动时的旧值，
    // 故用 State 读取最新状态
    val modelReadyState = rememberUpdatedState(modelReady)
    val keyboardPrefs = remember { AppPrefs.getInstance().keyboard }
    val hapticOnRepeat = keyboardPrefs.hapticOnRepeat.preferenceState()
    val spaceSwipeMoveCursor = keyboardPrefs.spaceSwipeMoveCursor.preferenceState()
    val scope = rememberCoroutineScope()

    // 段缓存随组合存活（离开布局即释放）
    val recognizer = remember {
        HandwritingSegmenter { s, k -> HandwritingEngine.predict(s, k) }
    }
    var recognizeJob by remember { mutableStateOf<Job?>(null) }

    /** 变淡范围起点：停顿提示时是整窗，否则是已完成前缀。 */
    val fadeStart = if (idleHidden) strokes.size else donePrefix

    // 停顿达阈值后整窗变淡；继续闲置 HW_CLEAR_IDLE_MS 则清空窗口（墨迹同步消失）并固化活动区
    LaunchedEffect(lastStrokeEndMs, strokeCount) {
        if (lastStrokeEndMs <= 0L || strokeCount <= 0) return@LaunchedEffect
        delay(HandwritingStrokeFx.splitPauseMs(strokeCount))
        if (lastStrokeEndMs <= 0L) return@LaunchedEffect
        idleHidden = true
        delay(HW_CLEAR_IDLE_MS)
        if (lastStrokeEndMs <= 0L) return@LaunchedEffect
        recognizeJob?.cancel()
        strokes.clear()
        currentStroke = emptyList()
        strokeCount = 0
        donePrefix = 0
        idleHidden = false
        lastStrokeEndMs = 0L
        recognizer.reset()
        onFinalize()
    }

    // 外部清空请求：清笔画并重置段缓存
    LaunchedEffect(clearSignal) {
        if (clearSignal <= 0) return@LaunchedEffect
        recognizeJob?.cancel()
        recognizeJob = null
        strokes.clear()
        currentStroke = emptyList()
        strokeCount = 0
        donePrefix = 0
        idleHidden = false
        lastStrokeEndMs = 0L
        recognizer.reset()
        onRecognizing(false)
    }

    fun scheduleRecognition() {
        recognizeJob?.cancel()
        recognizeJob = scope.launch {
            val self = coroutineContext[Job]
            onRecognizing(true)
            try {
                var round = 0
                while (isActive && round++ < MAX_SETTLE_ROUNDS) {
                    val window = strokes.toList()
                    if (window.isEmpty()) {
                        onRecognition(emptyList())
                        return@launch
                    }
                    val gaps = HandwritingStrokeFx.windowGaps(window)
                    val result = withContext(Dispatchers.Default) {
                        recognizer.recognize(window, gaps)
                    }
                    if (!isActive) return@launch
                    val segments = result.segments
                    if (segments.isEmpty()) {
                        onRecognition(emptyList())
                        return@launch
                    }
                    onRecognition(segments)

                    // 已完成前缀只做视觉变淡
                    val done = HandwritingStrokeFx.settledStrokesBeforeCurrent(segments, gaps)
                    donePrefix = done

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
                    onSegmentSettled(settledText)
                    withContext(Dispatchers.Main) {
                        repeat(settleStrokes) { if (strokes.isNotEmpty()) strokes.removeAt(0) }
                        strokeCount = strokes.size
                        donePrefix = 0
                        recognizer.onStrokesTrimmed(settleStrokes)
                    }
                }
            } finally {
                if (recognizeJob === self) {
                    recognizeJob = null
                    onRecognizing(false)
                }
            }
        }
    }

    fun commitStroke(stroke: List<StrokePoint>) {
        strokes.add(stroke)
        strokeCount = strokes.size
        // 新笔画落下：取消停顿提示，随后由识别结果更新已完成前缀
        if (idleHidden) idleHidden = false
        lastStrokeEndMs = System.currentTimeMillis()
        scheduleRecognition()
    }

    fun undoLastStroke() {
        if (strokes.isEmpty()) {
            onUndoActive()
            return
        }
        strokes.removeAt(strokes.size - 1)
        strokeCount = strokes.size
        idleHidden = false
        if (donePrefix > strokes.size) donePrefix = strokes.size
        if (strokes.isEmpty()) {
            lastStrokeEndMs = 0L
            recognizer.reset()
            onUndoActive()
        } else {
            lastStrokeEndMs = System.currentTimeMillis()
            scheduleRecognition()
        }
    }

    Column(modifier = modifier.fillMaxSize().background(colors.background)) {

        // 状态行：状态文案 + 「撤销一笔」小按钮（候选交给真正的候选栏）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
        ) {
            Text(
                text = statusText,
                color = colors.onSurfaceVariantSummary,
                fontSize = 12.sp,
                maxLines = 1,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            Text(
                text = stringResource(R.string.handwriting_undo_stroke),
                color = colors.primary,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            undoLastStroke()
                        }
                    },
            )
        }

        // 画布
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 8.dp),
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            if (!modelReadyState.value) return@awaitEachGesture
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val startX = down.position.x
                            val startY = down.position.y
                            val startedAt = System.currentTimeMillis()
                            var drawing = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (change.pressed) {
                                    change.consume()
                                    val distance = (change.position - down.position).getDistance()
                                    if (!drawing && distance > STROKE_START_THRESHOLD_PX) {
                                        drawing = true
                                        // 起笔即取消停顿提示（长笔画写到一半不应让整窗墨迹变淡）
                                        lastStrokeEndMs = 0L
                                        currentStroke = listOf(
                                            StrokePoint(startX, startY, startedAt)
                                        )
                                    }
                                    if (drawing) {
                                        currentStroke = currentStroke + StrokePoint(
                                            change.position.x,
                                            change.position.y,
                                            System.currentTimeMillis(),
                                        )
                                    }
                                } else {
                                    change.consume()
                                    if (drawing && currentStroke.size >= 2) {
                                        val finished = currentStroke
                                        currentStroke = emptyList()
                                        commitStroke(finished)
                                    } else {
                                        currentStroke = emptyList()
                                    }
                                    break
                                }
                            }
                        }
                    },
            ) {
                // 渲染：[0,fade) 变淡、[fade,end) 正常；笔画离开识别窗口（固化/清窗）时墨迹同步消失。
                // 正在书写的笔画无条件渲染（它尚未进入 strokes）。
                val fade = fadeStart.coerceIn(0, strokes.size)
                if (fade > 0) {
                    strokes.subList(0, fade).forEach {
                        drawStroke(it, colors.onSurface.copy(alpha = 0.3f))
                    }
                }
                strokes.drop(fade).forEach { drawStroke(it, colors.onSurface) }
                if (currentStroke.size >= 2) drawStroke(currentStroke, colors.onSurface)
            }
            if (!modelReady) {
                Text(
                    text = stringResource(R.string.handwriting_model_missing),
                    color = colors.primary,
                    fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            }
        }

        // 底部键行：复用 KeyDef + ComposeKeyRow，手势语义与主键盘一致
        //（退格：按下删除/长按连发/横滑移光标；空格：长按语音）
        val listenerState = rememberUpdatedState(keyActionListener)
        val row = remember {
            listOf(
                BackspaceKey(percentWidth = 0.20f),
                LayoutSwitchKey(
                    displayText = "ABC",
                    to = KeyboardLayoutNames.Text,
                    percentWidth = 0.14f
                ),
                SpaceKey(),
                ReturnKey(percentWidth = 0.20f),
            )
        }
        ComposeKeyRow(
            row = row,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 4.dp),
            keyIdBase = 900,
        ) { keyId, def, insets, keyModifier ->
            val swipeSpec = def.spaceAndBackspaceSwipeSpec(spaceSwipeMoveCursor)
            val gestureListener = remember(def, hapticOnRepeat) {
                def.spaceAndBackspaceGestureListener(
                    view = view,
                    onAction = { action ->
                        listenerState.value?.onKeyAction(action, KeyActionListener.Source.Keyboard)
                    },
                    hapticOnRepeat = hapticOnRepeat,
                )
            }
            ComposeKey(
                def = def,
                keyId = keyId,
                modifier = keyModifier,
                insets = insets,
                keyActionListener = listenerState.value,
                swipeSpec = swipeSpec,
                onSwipeGesture = gestureListener,
            )
        }
    }
}

/** 按速度插值笔宽（快写细、慢写粗）。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStroke(
    stroke: List<StrokePoint>,
    color: Color,
) {
    if (stroke.size == 1) {
        drawCircle(color, radius = STROKE_MAX_WIDTH / 2, center = Offset(stroke[0].x, stroke[0].y))
        return
    }
    var lastWidth = STROKE_MAX_WIDTH
    for (i in 1 until stroke.size) {
        val p0 = stroke[i - 1]
        val p1 = stroke[i]
        val dx = p1.x - p0.x
        val dy = p1.y - p0.y
        val distance = sqrt(dx * dx + dy * dy)
        val dt = max((p1.timeMs - p0.timeMs).toFloat() / 1000f, 0.001f)
        val speed = distance / dt / 100f
        val raw = when {
            speed >= 3f -> STROKE_MIN_WIDTH
            speed <= 0.3f -> STROKE_MAX_WIDTH
            else -> STROKE_MAX_WIDTH - speed / 3f * STROKE_MAX_WIDTH
        }
        val width = raw * 0.35f + lastWidth * 0.65f
        lastWidth = width
        drawLine(
            color = color,
            start = Offset(p0.x, p0.y),
            end = Offset(p1.x, p1.y),
            strokeWidth = width,
            cap = StrokeCap.Round,
        )
    }
}
