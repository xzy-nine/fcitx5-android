/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.preedit

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.collectLatest
import top.yukonga.miuix.kmp.basic.Text
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

/** 光标条宽度 */
private val PreeditCursorWidth = 2.dp

/** 受控滚动时光标与视口边缘保留的余量 */
private val PreeditCursorMargin = 16.dp

/** 光标闪烁半周期（毫秒），一整个亮灭循环为两倍 */
private const val PreeditCursorBlinkHalfCycleMs = 500

/** 受控滚动动画时长（毫秒） */
private const val PreeditScrollDurationMs = 140

private val PreeditTextSize = 16.sp

/**
 * Compose 预编辑栏
 *
 * 上行文本：auxUp + preedit（含光标）
 * 下行文本：auxDown
 */
@Composable
fun ComposePreedit(
    state: PreeditState,
    visuals: PreeditVisuals,
    modifier: Modifier = Modifier,
) {
    if (!state.visible) return

    Column(
        modifier = modifier
            .wrapContentWidth()
            .background(visuals.backgroundColor)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        // 上行：auxUp + preedit（含光标）
        if (state.upText.isNotEmpty()) {
            PreeditLine(
                text = state.upText,
                cursor = state.upCursor,
                textColor = visuals.textColor,
            )
        }

        // 下行：auxDown
        if (state.downText.isNotEmpty()) {
            Text(
                text = state.downText,
                color = visuals.textColor,
                fontSize = PreeditTextSize,
            )
        }
    }
}

/**
 * 预编辑上行文本。
 *
 * 文本超出视口宽度时通过 [scrollState] 横向滚动，但这是**受控滚动**：
 * - 滚动只由「光标位置 / 文本 / 视口尺寸」变化驱动，逐字符地把光标滚进可视区域；
 * - 视口本身不接收拖拽与 fling 手势（[horizontalScroll] 的 `enabled = false`），
 *   因此不会出现手指甩动后的惯性滚动，也不会把光标甩出可视范围。
 */
@Composable
private fun PreeditLine(
    text: String,
    cursor: Int,
    textColor: Color,
) {
    val density = LocalDensity.current
    val cursorWidthPx = with(density) { PreeditCursorWidth.toPx() }
    val cursorMarginPx = with(density) { PreeditCursorMargin.toPx() }

    val scrollState = rememberScrollState()
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var viewportWidthPx by remember { mutableIntStateOf(0) }

    val currentCursor by rememberUpdatedState(cursor)
    // layoutResult / viewportWidthPx 刻意**不用** rememberUpdatedState 包裹：那样会在组合期
    // 读取 State，使每次文本布局回调都额外触发一次重组。snapshotFlow 的读取发生在协程里，
    // 同样能拿到最新值，且不会引起重组（cursor 是参数而非 State，仍需 rememberUpdatedState）。
    LaunchedEffect(Unit) {
        snapshotFlow { Triple(layoutResult, viewportWidthPx, currentCursor) }
            .collectLatest { (layout, viewportWidth, cursorPosition) ->
                if (layout == null || viewportWidth <= 0 || cursorPosition < 0) {
                    return@collectLatest
                }
                val target = calculateControlledScrollTarget(
                    layout = layout,
                    cursor = cursorPosition,
                    scroll = scrollState.value,
                    maxScroll = scrollState.maxValue,
                    viewportWidthPx = viewportWidth,
                    cursorWidthPx = cursorWidthPx,
                    marginPx = cursorMarginPx,
                ) ?: return@collectLatest
                scrollState.animateScrollTo(
                    target,
                    tween(durationMillis = PreeditScrollDurationMs)
                )
            }
    }

    // 光标闪烁：alpha 只持有 State，实际读取下沉到 draw 阶段，
    // 使每帧只触发重绘而不触发重组
    val cursorAlpha = if (cursor >= 0) preeditCursorBlinkAlpha() else null
    val onTextLayout: (TextLayoutResult) -> Unit = remember { { layoutResult = it } }

    Box(
        modifier = Modifier
            .onSizeChanged { viewportWidthPx = it.width }
            .clipToBounds()
            // 光标几何同样只在 draw 阶段读取：layoutResult 与 scrollState 的读取都落在绘制期，
            // 因此文本布局回调只引起重绘，不再引起预编辑栏的第二次重组
            .drawWithContent {
                drawContent()
                val layout = layoutResult ?: return@drawWithContent
                if (cursor < 0 || layout.lineCount == 0) return@drawWithContent
                val lineTop = layout.getLineTop(0)
                drawRect(
                    color = textColor,
                    // 内容已由 horizontalScroll 平移，绘制在未平移的坐标系里需自行减去滚动量
                    topLeft = Offset(cursorOffsetX(layout, cursor) - scrollState.value, lineTop),
                    size = Size(cursorWidthPx, layout.getLineBottom(0) - lineTop),
                    alpha = textColor.alpha * (cursorAlpha?.value ?: 1f),
                )
            }
            .horizontalScroll(scrollState, enabled = false)
    ) {
        // 用 Row 承载文本 + 尾部占位，使可滚动内容宽度 = 文本宽 + 光标宽。
        // 否则光标仅靠 offset 定位、不计入测量宽度，末尾光标在滚到最大位置时被裁掉。
        Row {
            Text(
                text = text,
                color = textColor,
                fontSize = PreeditTextSize,
                softWrap = false,
                maxLines = 1,
                onTextLayout = onTextLayout,
            )
            Spacer(modifier = Modifier.width(PreeditCursorWidth))
        }
    }
}

