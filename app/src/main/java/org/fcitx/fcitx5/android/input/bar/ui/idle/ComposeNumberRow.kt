/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.fcitx.fcitx5.android.input.bar.ComposeKawaiiBarComponent
import org.fcitx.fcitx5.android.input.keyboard.ComposeKey
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.rememberKeyboardVisuals
import org.fcitx.fcitx5.android.input.popup.PopupActionListener

/**
 * Compose 版数字行，替代 [NumberRow]（View / BaseKeyboard）。
 * 作为工具栏 Idle 态的 NumberRow 子态内容，由 ComposeToolbar 渲染。
 *
 * 布局数据取自 [NumberRowLayout]（纯 [org.fcitx.fcitx5.android.input.keyboard.KeyDef] 数据，
 * 已从 View 类 NumberRow 抽离），按键本体走批次 B 的
 * [ComposeKey] 原语（每键 `pointerInput`），因此长按/滑行/移出取消等语义与主键盘同源。
 */
@Composable
fun NumberRowContent(
    keyActionListener: KeyActionListener?,
    popupActionListener: PopupActionListener?,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    // 收起手势要按**系统**布局方向判定，因此必须在下面强制 LTR 之前读取
    val systemLayoutDirection = LocalLayoutDirection.current
    // 与原 NumberRow 一致：左滑（LTR 下为向右位移）超过工具栏高度即收起
    val thresholdPx = with(density) { ComposeKawaiiBarComponent.HEIGHT.dp.toPx() }
    val visuals = rememberKeyboardVisuals()
    val row = NumberRowLayout.first()
    // 收起手势生效时递增：通知键取消当前手势，避免「滑收起的同时又上屏一个数字」
    // （对应 View 侧 onInterceptTouchEvent 抢占后子 View 收到 ACTION_CANCEL）
    var cancelEpoch by remember { mutableStateOf(0) }

    // 数字行**恒为 LTR**：View 侧 BaseKeyboard.createKeyRow 用的是绝对 left/right 约束，
    // 在 RTL 语言下不会镜像；Compose 的 Row 默认跟随 LocalLayoutDirection，必须显式固定，
    // 否则阿拉伯语/希伯来语下数字顺序会倒过来（0 在最左）。
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .pointerInput(thresholdPx, systemLayoutDirection) {
                    // 左滑（LTR 下为向右位移）超过工具栏高度即收起，等价于原 NumberRow 的收起手势
                    var startX = 0f
                    var triggered = false
                    detectDragGestures(
                        onDragStart = { offset ->
                            startX = offset.x
                            triggered = false
                        },
                        onDrag = { change, _ ->
                            val dir =
                                if (systemLayoutDirection == LayoutDirection.Ltr) 1f else -1f
                            val dx = (change.position.x - startX) * dir
                            if (!triggered && dx > thresholdPx) {
                                triggered = true
                                cancelEpoch++
                                onCollapse()
                            }
                        },
                        onDragEnd = {},
                        onDragCancel = {},
                    )
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            row.forEachIndexed { index, def ->
                ComposeKey(
                    def = def,
                    keyId = index,
                    visuals = visuals,
                    keyActionListener = keyActionListener,
                    popupActionListener = popupActionListener,
                    cancelEpoch = cancelEpoch,
                    // 原 NumberRow 的 10 个键等宽（percentWidth 均为 0.1f），weight 与之等价
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        }
    }
}
