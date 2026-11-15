/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * 拖动的瞬时状态。
 *
 * 工具栏行与更多页网格是两个独立 Composition，但同处一个进程、共享同一份快照状态，
 * 因此把「手指在哪、目标是谁」放在这里，两侧各自读写即可实现跨层拖动，
 * 不需要真正的跨 ComposeView 拖放通道。
 *
 * 坐标一律用**窗口绝对坐标**：槽位矩形由两侧用 `boundsInWindow()` 上报，
 * 指针位置由各自的 `onGloballyPositioned` 原点换算，两处口径一致才能互相命中。
 *
 * 落点判定本身是纯函数（[ToolbarLayout.resolveDrop]），这里只负责持有状态与做坐标换算。
 */
class ToolbarDragState {

    /**
     * 当前布局的读取入口，由 [ToolbarEditComponent] 注入。
     *
     * 刻意做成**在拖动回调里现取**而不是由调用方把列表传进来：手势回调跑在
     * `pointerInput` 的挂起块里，该块只在 key 变化时重启，闭包捕获的列表会随着草稿
     * 重排而变成陈旧值（拖动过程中布局每落一次就变一次）。现取可保证插入索引永远
     * 与当前渲染顺序一致。
     */
    internal var layoutProvider: () -> ToolbarLayoutState = { ToolbarLayout.DEFAULT }

    /** 正在被拖动的按钮；null 表示没有拖动进行中。 */
    var dragging by mutableStateOf<ToolbarButton?>(null)
        private set

    /** 手指的窗口绝对坐标（也供边缘自动滚动判断）。 */
    var pointer by mutableStateOf(Offset.Zero)
        private set

    /** 当前落点；null 表示落在无效区域（不高亮、松手回弹）。 */
    var target by mutableStateOf<ToolbarLayout.Drop?>(null)
        private set

    /** 可停靠行在窗口里的可视矩形（滚动视口），由行自己上报。 */
    var dockedViewport by mutableStateOf<Rect?>(null)

    /**
     * 可停靠行是否处于 RTL 布局。
     *
     * 横向插入方向只由**可停靠行**决定（按钮池所在窗口被强制 LTR，见 `createComposeWindowView`），
     * 因此由行在上报视口时一并写入，而不是让调用方各自按自己的布局方向传入 ——
     * 否则「从池子拖向 RTL 工具栏」会用到池子的 LTR 方向而算反。
     */
    var dockedRtl by mutableStateOf(false)

    /** 按钮池网格在窗口里的可视矩形，由网格自己上报。 */
    var poolViewport by mutableStateOf<Rect?>(null)

    /** 可停靠行里各按钮的窗口矩形。 */
    val dockedSlots = mutableStateMapOf<ToolbarButton, Rect>()

    /** 按钮池里各按钮的窗口矩形。 */
    val poolSlots = mutableStateMapOf<ToolbarButton, Rect>()

    val isDragging: Boolean get() = dragging != null

    fun begin(button: ToolbarButton) {
        dragging = button
        target = null
    }

    /** 更新手指位置并重算落点；布局顺序在回调时现取（见 [layoutProvider]）。 */
    fun moveTo(position: Offset) {
        val button = dragging ?: return
        pointer = position
        val layout = layoutProvider()
        target = ToolbarLayout.resolveDrop(
            button = button,
            x = position.x,
            y = position.y,
            docked = layout.docked,
            dockedSlots = dockedSlots.mapValues { it.value.toSlotRect() },
            dockedViewport = dockedViewport?.toSlotRect(),
            pool = layout.pool,
            poolSlots = poolSlots.mapValues { it.value.toSlotRect() },
            poolViewport = poolViewport?.toSlotRect(),
            rtl = dockedRtl,
        )
    }

    /** 结束拖动并返回落点（调用方据此提交草稿）。 */
    fun end(): ToolbarLayout.Drop? {
        val result = target
        dragging = null
        target = null
        return result
    }

    /** 放弃本次拖动。 */
    fun cancel() {
        dragging = null
        target = null
    }

    /** 视图树变化后清掉上报的几何，避免用陈旧矩形做命中判定。 */
    fun clearGeometry() {
        dockedSlots.clear()
        poolSlots.clear()
        dockedViewport = null
        poolViewport = null
    }
}

private fun Rect.toSlotRect() = ToolbarLayout.SlotRect(left, top, right, bottom)
