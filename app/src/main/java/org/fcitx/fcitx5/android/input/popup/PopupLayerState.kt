/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import org.fcitx.fcitx5.android.input.keyboard.KeyDef

/**
 * 弹窗层整体状态：一次重组渲染全部气泡与容器。
 *
 * 所有坐标为**相对 [PopupComponent.root] 左上角的 px**，与 Compose 的 `Modifier.offset` 对齐；
 * dp 换算在渲染层用 `LocalDensity` 完成。
 */
data class PopupLayerState(
    val entries: List<PopupEntryState> = emptyList(),
    val containers: List<PopupContainerState> = emptyList(),
)

/**
 * 按键按下预览气泡。
 *
 * @param text 当前显示内容（长按小键盘时为气泡底座，内容为空串）
 * @param lastShowTime 最近一次内容刷新时间，用于 100ms 最小显示时长判定
 */
class PopupEntryState(
    val viewId: Int,
    val text: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    /** 文字容器高度（原 `PopupEntryUi` 的 `keyHeight`），文字顶部对齐并被裁切在该区域内 */
    val contentHeight: Int,
    val lastShowTime: Long
) {
    fun withText(text: String, lastShowTime: Long) = PopupEntryState(
        viewId = viewId,
        text = text,
        x = x,
        y = y,
        width = width,
        height = height,
        contentHeight = contentHeight,
        lastShowTime = lastShowTime
    )
}

/**
 * 弹窗容器（长按小键盘 / 长按菜单）。
 *
 * 子类为普通 class 而非 data class：焦点变化时只需换一个新实例即可让 [PopupLayerState]
 * （data class，按 `equals` 比较列表元素）判定为已变更并触发重组；`keyOrders` 等大数组按引用复用，
 * 保证 `ChangeFocusAction` 热路径上零分配。
 */
sealed interface PopupContainerState {

    val viewId: Int

    /** 相对 [PopupComponent.root] 左上角（px）：`triggerBounds.left + offsetX - rootBounds.left` */
    val x: Int
    val y: Int

    /** 当前焦点索引（小键盘为键索引，菜单为 items 索引） */
    val focusedIndex: Int

    /** 手势坐标换算为容器内部坐标用：`x - offsetX` / `y - offsetY` */
    val offsetX: Int
    val offsetY: Int

    /** 长按小键盘：[keys] 为上屏字符，[labels] 为显示字符（标点转换后可能不同） */
    class Keyboard(
        override val viewId: Int,
        override val x: Int,
        override val y: Int,
        override val focusedIndex: Int,
        override val offsetX: Int,
        override val offsetY: Int,
        val keys: List<String>,
        val labels: List<String>,
        val keyOrders: Array<IntArray>,
        val rowCount: Int,
        val columnCount: Int,
        val keyWidth: Int,
        val keyHeight: Int
    ) : PopupContainerState {

        fun withFocus(index: Int) = Keyboard(
            viewId = viewId,
            x = x,
            y = y,
            focusedIndex = index,
            offsetX = offsetX,
            offsetY = offsetY,
            keys = keys,
            labels = labels,
            keyOrders = keyOrders,
            rowCount = rowCount,
            columnCount = columnCount,
            keyWidth = keyWidth,
            keyHeight = keyHeight
        )
    }

    /** 长按菜单（emoji 肤色等）：[columnOrder] 为显示顺序 */
    class Menu(
        override val viewId: Int,
        override val x: Int,
        override val y: Int,
        override val focusedIndex: Int,
        override val offsetX: Int,
        override val offsetY: Int,
        val items: List<KeyDef.Popup.Menu.Item>,
        val columnOrder: IntArray,
        val keySize: Int
    ) : PopupContainerState {

        fun withFocus(index: Int) = Menu(
            viewId = viewId,
            x = x,
            y = y,
            focusedIndex = index,
            offsetX = offsetX,
            offsetY = offsetY,
            items = items,
            columnOrder = columnOrder,
            keySize = keySize
        )
    }
}

/**
 * 渲染所需的视觉参数（Theme 属性 → Compose 类型），由渲染层在 Composition 内构造，
 * 因为需要 `LocalDensity` 与 `MiuixTheme` 上下文。
 */
data class PopupVisuals(
    val backgroundColor: Color,
    val textColor: Color,
    /** 小键盘焦点格背景 */
    val activeBackgroundColor: Color,
    val activeForegroundColor: Color,
    /** 菜单未聚焦圆底 = `accentKeyBackgroundColor` */
    val menuInactiveBackgroundColor: Color,
    /** 菜单聚焦圆底 = `keyPressHighlightColor` 叠加在 `accentKeyBackgroundColor` 上的结果 */
    val menuActiveBackgroundColor: Color,
    val menuIconTintColor: Color,
    val radius: Dp,
    val elevation: Dp
)
