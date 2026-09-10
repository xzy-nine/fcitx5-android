/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.popup.PopupContainerState.Keyboard
import org.fcitx.fcitx5.android.input.popup.PopupContainerState.Menu

/** 弹窗文字字号，等价原 `AutoScaleTextView.textSize = 23f` */
private val PopupTextSize = 23.sp

/** 菜单格子边长，等价原 `keySize = context.dp(48)` */
private val MenuCellSize = 48.dp

/** 菜单未聚焦圆底直径，等价原 `InsetDrawable` 的 `keySize - dp(33) / 2` 内缩 */
private val MenuInactiveCircleSize = 33.dp

/** 菜单聚焦圆底直径，等价原 `InsetDrawable` 的 `keySize - dp(34) / 2` 内缩 */
private val MenuActiveCircleSize = 34.dp

/**
 * 按键弹窗层渲染。
 *
 * 由 [PopupComponent] 持有的 `ComposeView` 调用（未来也可直接并入 `composeTopView` 的单一 Composition）。
 * 本层**不接收触摸**：所有手势都由 `BaseKeyboard` / `PickerPageUi` 侧的 `CustomGestureView` 捕获后，
 * 通过 [PopupActionListener] 的 `ChangeFocusAction` / `TriggerAction` 转发进来，因此这里只做绘制。
 *
 * 定位全部使用「相对 [PopupComponent.root] 左上角的 px」+ [Modifier.offset]，并用
 * [LayoutDirection.Ltr] 固定方向，等价原 View 的 `root.layoutDirection = LTR`。
 */
@Composable
fun PopupContent(
    state: PopupLayerState,
    visuals: PopupVisuals,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            state.entries.forEach { entry ->
                // key(viewId) 保证身份稳定：单键按下/抬起只重组一个气泡
                key(entry.viewId) { PopupEntryItem(entry, visuals) }
            }
            state.containers.forEach { container ->
                key(container.viewId) {
                    when (container) {
                        is Keyboard -> PopupKeyboardItem(container, visuals)
                        is Menu -> PopupMenuItem(container, visuals)
                    }
                }
            }
        }
    }
}

/**
 * 按键预览气泡（原 [PopupEntryUi]）。
 *
 * 文字顶部对齐、高度为 `keyHeight`、水平居中，且**不做缩放**：原 `AutoScaleTextView` 未设置
 * `scaleMode`，默认 `Mode.None` 即溢出直接裁切。
 */
