/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 手写输入作为**第三种键盘布局**（与文本/数字并列）。
 *
 * - 镜像 L 布局：左侧画布 + 右侧固定宽竖列（删除 / 符号 / 回车），底部键行
 *   （返回 ABC / 123 / 符号页 / 空格），竖列与底行由 `ComposeKeyColumn` / `ComposeKeyRow` 渲染；
 * - 右侧列的符号键单独填充且不可自定义，键值与主键盘同口径（由引擎按全半角/标点设置转换）；
 * - 候选由 `HandwritingInputComponent` 推给候选栏，不绘制在本布局；
 * - 识别走统一入口 `data/handwriting/HandwritingRecognition.kt`（系统手写引擎优先 → 谷歌数字墨水
 *   回落，与触控笔手写同一份引擎选择）；两个引擎都是**整段墨迹 → 文本**，故每次把整个窗口送进去；
 * - 识别窗口：每落一笔全窗重新识别；停顿只把笔画变淡，
 *   继续闲置 [HW_CLEAR_IDLE_MS] 才清空窗口（墨迹同步消失）并回调 `onFinalize`；
 * - 渲染：停顿提示时整窗变淡；笔画离开识别窗口（清窗）时墨迹同步消失；
 * - `Canvas` 的 `pointerInput` 固定用 `Unit` 作 key：保证手势协程跨重组存活。
 */
package org.fcitx.fcitx5.android.input.handwriting

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalContext
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
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.data.handwriting.HandwritingRecognition
import org.fcitx.fcitx5.android.data.handwriting.HandwritingCandidate
import org.fcitx.fcitx5.android.data.handwriting.HandwritingStrokeFx
import org.fcitx.fcitx5.android.data.handwriting.StrokePoint
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.keyboard.BackspaceKey
import org.fcitx.fcitx5.android.input.keyboard.ComposeKey
import org.fcitx.fcitx5.android.input.keyboard.ComposeKeyColumn
import org.fcitx.fcitx5.android.input.keyboard.ComposeKeyRow
import org.fcitx.fcitx5.android.input.keyboard.ComposeSymbolSlider
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.KeyboardLayoutNames
import org.fcitx.fcitx5.android.input.keyboard.LayoutSwitchKey
import org.fcitx.fcitx5.android.input.keyboard.ReturnKey
import org.fcitx.fcitx5.android.input.keyboard.SpaceKey
import org.fcitx.fcitx5.android.input.keyboard.preferenceState
import org.fcitx.fcitx5.android.input.keyboard.spaceAndBackspaceGestureListener
import org.fcitx.fcitx5.android.input.keyboard.spaceAndBackspaceSwipeSpec
import org.fcitx.fcitx5.android.input.keyboard.rememberKeyboardVisuals
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import top.yukonga.miuix.kmp.basic.Text
import kotlin.math.max
import kotlin.math.sqrt

/** 起笔判定阈值（px）：小于它视为点击而不是笔画。 */
private const val STROKE_START_THRESHOLD_PX = 12f

/**
 * 变淡后到清空识别窗口（墨迹随之消失）的延时（ms）。
 */
private const val HW_CLEAR_IDLE_MS = 300L

/** 底栏小键（ABC / 123 / 符号 / 回车）与右侧竖列的宽度占键盘宽比例。 */
private const val HW_SIDE_KEY_WIDTH_FRACTION = 0.15f

/** 右侧列删除键的高度（dp）。 */
private val HW_SIDE_KEY_HEIGHT = 48.dp

/** 底部键行高度（dp）。 */
private val HW_BOTTOM_ROW_HEIGHT = 48.dp

/** 字迹粗细范围（px）。 */
private const val STROKE_MIN_WIDTH = 8f
private const val STROKE_MAX_WIDTH = 22f

/** 右侧列符号滑块的符号（固定填充、不可自定义）：显示 ASCII 标签，发出的键值走引擎标点/全半角设置。 */
private val HandwritingSymbolSyms: Map<String, Int> = linkedMapOf(
    "." to 0x2e,
    "," to 0x2c,
    ";" to 0x3b,
    "?" to 0x3f,
    "!" to 0x21,
    "\"" to 0x22,
)

private val HandwritingSymbolLabels: List<String> = HandwritingSymbolSyms.keys.toList()

