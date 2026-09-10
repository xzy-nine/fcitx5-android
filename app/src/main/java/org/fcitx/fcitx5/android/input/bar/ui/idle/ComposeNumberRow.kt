/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.bar.ComposeKawaiiBarComponent
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import top.yukonga.miuix.kmp.basic.Text

/**
 * Compose 版数字行，替代 [NumberRow]（View / BaseKeyboard）。
 * 作为工具栏 Idle 态的 NumberRow 子态内容，由 ComposeToolbar 渲染。
 */
private val NUMBER_ROW_DIGITS = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")

@Composable
fun NumberRowContent(
    theme: Theme,
    keyActionListener: KeyActionListener?,
    popupActionListener: PopupActionListener?,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    // 与原 NumberRow 一致：左滑（LTR 下为向右位移）超过工具栏高度即收起
    val thresholdPx = with(density) { ComposeKawaiiBarComponent.HEIGHT.dp.toPx() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .pointerInput(thresholdPx, layoutDirection) {
                // 左滑（LTR 下为向右位移）超过工具栏高度即收起，等价于原 NumberRow 的收起手势
                var startX = 0f
                var triggered = false
                detectDragGestures(
                    onDragStart = { offset ->
                        startX = offset.x
                        triggered = false
                    },
                    onDrag = { change, _ ->
                        val dir = if (layoutDirection == LayoutDirection.Ltr) 1f else -1f
                        val dx = (change.position.x - startX) * dir
                        if (!triggered && dx > thresholdPx) {
                            triggered = true
                            onCollapse()
                        }
                    },
                    onDragEnd = {},
                    onDragCancel = {},
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
        ) {
            // BoxWithConstraints 是 Box（子项重叠堆叠），必须用 Row 横向排列 10 个键
            val keyWidth = maxWidth / NUMBER_ROW_DIGITS.size
            Row(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NUMBER_ROW_DIGITS.forEachIndexed { index, digit ->
                    NumberKey(
                        digit = digit,
                        id = index,
                        theme = theme,
                        keyActionListener = keyActionListener,
                        popupActionListener = popupActionListener,
                        modifier = Modifier.width(keyWidth).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun NumberKey(
    digit: String,
    id: Int,
    theme: Theme,
    keyActionListener: KeyActionListener?,
    popupActionListener: PopupActionListener?,
    modifier: Modifier = Modifier,
) {
    var bounds by remember { mutableStateOf(Rect()) }
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    // 按下时弹出预览、松开时消失（对应原 KeyDef.Popup.Preview）
    LaunchedEffect(pressed) {
        if (pressed) {
            popupActionListener?.onPopupAction(PopupAction.PreviewAction(id, digit, bounds))
        } else {
            popupActionListener?.onPopupAction(PopupAction.DismissAction(id))
        }
    }

    Box(
        modifier = modifier
            .background(if (pressed) Color(theme.keyPressHighlightColor) else Color.Transparent)
            .onGloballyPositioned { coordinates: LayoutCoordinates ->
                val window = coordinates.boundsInWindow()
                bounds = Rect(
                    window.left.toInt(),
                    window.top.toInt(),
                    window.right.toInt(),
                    window.bottom.toInt(),
                )
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    keyActionListener?.onKeyAction(
                        KeyAction.SymAction(KeySym(digit.codePointAt(0))),
                        KeyActionListener.Source.Keyboard,
                    )
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = digit,
            fontSize = 21.sp,
            color = Color(theme.keyTextColor),
        )
    }
}