@Composable
private fun PopupEntryItem(entry: PopupEntryState, visuals: PopupVisuals) {
    val density = LocalDensity.current
    Surface(
        modifier = Modifier
            .offset { IntOffset(entry.x, entry.y) }
            .size(
                width = with(density) { entry.width.toDp() },
                height = with(density) { entry.height.toDp() },
            ),
        shape = RoundedCornerShape(visuals.radius),
        color = visuals.backgroundColor,
        shadowElevation = visuals.elevation,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(density) { entry.contentHeight.toDp() })
                .clipToBounds(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = entry.text,
                color = visuals.textColor,
                fontSize = PopupTextSize,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

/**
 * 长按弹出的小键盘（原 [PopupKeyboardUi]）。
 */
@Composable
private fun PopupKeyboardItem(state: Keyboard, visuals: PopupVisuals) {
    val density = LocalDensity.current
    val keyWidth = with(density) { state.keyWidth.toDp() }
    val keyHeight = with(density) { state.keyHeight.toDp() }
    val keyWidthPx = state.keyWidth.toFloat()

    // 行布局只与网格结构有关，焦点变化时不重算（热路径零分配）
    val rows = remember(state.keyOrders, state.labels, state.rowCount, state.columnCount) {
        PopupLayoutMath.computeDisplayRows(
            keyOrders = state.keyOrders,
            rowCount = state.rowCount,
            columnCount = state.columnCount,
            keyCount = state.labels.size,
        )
    }

    Surface(
        modifier = Modifier.offset { IntOffset(state.x, state.y) },
        shape = RoundedCornerShape(visuals.radius),
        color = visuals.backgroundColor,
        shadowElevation = visuals.elevation,
    ) {
        Column(modifier = Modifier.width(keyWidth * state.columnCount)) {
            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (row.alignEnd) Arrangement.End else Arrangement.Start,
                ) {
                    row.indices.forEach { index ->
                        PopupKeyCell(
                            label = state.labels[index],
                            focused = index == state.focusedIndex,
                            keyWidth = keyWidth,
                            keyHeight = keyHeight,
                            keyWidthPx = keyWidthPx,
                            visuals = visuals,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 小键盘的单个按键（原 [PopupKeyboardUi.PopupKeyUi]）。
 */
@Composable
private fun PopupKeyCell(
    label: String,
    focused: Boolean,
    keyWidth: Dp,
    keyHeight: Dp,
    keyWidthPx: Float,
    visuals: PopupVisuals,
) {
    Box(
        modifier = Modifier
            .size(keyWidth, keyHeight)
            .then(
                if (focused) {
                    Modifier.background(visuals.activeBackgroundColor, RoundedCornerShape(visuals.radius))
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        ScaledLabel(
            text = label,
            // 未聚焦时用 popupTextColor：与原 markInactive() 一致（初始的 keyTextColor 是上游不一致，
            // 焦点移动一次后就会被改成 popupTextColor）
            color = if (focused) visuals.activeForegroundColor else visuals.textColor,
            maxWidthPx = keyWidthPx,
        )
    }
}

/**
 * 等比缩放文本，等价 `AutoScaleTextView.Mode.Proportional`。
 *
 * 原实现是 `canvas.scale(sx, sy)`（围绕文字原点缩放后再按中心 gravity 平移），
 * 这里用 `graphicsLayer` 在已居中排版的文字上做等比缩放，效果一致。
 *
 * 注意必须自行量文字宽度来判断溢出：`layoutResult.size.width` 会被 `maxWidth` 钳制，检测不到超宽，
 * 这里取文本末尾的横向坐标作为实际宽度。
 */
@Composable
private fun ScaledLabel(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    maxWidthPx: Float,
) {
    var lineWidthPx by remember(text) { mutableFloatStateOf(0f) }
    val scale = if (lineWidthPx > maxWidthPx && lineWidthPx > 0f) maxWidthPx / lineWidthPx else 1f
    Text(
        text = text,
        color = color,
        fontSize = PopupTextSize,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        onTextLayout = { layout ->
            if (layout.lineCount > 0) {
                lineWidthPx = layout.getHorizontalPosition(
                    layout.layoutInput.text.length,
                    usePrimaryDirection = true,
                )
            }
        },
        modifier = Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
    )
}

/**
 * 长按弹出菜单（原 [PopupMenuUi]）：一排圆形底图标。
 */
@Composable
private fun PopupMenuItem(state: Menu, visuals: PopupVisuals) {
    val density = LocalDensity.current
    val cellSize = with(density) { state.keySize.toDp() }
    Row(modifier = Modifier.offset { IntOffset(state.x, state.y) }) {
        for (position in state.items.indices) {
            PopupMenuCell(
                item = state.items[state.columnOrder[position]],
                focused = state.columnOrder[position] == state.focusedIndex,
                cellSize = cellSize,
                visuals = visuals,
            )
        }
    }
}

@Composable
private fun PopupMenuCell(
    item: KeyDef.Popup.Menu.Item,
    focused: Boolean,
    cellSize: Dp,
    visuals: PopupVisuals,
) {
    Box(modifier = Modifier.size(cellSize), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.size(if (focused) MenuActiveCircleSize else MenuInactiveCircleSize),
            shape = CircleShape,
            color = if (focused) visuals.menuActiveBackgroundColor else visuals.menuInactiveBackgroundColor,
        ) {}
        Icon(
            painter = painterResource(item.icon),
            contentDescription = item.label,
            tint = visuals.menuIconTintColor,
            // 原实现是 ImageView + ScaleType.CENTER_INSIDE：图标按自身尺寸绘制，只缩小不放大。
            // 这里限制上界以复刻该行为（避免超大 drawable 溢出格子）。
            modifier = Modifier.sizeIn(maxWidth = cellSize, maxHeight = cellSize),
        )
    }
}