/**
 * 手写键盘。
 *
 * @param modelReady 识别后端是否就绪（false 时画布不接收笔画，只显示提示）
 * @param statusText 状态文案（加载中 / 识别中 / 空闲 / 错误）
 * @param clearSignal 外部清空请求（固化/上屏失败时递增；本布局清笔画并重置识别窗口）
 * @param onFinalize 本布局已自行清窗（超长闲置）：会话组件固化活动区
 * @param onFinalizeWindow 删除/回车/空格/符号键按下：会话组件固化活动区并请布局清窗
 * @param onSpace 空格键按下：返回 true 表示已被候选词消费（不上屏空格）
 * @param onRecognition 整段墨迹的识别结果（候选，按分数降序）
 * @param onRecognizing 识别忙闲（状态行用）
 * @param keyActionListener 底部键行与右侧列的按键出口
 */
@Composable
fun HandwritingKeyboardLayout(
    modelReady: Boolean,
    statusText: String,
    clearSignal: Int,
    onFinalize: () -> Unit,
    onFinalizeWindow: () -> Unit,
    onSpace: () -> Boolean,
    onRecognition: (List<HandwritingCandidate>) -> Unit,
    onRecognizing: (Boolean) -> Unit,
    keyActionListener: KeyActionListener?,
    modifier: Modifier = Modifier,
) {
    val visuals = rememberKeyboardVisuals()
    val strokes = remember { mutableStateListOf<List<StrokePoint>>() }
    var currentStroke by remember { mutableStateOf<List<StrokePoint>>(emptyList()) }
    var strokeCount by remember { mutableIntStateOf(0) }

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
    // 符号滑块外显段数：与数字键盘共用同一偏好
    val symbolSliderVisibleCount =
        remember { AppPrefs.getInstance().symbols }.symbolSliderVisibleCount.preferenceState()
    val scope = rememberCoroutineScope()

    // 识别走统一入口（系统手写引擎优先 → 谷歌数字墨水回落），与触控笔路径同一份引擎选择；
    // 两个引擎都是整段墨迹识别，故直接把整个窗口送进去，不做切分。
    val context = LocalContext.current
    var recognizeJob by remember { mutableStateOf<Job?>(null) }

    // 空格：有候选词时点选首选、不上屏空格
    val spaceState = rememberUpdatedState(onSpace)
    val spaceListener = remember(keyActionListener) {
        object : KeyActionListener {
            override fun onKeyAction(action: KeyAction, source: KeyActionListener.Source) {
                if (spaceState.value()) return
                keyActionListener?.onKeyAction(action, source)
            }

            override fun onKeyActionRelease(action: KeyAction, source: KeyActionListener.Source) {
                keyActionListener?.onKeyActionRelease(action, source)
            }
        }
    }

    // 回车改动宿主文本：先固化活动区并清窗
    val finalizeWindowState = rememberUpdatedState(onFinalizeWindow)
    val finalizeListener = remember(keyActionListener) {
        object : KeyActionListener {
            override fun onKeyAction(action: KeyAction, source: KeyActionListener.Source) {
                finalizeWindowState.value()
                keyActionListener?.onKeyAction(action, source)
            }

            override fun onKeyActionRelease(action: KeyAction, source: KeyActionListener.Source) {
                keyActionListener?.onKeyActionRelease(action, source)
            }
        }
    }

    val deleteKey = remember { BackspaceKey() }
    // 底栏：ABC / 123 / 空格（占剩余宽，左右各两键使其居中）/ 符号页 / 回车，四个小键等宽
    val bottomRow = remember {
        listOf(
            LayoutSwitchKey(
                displayText = "ABC",
                to = KeyboardLayoutNames.Text,
                percentWidth = HW_SIDE_KEY_WIDTH_FRACTION,
            ),
            LayoutSwitchKey(
                displayText = "123",
                to = KeyboardLayoutNames.Number,
                percentWidth = HW_SIDE_KEY_WIDTH_FRACTION,
            ),
            SpaceKey(),
            LayoutSwitchKey(
                displayText = "!?#",
                to = PickerWindow.Key.Symbol.name,
                percentWidth = HW_SIDE_KEY_WIDTH_FRACTION,
                variant = KeyDef.Appearance.Variant.AltForeground,
            ),
            ReturnKey(percentWidth = HW_SIDE_KEY_WIDTH_FRACTION),
        )
    }

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
        idleHidden = false
        lastStrokeEndMs = 0L
        onFinalize()
    }

    // 外部清空请求：清笔画并重置识别窗口
    LaunchedEffect(clearSignal) {
        if (clearSignal <= 0) return@LaunchedEffect
        recognizeJob?.cancel()
        recognizeJob = null
        strokes.clear()
        currentStroke = emptyList()
        strokeCount = 0
        idleHidden = false
        lastStrokeEndMs = 0L
        onRecognizing(false)
    }

    fun scheduleRecognition() {
        recognizeJob?.cancel()
        recognizeJob = scope.launch {
            val self = coroutineContext[Job]
            onRecognizing(true)
            try {
                val window = strokes.toList()
                if (window.isEmpty()) {
                    onRecognition(emptyList())
                    return@launch
                }
                val candidates = withContext(Dispatchers.Default) {
                    HandwritingRecognition.recognize(context, window)
                }
                if (!isActive) return@launch
                onRecognition(candidates)
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
        // 新笔画落下：取消停顿提示
        if (idleHidden) idleHidden = false
        lastStrokeEndMs = System.currentTimeMillis()
        scheduleRecognition()
    }

    // 不画整体背景：沿用键盘主题底（`InputView.customBackground`），键区另画「非按键底」色带
    Column(modifier = modifier.fillMaxSize()) {

        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {

            // 画布区：状态行 + 手写画布
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                Text(
                    text = statusText,
                    color = visuals.keyTextColor.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                )
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
                                    // 先等触摸开始再判就绪：检查放最前会让 awaitEachGesture
                                    // 在模型未就绪时立即返回，每帧空转且拿不到新的就绪状态
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    if (!modelReadyState.value) return@awaitEachGesture
                                    val startX = down.position.x
                                    val startY = down.position.y
                                    val startedAt = System.currentTimeMillis()
                                    var drawing = false
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull() ?: break
                                        if (change.pressed) {
                                            change.consume()
                                            val distance =
                                                (change.position - down.position).getDistance()
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
                        // 渲染：停顿提示时整窗变淡；笔画离开识别窗口（清窗）时墨迹同步消失。
                        // 正在书写的笔画无条件正常渲染（它尚未进入 strokes）。
                        val inkColor = if (idleHidden) {
                            visuals.keyTextColor.copy(alpha = 0.3f)
                        } else {
                            visuals.keyTextColor
                        }
                        strokes.forEach { drawStroke(it, inkColor) }
                        if (currentStroke.size >= 2) drawStroke(currentStroke, visuals.keyTextColor)
                    }
                    if (!modelReady) {
                        Text(
                            text = stringResource(R.string.handwriting_engine_unavailable),
                            color = visuals.keyTextColor,
                            fontSize = 13.sp,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        )
                    }
                }
            }

            // 右侧竖列（镜像 L 的竖臂，宽度与底栏小键一致）：删除 → 滚动符号列（固定填充、不可自定义）
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(HW_SIDE_KEY_WIDTH_FRACTION)
                    .background(visuals.backgroundColor)
                    .padding(horizontal = 2.dp, vertical = 2.dp),
            ) {
                ComposeKeyColumn(
                    keys = remember(deleteKey) { listOf(deleteKey) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(HW_SIDE_KEY_HEIGHT),
                    keyIdBase = HW_SIDE_DELETE_KEY_ID,
                    keyActionListener = keyActionListener,
                    // 删除会改动宿主文本：先固化活动区并清窗
                    onBeforeKeyAction = { onFinalizeWindow() },
                )
                ComposeSymbolSlider(
                    symbols = HandwritingSymbolLabels,
                    // 外显段数与数字键盘滑块同口径（符号数多于它即需滚动，不会一次显示完）
                    visibleCount = symbolSliderVisibleCount,
                    onSymbolInput = { label ->
                        onFinalizeWindow()
                        val sym = HandwritingSymbolSyms[label] ?: return@ComposeSymbolSlider
                        keyActionListener?.onKeyAction(
                            KeyAction.SymAction(KeySym(sym)),
                            KeyActionListener.Source.Keyboard,
                        )
                    },
                    // 不可自定义：无编辑按钮
                    onEditClick = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }

        // 底部键行（镜像 L 的横臂）：ABC / 123 / 空格 / 符号页 / 回车
        val listenerState = rememberUpdatedState(keyActionListener)
        ComposeKeyRow(
            row = bottomRow,
            modifier = Modifier
                .fillMaxWidth()
                .height(HW_BOTTOM_ROW_HEIGHT)
                .background(visuals.backgroundColor)
                .padding(horizontal = 4.dp),
            keyIdBase = HW_BOTTOM_ROW_KEY_ID_BASE,
        ) { keyId, def, insets, keyModifier ->
            val listener = when {
                def is SpaceKey -> spaceListener
                def is ReturnKey -> finalizeListener
                else -> listenerState.value
            }
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
                keyActionListener = listener,
                swipeSpec = swipeSpec,
                onSwipeGesture = gestureListener,
            )
        }
    }
}

/** 右侧列删除键 id。 */
private const val HW_SIDE_DELETE_KEY_ID = 900

/** 底部行键 id 基址。 */
private const val HW_BOTTOM_ROW_KEY_ID_BASE = 950

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
