/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import android.graphics.Rect
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 按键弹窗的纯计算：定位与焦点换算。
 *
 * 逐行对齐原 View 实现（[PopupContainerUi] 的 `calcInitialFocusedColumn` / `createColumnOrder` /
 * `limitIndex`，[PopupKeyboardUi] 的 `offsetX` / `offsetY` / `keyOrders` / `onChangeFocus`，
 * [PopupMenuUi] 的 `offsetX` / `offsetY` / `onChangeFocus`，[PopupComponent] 的气泡与容器定位）。
 *
 * 之所以复制而不是直接复用：`PopupContainerUi` 等文件是上游文件，按项目约定保持零改动以避免合并冲突，
 * 旧实现已断开接线、仅保留供对比。
 *
 * 本文件不依赖任何 View，可被 Compose 渲染层直接调用。
 */
object PopupLayoutMath {

    /** 手势越界：应关闭弹窗并消费手势（不再派发给触发键） */
    const val FOCUS_DISMISS = -1

    /** 焦点未落在任何按键上：保持当前焦点，且不消费手势 */
    const val FOCUS_KEEP = -2

    fun limitIndex(i: Int, limit: Int) = if (i < 0) 0 else if (i >= limit) limit - 1 else i

    /**
     * 初始焦点列：优先居中，左右空间不足时依次让位。
     * 对应 [PopupContainerUi.calcInitialFocusedColumn]。
     */
    fun calcInitialFocusedColumn(
        columnCount: Int,
        columnWidth: Int,
        outerBounds: Rect,
        triggerBounds: Rect
    ): Int {
        // assume trigger bounds inside outer bounds
        val leftSpace = triggerBounds.left - outerBounds.left
        val rightSpace = outerBounds.right - triggerBounds.right
        var col = (columnCount - 1) / 2
        while (columnWidth * col > leftSpace) col--
        while (columnWidth * (columnCount - col - 1) > rightSpace) col++
        return col.coerceIn(0, columnCount - 1)
    }

    /**
     * 列显示顺序：中心优先，然后右、左交替展开。
     * 对应 [PopupContainerUi.createColumnOrder]。
     *
     * ```
     * | 6 | 4 | 2 | 0 | 1 | 3 | 5 |
     * ```
     */
    fun createColumnOrder(columnCount: Int, initialFocus: Int): IntArray = IntArray(columnCount).also {
        var order = 0
        it[initialFocus] = order++
        for (i in 1 until columnCount * 2) {
            val sign = if (i % 2 == 0) -1 else 1
            val delta = (i / 2f).roundToInt()
            val nextColumn = initialFocus + sign * delta
            if (nextColumn < 0 || nextColumn >= columnCount) continue
            it[nextColumn] = order++
        }
    }

    /**
     * 长按小键盘的行列数：每行最多 5 个键（对应 [PopupKeyboardUi] 的 `rowCount` / `columnCount`）。
     *
     * 列数用**向上取整**而非四舍五入：原实现的 `roundToInt` 会让行×列容量小于键数，
     * 从而静默丢掉末尾的键（例如 `PopupPreset["e"]` 有 13 个键：rowCount=3、round(13/3)=4，
     * 容量 12 < 13，最后一个 `ə` 永远不会显示）。向上取整保证容量不小于键数。
     */
    fun keyboardGrid(keyCount: Int): Pair<Int, Int> {
        val rowCount = ceil(keyCount.toFloat() / 5).toInt()
        val columnCount = ceil(keyCount.toFloat() / rowCount).toInt()
        return rowCount to columnCount
    }

    /**
     * 行×列 → 键索引矩阵。行号小的显示在下方（渲染时按行倒序添加）。
     *
     * ```
     * [[2, 0, 1, 3], [6, 4, 5, 7]] 显示为
     * | 6 | 4 | 5 | 7 |
     * | 2 | 0 | 1 | 3 |
     * ```
     */
    fun computeKeyOrders(rowCount: Int, columnCount: Int, columnOrder: IntArray): Array<IntArray> =
        Array(rowCount) { row -> IntArray(columnCount) { col -> row * columnCount + columnOrder[col] } }

    /**
     * 预览气泡相对 [PopupComponent.root] 左上角的位置（px）。
     * 底边与触发键的边框底边对齐，水平居中。
     */
    fun computeEntryPosition(
        bounds: Rect,
        popupWidth: Int,
        popupHeight: Int,
        keyBottomMargin: Int
    ): Pair<Int, Int> =
        ((bounds.left + bounds.right - popupWidth) / 2) to (bounds.bottom - popupHeight - keyBottomMargin)

