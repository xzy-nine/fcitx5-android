/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/** 幽灵图标的外框边长（与工具栏按钮的点击区一致）。 */
private val DRAG_GHOST_SIZE = 40.dp

/**
 * 拖动浮层：跟随手指的幽灵图标 + 目标槽高亮。
 *
 * 挂在 IME 根组合、铺满整个 IME 窗口，因此它的坐标系就是**窗口绝对坐标**，
 * 与 [ToolbarDragState] 里上报的槽位矩形口径一致，可以直接拿矩形做位移。
 *
 * 只**绘制**不消费触摸（没有任何 pointer handler）：工具栏行与更多页网格各自持有手势，
 * 这里若也参与命中就会抢走它们的事件。
 */
@Composable
fun ToolbarDragOverlay(
    drag: ToolbarDragState,
    modifier: Modifier = Modifier,
) {
    val dragging = drag.dragging ?: return
    val density = LocalDensity.current
    // 幽灵图标以手指为中心，故偏移要减掉半个外框
    val halfPx = with(density) { DRAG_GHOST_SIZE.toPx() / 2f }
    val pointer = drag.pointer

    Box(modifier = modifier.fillMaxSize()) {
        // 目标槽高亮画在幽灵图标之下，避免反过来遮住手指
        drag.target?.anchor?.let { anchor ->
            HighlightSlot(anchor = anchor)
        }

        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (pointer.x - halfPx).roundToInt(),
                        (pointer.y - halfPx).roundToInt(),
                    )
                }
                .size(DRAG_GHOST_SIZE)
                // 半透明：能看清下面的原按钮，同时明确「这是被拖动的那一个」
                .graphicsLayer { alpha = 0.82f }
                .clip(RoundedCornerShape(DRAG_GHOST_SIZE / 2))
                .background(MiuixTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            ToolbarButtonIcon(
                button = dragging,
                tint = MiuixTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** 目标槽高亮：半透明主题色圆角块，尺寸取自槽位矩形。 */
@Composable
private fun HighlightSlot(anchor: ToolbarLayout.SlotRect) {
    val density = LocalDensity.current
    val width = with(density) { (anchor.right - anchor.left).toDp() }
    val height = with(density) { (anchor.bottom - anchor.top).toDp() }
    Box(
        modifier = Modifier
            .offset { IntOffset(anchor.left.roundToInt(), anchor.top.roundToInt()) }
            .size(width = width, height = height)
            .clip(RoundedCornerShape(8.dp))
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.22f))
    )
}
