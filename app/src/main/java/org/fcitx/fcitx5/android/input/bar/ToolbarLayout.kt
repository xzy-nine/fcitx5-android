/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import kotlin.math.abs

/**
 * 可移动的工具栏按钮。
 *
 * [id] 是持久化标识（偏好里存的就是它），改名会让已有布局回落到默认值，故不可随意修改。
 */
enum class ToolbarButton(val id: String) {
    TextEditing("text_editing"),
    Clipboard("clipboard"),
    Tune("tune"),
    Voice("voice"),
    Handwriting("handwriting"),
    SplitKeyboard("split_keyboard"),
}

/**
 * 工具栏按钮布局：两个互斥的有序列表。
 *
 * [docked] 里的按钮显示在工具栏可停靠行，[pool] 里的按钮显示在更多页的按钮池。
 * 两者互不相交、并集恒为 [ToolbarButton] 全集（见 [sanitize] 的不变量）。
 */
data class ToolbarLayoutState(
    val docked: List<ToolbarButton>,
    val pool: List<ToolbarButton>,
)

/**
 * 布局的编解码与纯计算。
 *
 * 与 Compose / Android 无关：拖动落点判定只吃原始坐标，便于 JVM 单测覆盖边界情形。
 */
object ToolbarLayout {

    /** 默认停靠顺序（工具栏可停靠行从左到右）。 */
    val DEFAULT_DOCKED: List<ToolbarButton> = listOf(
        ToolbarButton.TextEditing,
        ToolbarButton.Clipboard,
        ToolbarButton.Tune,
        ToolbarButton.Voice,
        ToolbarButton.Handwriting,
    )

    /** 默认按钮池（更多页里未停靠的按钮）。 */
    val DEFAULT_POOL: List<ToolbarButton> = listOf(ToolbarButton.SplitKeyboard)

    val ALL: List<ToolbarButton> = ToolbarButton.entries.toList()

    val DEFAULT: ToolbarLayoutState = ToolbarLayoutState(DEFAULT_DOCKED, DEFAULT_POOL)

    /** 一个矩形槽位的原始坐标（避免依赖 Compose 的 Rect，使其可在单测里构造）。 */
    data class SlotRect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
    }

    fun encode(buttons: List<ToolbarButton>): String = buttons.joinToString(",") { it.id }

    /** 解码：未知 id 直接丢弃（孤儿 id 与旧版本残留由此自愈）。 */
    fun decode(raw: String): List<ToolbarButton> =
        raw.split(',')
            .mapNotNull { id -> ALL.firstOrNull { it.id == id } }

    /**
     * 由持久化字符串还原出合法布局。
     *
     * 不变量：`docked` 去重且只含已知按钮；`pool` 与 `docked` 互斥；
     * `docked + pool` 恰好是全集 —— 缺失的按钮按默认顺序补进 `pool`，
     * 因此新增枚举项不会导致按钮「消失」。
     */
    fun sanitize(dockedRaw: String, poolRaw: String): ToolbarLayoutState {
        val docked = decode(dockedRaw).distinct()
        val pool = (decode(poolRaw) + DEFAULT_DOCKED + DEFAULT_POOL)
            .distinct()
            .filterNot { it in docked }
        return ToolbarLayoutState(docked, pool)
    }

    /** 解码结果与原文不一致（有非法/重复/缺失项）时为 true，调用方据此把自愈结果回写偏好。 */
    fun needsRewrite(state: ToolbarLayoutState, dockedRaw: String, poolRaw: String): Boolean =
        encode(state.docked) != dockedRaw || encode(state.pool) != poolRaw

    /**
     * 把 [button] 移到 [docked] 的目标位置（并把它从池子里移除）。
     *
     * [index] 是**移除该按钮之后**的列表里的插入位（0..剩余长度）—— 拖动时的目标指示线
     * 画的正是这个语义：把按钮插到「剩余按钮」中第 index 个之前。因此同一个 index
     * 在「同区重排」与「跨区拖入」两种情形下含义一致，无需再按来源位置修正。
     */
    fun moveToDocked(
        state: ToolbarLayoutState,
        button: ToolbarButton,
        index: Int,
    ): ToolbarLayoutState {
        val docked = insert(state.docked, button, index)
        return state.copy(docked = docked, pool = state.pool.filterNot { it == button })
    }

    /** 把 [button] 移到按钮池的目标位置（并把它从工具栏移除）。语义同 [moveToDocked]。 */
    fun moveToPool(
        state: ToolbarLayoutState,
        button: ToolbarButton,
        index: Int,
    ): ToolbarLayoutState {
        val pool = insert(state.pool, button, index)
        return state.copy(pool = pool, docked = state.docked.filterNot { it == button })
    }

    /** 插入位按「先摘除 [button] 再插入」解释，见 [moveToDocked]。 */
    private fun insert(
        list: List<ToolbarButton>,
        button: ToolbarButton,
        index: Int,
    ): List<ToolbarButton> = list.toMutableList().apply {
        remove(button)
        add(index.coerceIn(0, size), button)
    }

    /**
     * 横向一行的插入位：取最近的槽位中心，再按指针落在该中心的哪一侧决定插在其前/后。
     *
     * [centers] 必须按**列表顺序**给出（第 i 项对应列表索引 i），返回值即插入索引。
     * LTR 下列表顺序与视觉左右一致，RTL 下相反 —— 因此 RTL 时「落在中心左侧」等价于
     * 「插在该项之后」，这正是 `rtl` 只翻转比较方向的原因。
     *
     * **调用方需传入「已摘除被拖动按钮」后的槽位中心**，与 [moveToDocked] 的索引口径一致；
     * 被拖走的按钮本身不参与落点判定，也就不可能把它放回自己的原位。
     * 返回 0..centers.size。等距时取靠前者，保证同一坐标的结果唯一。
     */
    fun insertionIndexByCenterX(centers: List<Float>, x: Float, rtl: Boolean): Int {
        if (centers.isEmpty()) return 0
        var nearest = 0
        var best = Float.MAX_VALUE
        centers.forEachIndexed { index, center ->
            val distance = abs(center - x)
            if (distance < best) {
                best = distance
                nearest = index
            }
        }
        val after = if (rtl) x < centers[nearest] else x > centers[nearest]
        return nearest + if (after) 1 else 0
    }

    /**
     * 网格（行主序）的插入位：取中心距最近的一格，再按指针落在该格中心的哪一侧决定插在前/后。
     *
     * 与 [insertionIndexByCenterX] 同口径：[cells] 需为「已摘除被拖动按钮」后的格子。
     * 点在所有格子之外时依旧返回最近格子给出的索引，由调用方决定「网格外」是否算有效落点。
     */
    fun insertionIndexByCellBounds(cells: List<SlotRect>, x: Float, y: Float): Int {
        if (cells.isEmpty()) return 0
        var nearest = 0
        var best = Float.MAX_VALUE
        cells.forEachIndexed { index, cell ->
            val dx = cell.centerX - x
            val dy = cell.centerY - y
            val distance = dx * dx + dy * dy
            if (distance < best) {
                best = distance
                nearest = index
            }
        }
        return nearest + if (x > cells[nearest].centerX) 1 else 0
    }
}
