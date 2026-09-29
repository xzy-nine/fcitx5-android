/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 手写输入作为**第三种键盘布局**（与文本/数字并列，不是覆盖层面板）。
 *
 * - 布局：状态行 → 手写画布 → 底部键行（复用项目既有 `KeyDef` 与 `ComposeKeyRow`，
 *   因此长按连发、横向滑移光标等语义与主键盘完全一致）；
 * - **候选不画在这里**，而是由 `HandwritingInputComponent` 推给真正的候选栏
 *   （见 `InputView` 的接管逻辑）；
 * - ⚠️ `Canvas` 的 `pointerInput` **绝不能**挂 `key(...)`：每个采样点都会重建手势节点并
 *   CANCEL 当前笔画（症状：笔画只能写出一小截）。
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.delay
import org.fcitx.fcitx5.android.R
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

/** 每多一笔，停顿阈值递减量（ms）。 */
private const val PAUSE_STEP_MS = 50L

/** 停顿阈值下限（ms）。 */
private const val PAUSE_MIN_MS = 500L

/** 停顿阈值上限（ms，起笔阶段保守）。 */
private const val PAUSE_BASE_MS = 700L

/** 停顿后仍无新笔画的清空延时（ms）。 */
private const val IDLE_CLEAR_MS = 2500L

/** 字迹粗细范围（px）。 */
private const val STROKE_MIN_WIDTH = 8f
private const val STROKE_MAX_WIDTH = 22f

private fun pauseThresholdMs(strokeCount: Int): Long =
    (PAUSE_BASE_MS - (strokeCount - 1).coerceAtLeast(0) * PAUSE_STEP_MS)
        .coerceAtLeast(PAUSE_MIN_MS)

/**
 * 手写键盘。
 *
 * @param modelReady 模型是否就绪（false 时画布不接收笔画，只显示提示）
 * @param statusText 状态文案（加载中 / 识别中 / 空闲 / 错误）
 * @param onStrokesChanged 笔画变化（每完成一笔触发一次叠写识别）
 * @param onFinalize 活动字固化（停顿清窗）
 * @param onUndoActive 撤销一笔导致活动字整段回滚
 */
@Composable
fun HandwritingKeyboardLayout(
    modelReady: Boolean,
    statusText: String,
    onStrokesChanged: (List<List<StrokePoint>>, List<Long>) -> Unit,
    onFinalize: () -> Unit,
    onUndoActive: () -> Unit,
    keyActionListener: KeyActionListener?,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val strokes = remember { mutableStateListOf<List<StrokePoint>>() }
    var currentStroke by remember { mutableStateOf<List<StrokePoint>>(emptyList()) }
    var strokeCount by remember { mutableIntStateOf(0) }
    var fading by remember { mutableStateOf(false) }
    val view = LocalView.current
    val keyboardPrefs = remember { AppPrefs.getInstance().keyboard }
    val hapticOnRepeat = keyboardPrefs.hapticOnRepeat.preferenceState()
    val spaceSwipeMoveCursor = keyboardPrefs.spaceSwipeMoveCursor.preferenceState()

    fun commitStroke(stroke: List<StrokePoint>) {
        strokes.add(stroke)
        strokeCount = strokes.size
        fading = false
        onStrokesChanged(strokes.toList(), gapsOf(strokes))
    }

    fun clearAll(finalize: Boolean) {
        strokes.clear()
        currentStroke = emptyList()
        strokeCount = 0
        fading = false
        if (finalize) onFinalize()
    }

    // 停顿 → 字迹变淡提示「已识别上屏」；再等 IDLE_CLEAR_MS 无新笔画才真正清空（固化活动字）
    LaunchedEffect(strokeCount) {
        if (strokeCount <= 0) return@LaunchedEffect
        delay(pauseThresholdMs(strokeCount))
        fading = true
        delay(IDLE_CLEAR_MS)
        if (strokes.size == strokeCount) clearAll(finalize = true)
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
                            if (strokes.isNotEmpty()) {
                                strokes.removeAt(strokes.size - 1)
                                strokeCount = strokes.size
                                fading = false
                                if (strokes.isEmpty()) onUndoActive()
                                else onStrokesChanged(strokes.toList(), gapsOf(strokes))
                            } else {
                                onUndoActive()
                            }
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
                            if (!modelReady) return@awaitEachGesture
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
                val color =
                    if (fading) colors.onSurface.copy(alpha = 0.3f) else colors.onSurface
                strokes.forEach { drawStroke(it, color) }
                if (currentStroke.size >= 2) drawStroke(currentStroke, color)
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

/** 笔间时间间隔：gaps[j] = 第 j 笔起笔与上一笔收笔的间隔（gaps[0] 恒为 0）。 */
private fun gapsOf(strokes: List<List<StrokePoint>>): List<Long> =
    strokes.mapIndexed { index, stroke ->
        if (index == 0) 0L
        else stroke.first().timeMs - strokes[index - 1].last().timeMs
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
