/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * 把本元素的窗口矩形登记为 [button] 的落点槽位。
 *
 * 用窗口绝对坐标而不是局部坐标：工具栏行与更多页网格是两个独立 Composition，
 * 只有统一到窗口口径，跨区拖动才能互相命中（见 [ToolbarDragState]）。
 */
fun Modifier.toolbarDockSlot(
    drag: ToolbarDragState,
    button: ToolbarButton,
    inPool: Boolean,
): Modifier = onGloballyPositioned { coordinates ->
    val bounds = coordinates.boundsInWindow()
    if (inPool) drag.poolSlots[button] = bounds else drag.dockedSlots[button] = bounds
}

/** 把本元素的窗口矩形登记为可放置区域（可停靠行的视口 / 按钮池网格的视口）。 */
fun Modifier.toolbarDropViewport(
    drag: ToolbarDragState,
    inPool: Boolean,
): Modifier = onGloballyPositioned { coordinates ->
    val bounds = coordinates.boundsInWindow()
    if (inPool) {
        drag.poolViewport = bounds
    } else {
        drag.dockedViewport = bounds
    }
}

/**
 * 上报可停靠行的布局方向。
 *
 * 横向插入方向只由可停靠行决定（按钮池所在窗口被强制 LTR），所以必须由行自己写，
 * 不能让每个拖动源按各自的布局方向传入 —— 否则「从池子拖向 RTL 工具栏」会算反。
 */
fun Modifier.toolbarDockedDirection(
    drag: ToolbarDragState,
    rtl: Boolean,
): Modifier = onGloballyPositioned {
    drag.dockedRtl = rtl
}

/**
 * 长按后拖动以重排/跨区移动 [button]，仅在编辑模式（[enabled]）生效。
 *
 * 只接管**长按之后**的拖动，普通滑动照旧：手指先移动会让父级滚动消费掉事件，
 * 长按随之被取消（正常滚动）；先停住再拖则长按先成立、后续位移被本修饰符消费，
 * 父级滚动不再介入（重排）。因此「长按拖=重排，普通滑=滚动」这一分工无需额外开关。
 *
 * 布局顺序不在参数里：由 [ToolbarDragState.layoutProvider] 在每次回调时现取，
 * 否则本修饰符的 `pointerInput` 块会捕获到重排前的陈旧列表。
 * 松手时把落点交给 [onDrop]；落在无效区域时 [onDrop] 收到 null，调用方据此回弹。
 */
@Composable
fun Modifier.toolbarDragSource(
    drag: ToolbarDragState,
    button: ToolbarButton,
    enabled: Boolean,
    onDrop: (ToolbarLayout.Drop?) -> Unit,
): Modifier {
    if (!enabled) return this
    val haptics = LocalHapticFeedback.current
    // 元素自身的窗口原点：指针的局部坐标加上它才等于窗口坐标，与槽位矩形同口径
    var origin by remember { mutableStateOf<LayoutCoordinates?>(null) }

    return this
        .onGloballyPositioned { origin = it }
        .pointerInput(button) {
            detectDragGesturesAfterLongPress(
                onDragStart = { offset ->
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    drag.begin(button)
                    drag.moveTo(toWindow(origin, offset))
                },
                onDrag = { change, _ ->
                    change.consume()
                    drag.moveTo(toWindow(origin, change.position))
                },
                onDragEnd = { onDrop(drag.end()) },
                onDragCancel = {
                    drag.cancel()
                    onDrop(null)
                },
            )
        }
}

/** 局部坐标 → 窗口坐标；拿不到几何时退回原值（宁可落点偏高，也不要崩）。 */
private fun toWindow(origin: LayoutCoordinates?, local: Offset): Offset =
    origin?.let { it.positionInWindow() + local } ?: local

/**
 * 拖动接近容器边缘时持续自动滚动。
 *
 * 可停靠行横向、按钮池网格纵向都可能放不下全部按钮，没有自动滚动就够不到远端槽位。
 * 长按拖动期间父级滚动已被本修饰符的消费挡住，因此这里必须自己按帧推进：
 * 只要手指停在边缘带内，就每帧滚一段，速度按深入程度线性上升。
 *
 * [scrollBy] 由调用方提供（行传 `scrollState::scrollBy`，网格传 `gridState::scrollBy`）。
 * [edgePx] 是触发带宽度，[maxStepPx] 是贴到最边缘时每帧的位移上限。
 */
@Composable
fun toolbarEdgeAutoScroll(
    drag: ToolbarDragState,
    inPool: Boolean,
    horizontal: Boolean,
    edgePx: Float,
    maxStepPx: Float,
    scrollBy: suspend (Float) -> Unit,
) {
    LaunchedEffect(drag.dragging, inPool, horizontal) {
        while (drag.dragging != null) {
            withFrameNanos { }
            val viewport = (if (inPool) drag.poolViewport else drag.dockedViewport)
                ?: continue
            val position = drag.pointer
            if (!viewport.contains(position)) continue
            val delta = if (horizontal) {
                horizontalDelta(position.x, viewport.left, viewport.right, edgePx, maxStepPx)
            } else {
                horizontalDelta(position.y, viewport.top, viewport.bottom, edgePx, maxStepPx)
            }
            if (delta != 0f) scrollBy(delta)
        }
    }
}

/** 边缘带内的滚动增量：靠近 start 为负、靠近 end 为正、中间为 0。 */
private fun horizontalDelta(
    value: Float,
    start: Float,
    end: Float,
    edgePx: Float,
    maxStepPx: Float,
): Float = when {
    value < start + edgePx ->
        -maxStepPx * ((start + edgePx - value) / edgePx).coerceIn(0f, 1f)
    value > end - edgePx ->
        maxStepPx * ((value - (end - edgePx)) / edgePx).coerceIn(0f, 1f)
    else -> 0f
}
