/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 手写画布 + 候选行 + 功能键（IME 内覆盖层内容）。
 *
 * 交互与 Xime 的手写键盘同源但独立实现：
 * - 画布吃全部指针事件：位移超过阈值才算「起笔」，抬手且点数 ≥2 才记为一笔；
 * - 每完成一笔触发一次叠写识别（[onStrokesChanged]）；停顿后画布变淡并清空（视觉提示），
 *   清空同时固化活动字（[onFinalize]）；
 * - 行内放宽的「一笔」判定避免误点成笔。
 */
package org.fcitx.fcitx5.android.input.handwriting

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.handwriting.HandwritingCandidate
import org.fcitx.fcitx5.android.data.handwriting.StrokePoint
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
    (PAUSE_BASE_MS - (strokeCount - 1).coerceAtLeast(0) * PAUSE_STEP_MS).coerceAtLeast(PAUSE_MIN_MS)

/**
 * 手写面板内容。
 *
 * @param modelReady 模型是否就绪（false 时画布只显示提示，不接收笔画）
 * @param statusText 状态文案（加载中 / 识别中 / 空闲）
 * @param candidates 当前活动字的候选
 * @param onStrokesChanged 笔画变化（每完成一笔触发一次叠写识别）
 * @param onFinalize 活动字固化（停顿清空画布）
 * @param onUndoActive 撤销一笔导致活动字整段回滚
 * @param onSelectCandidate 点选候选
 * @param onClear 手动清空画布
 */
@Composable
fun ComposeHandwritingPanel(
    modelReady: Boolean,
    statusText: String,
    candidates: List<HandwritingCandidate>,
    onStrokesChanged: (List<List<StrokePoint>>, List<Long>) -> Unit,
    onFinalize: () -> Unit,
    onUndoActive: () -> Unit,
    onSelectCandidate: (HandwritingCandidate) -> Unit,
    onClear: () -> Unit,
    onBackToKeyboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val strokes = remember { mutableStateListOf<List<StrokePoint>>() }
    var currentStroke by remember { mutableStateOf<List<StrokePoint>>(emptyList()) }
    var strokeCount by remember { mutableIntStateOf(0) }
    var fading by remember { mutableStateOf(false) }

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

    Column(modifier = modifier.fillMaxSize()) {

        // 状态行：状态文案 + 候选
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = statusText,
                color = colors.onSurfaceVariantSummary,
                fontSize = 12.sp,
                maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                candidates.take(8).forEachIndexed { index, candidate ->
                    Text(
                        text = candidate.char,
                        color = if (index == 0) colors.primary else colors.onSurface,
                        fontSize = 18.sp,
                        fontWeight = if (index == 0) FontWeight.Medium else FontWeight.Normal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (index == 0) colors.secondaryContainer else Color.Transparent)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                            .pointerInput(candidate) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    onSelectCandidate(candidate)
                                }
                            },
                    )
                }
            }
        }
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
                val color = if (fading) colors.onSurface.copy(alpha = 0.3f) else colors.onSurface
                strokes.forEach { drawStroke(it, color) }
                if (currentStroke.size >= 2) drawStroke(currentStroke, color)
            }
            if (!modelReady) {
                Text(
                    text = stringResource(R.string.handwriting_model_missing),
                    color = colors.primary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            }
        }

        // 功能键行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PanelKey(
                text = stringResource(R.string.handwriting_undo_stroke),
                onClick = {
                    if (strokes.isNotEmpty()) {
                        strokes.removeAt(strokes.size - 1)
                        strokeCount = strokes.size
                        fading = false
                        if (strokes.isEmpty()) {
                            // 撤销到空 → 屏上活动字整段回滚
                            onUndoActive()
                        } else {
                            onStrokesChanged(strokes.toList(), gapsOf(strokes))
                        }
                    } else {
                        onUndoActive()
                    }
                },
                modifier = Modifier.weight(1.4f),
            )
            PanelKey(
                text = stringResource(R.string.handwriting_clear),
                onClick = { clearAll(finalize = true); onClear() },
                modifier = Modifier.weight(1f),
            )
            PanelKey(
                text = stringResource(R.string.handwriting_candidates),
                onClick = { },
                enabled = false,
                modifier = Modifier.weight(1f),
            )
            PanelKey(
                text = stringResource(R.string.handwriting_back_to_keyboard),
                onClick = onBackToKeyboard,
                modifier = Modifier.weight(1.6f),
                emphasized = true,
            )
        }
    }
}

/** 面板功能键：统一的圆角块（与语音面板的删除键同一视觉语言）。 */
@Composable
private fun PanelKey(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emphasized: Boolean = false,
) {
    val colors = MiuixTheme.colorScheme
    val background = when {
        !enabled -> colors.secondaryContainer.copy(alpha = 0.4f)
        emphasized -> colors.secondaryContainer
        else -> colors.secondaryContainer.copy(alpha = 0.7f)
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .pointerInput(text, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onClick()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = colors.onSecondaryContainer,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/** 笔间时间间隔：gaps[j] = 第 j 笔起笔与上一笔收笔的间隔（gaps[0] 恒为 0）。 */
private fun gapsOf(strokes: List<List<StrokePoint>>): List<Long> =
    strokes.mapIndexed { index, stroke ->
        if (index == 0) 0L
        else stroke.first().timeMs - strokes[index - 1].last().timeMs
    }

/** 按速度插值笔宽（与 Xime 手写键盘同一观感：快写细、慢写粗）。 */
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
        // 归一化速度：约 100 px 对应 1.0
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