/**
 * 光标闪烁动画：1 秒一个亮灭循环（前 500ms 由亮到灭，后 500ms 由灭到亮）。
 *
 * 返回 [State] 而非当前值，供调用方在 draw 阶段读取，避免逐帧重组。
 */
@Composable
private fun preeditCursorBlinkAlpha(): State<Float> {
    val transition = rememberInfiniteTransition(label = "PreeditCursorBlink")
    return transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = PreeditCursorBlinkHalfCycleMs,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "PreeditCursorBlinkAlpha",
    )
}

/**
 * 光标在文本坐标系中的横向位置（px）。
 */
private fun cursorOffsetX(layout: TextLayoutResult, cursor: Int): Float {
    val length = layout.layoutInput.text.length
    // 光标在末尾时没有对应字符，直接取文本整体宽度
    if (cursor >= length) return layout.size.width.toFloat()
    return layout.getHorizontalPosition(cursor.coerceAtLeast(0), usePrimaryDirection = true)
}

/**
 * 计算受控滚动目标位置：仅在光标（含其前后余量）超出视口时给出新位置，
 * 并保证结果落在 [0, maxScroll] 内。返回 null 表示无需滚动。
 */
private fun calculateControlledScrollTarget(
    layout: TextLayoutResult,
    cursor: Int,
    scroll: Int,
    maxScroll: Int,
    viewportWidthPx: Int,
    cursorWidthPx: Float,
    marginPx: Float,
): Int? {
    val length = layout.layoutInput.text.length
    if (length == 0 || viewportWidthPx <= 0) return null
    val cursorLeft = cursorOffsetX(layout, cursor.coerceIn(0, length))
    val cursorRight = cursorLeft + cursorWidthPx
    val desired = when {
        cursorLeft - marginPx < scroll -> (cursorLeft - marginPx).coerceAtLeast(0f)
        cursorRight + marginPx > scroll + viewportWidthPx ->
            (cursorRight + marginPx - viewportWidthPx).coerceAtLeast(0f)

        else -> scroll.toFloat()
    }.coerceIn(0f, maxScroll.coerceAtLeast(0).toFloat())
    return if ((desired - scroll).absoluteValue >= 0.5f) desired.roundToInt() else null
}
