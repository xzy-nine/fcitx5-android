/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.expanded

/**
 * 「展开候选」表格翻页的目标下标计算（纯函数，可单测）。
 *
 * 两个方向都是**一整屏行数**的位移：目标行的首项滚到视口顶部
 * （等价于 View 侧 `GridExpandedCandidateWindow` 的 `LinearSmoothScroller(SNAP_TO_START)`）。
 *
 * - 下翻：目标行 = 最后一个完全可见行的下一行；
 * - 上翻：目标行 = 第一个完全可见行往前 `rowsPerPage` 行（`rowsPerPage` = 视口内完全可见的行数）。
 *
 * 上翻**不能只取「第一个完全可见项 - 1」**：行对齐的普通状态下那只会往上滚一行，看起来就是
 * 「翻行」而不是「翻页」；想靠 `scrollOffset` 把目标行贴到视口下沿也不行——那需要传负偏移，
 * 与 `LazyGridScrollPosition` 的 `scrollOffset >= 0` 前置条件冲突。
 *
 * 表格是等宽等高格子、同行各项共享同一 y，因此「行下标 = item 下标 / 列数」成立。
 *
 * @param firstVisibleIndex 第一个**完全可见**项的 item 下标；没有完全可见项时传第一个可见项。
 * @param lastVisibleIndex 最后一个**完全可见**项的 item 下标；没有完全可见项时传最后一个可见项。
 * @param columns 列数（`LazyGridLayoutInfo.maxSpan`）。
 * @param totalItemsCount 条目总数。
 * @param forward `true` = 下翻，`false` = 上翻。
 * @return 目标 item 下标，恒落在 `[0, totalItemsCount-1]`；列表为空时返回 0。
 */
fun computePageTargetIndex(
    firstVisibleIndex: Int,
    lastVisibleIndex: Int,
    columns: Int,
    totalItemsCount: Int,
    forward: Boolean,
): Int {
    if (totalItemsCount <= 0) return 0
    val span = columns.coerceAtLeast(1)
    val lastIndex = totalItemsCount - 1
    return if (forward) {
        (lastVisibleIndex + 1).coerceAtMost(lastIndex)
    } else {
        val firstRow = firstVisibleIndex.coerceAtLeast(0) / span
        val lastRow = lastVisibleIndex.coerceAtLeast(0) / span
        // 视口内完全可见的行数，至少 1 行
        val rowsPerPage = (lastRow - firstRow + 1).coerceAtLeast(1)
        ((firstRow - rowsPerPage).coerceAtLeast(0) * span).coerceAtMost(lastIndex)
    }
}