    /**
     * 小键盘相对触发键的偏移（px）。坐标变换见 [PopupKeyboardUi] 中 `offsetX` / `offsetY` 的注释图。
     */
    fun computeKeyboardOffset(
        triggerBounds: Rect,
        keyWidth: Int,
        keyHeight: Int,
        popupHeight: Int,
        rowCount: Int,
        focusColumn: Int
    ): Pair<Int, Int> =
        (((triggerBounds.width() - keyWidth) / 2) - (keyWidth * focusColumn)) to
                ((triggerBounds.height() - popupHeight) - (keyHeight * (rowCount - 1)))

    /** 长按菜单相对触发键的偏移（px）。 */
    fun computeMenuOffset(
        triggerBounds: Rect,
        keySize: Int,
        focusColumn: Int,
        verticalOffset: Int
    ): Pair<Int, Int> =
        (((triggerBounds.width() - keySize) / 2) - (keySize * focusColumn)) to verticalOffset

    /**
     * 小键盘手势坐标（已减去容器偏移，相对容器左上角）→ 新焦点键索引。
     *
     * @return 有效键索引，或 [FOCUS_DISMISS] / [FOCUS_KEEP]。语义对齐 [PopupKeyboardUi.onChangeFocus]：
     * 行列超出 ±2 范围时关闭弹窗并消费手势；落在越界格子上时保持焦点且不消费手势。
     */
    fun keyboardFocusIndex(
        x: Float,
        y: Float,
        rowCount: Int,
        columnCount: Int,
        keyWidth: Int,
        keyHeight: Int,
        keyCount: Int,
        keyOrders: Array<IntArray>
    ): Int {
        // move to next row when gesture moves above 30% from bottom of current row
        var newRow = rowCount - (y / keyHeight - 0.2f).roundToInt()
        // move to next column when gesture moves out of current column
        var newColumn = floor(x / keyWidth).toInt()
        // retain focus when gesture moves between ±2 rows/columns of range
        if (newRow < -2 || newRow > rowCount + 1 || newColumn < -2 || newColumn > columnCount + 1) {
            return FOCUS_DISMISS
        }
        newRow = limitIndex(newRow, rowCount)
        newColumn = limitIndex(newColumn, columnCount)
        val newFocus = keyOrders[newRow][newColumn]
        // 键数不是行列数整数倍时，矩阵尾部存在空位
        return if (newFocus < keyCount) newFocus else FOCUS_KEEP
    }

    /**
     * 长按菜单手势坐标（已减去容器偏移）→ 新焦点项索引。
     *
     * @return 有效项索引，或 [FOCUS_DISMISS] / [FOCUS_KEEP]。语义对齐 [PopupMenuUi.onChangeFocus]。
     */
    fun menuFocusIndex(
        x: Float,
        columnCount: Int,
        keySize: Int,
        columnOrder: IntArray
    ): Int {
        var newColumn = floor(x / keySize).toInt()
        if (newColumn < -2 || newColumn > columnCount + 1) {
            return FOCUS_DISMISS
        }
        newColumn = limitIndex(newColumn, columnCount)
        val newFocus = columnOrder[newColumn]
        return if (newFocus < columnCount) newFocus else FOCUS_KEEP
    }

    /**
     * 一行中实际绘制的键索引与整行对齐方式。
     *
     * 原实现（[PopupKeyboardUi.root]）用 `horizontalLayout` 跳过越界的空位，并在遇到空位时把整行
     * gravity 设为 `Start` / `End`（空位落在首列时为 `End`），因此这里一并把结果算好，
     * 使渲染层在焦点变化的每帧上零分配。
     */
    class DisplayRow(val indices: IntArray, val alignEnd: Boolean)

    /**
     * 计算显示用的行列表。
     *
     * **顺序为自下而上**（行号小的显示在下方）：原实现是 `for (i in rowCount - 1 downTo 0)` 逐个
     * `addView`，因为后加入的 View 显示在下方；这里直接倒序产出，渲染层顺序遍历即可。
     */
    fun computeDisplayRows(
        keyOrders: Array<IntArray>,
        rowCount: Int,
        columnCount: Int,
        keyCount: Int
    ): List<DisplayRow> {
        val rows = ArrayList<DisplayRow>(rowCount)
        for (row in rowCount - 1 downTo 0) {
            val present = ArrayList<Int>(columnCount)
            var alignEnd = false
            for (col in 0 until columnCount) {
                val index = keyOrders[row][col]
                if (index >= keyCount) {
                    alignEnd = col == 0
                } else {
                    present.add(index)
                }
            }
            rows.add(DisplayRow(present.toIntArray(), alignEnd))
        }
        return rows
    }
}
