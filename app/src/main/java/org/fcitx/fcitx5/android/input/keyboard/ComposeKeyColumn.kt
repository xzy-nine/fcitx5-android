/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * 竖排键列：[ComposeKeyRow] 的竖向对应物。
 *
 * 每键高度缺省等分（可用 [ComposeKeyColumn] 的 `keyWeights` 覆盖）；
 * 空格/退格的滑行（移光标、删选区）与长按连发按主键盘口径接线。
 * 供展开候选页 / Picker 右栏、手写键盘右侧列等复用。
 */
package org.fcitx.fcitx5.android.input.keyboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.popup.PopupActionListener

/**
 * 一列键（竖排，恒 LTR）。
 *
 * @param keyWeights 每键高度权重，缺省等分
 * @param keyEnabled 每键可用性（如翻页键到顶/到底置灰）
 * @param onBeforeKeyAction 每键派发前的回调（如手写键盘按下删除/回车/符号前先固化识别窗口）
 */
@Composable
fun ComposeKeyColumn(
    keys: List<KeyDef>,
    modifier: Modifier = Modifier,
    keyIdBase: Int = 0,
    keyActionListener: KeyActionListener? = null,
    popupActionListener: PopupActionListener? = null,
    keyWeights: List<Float>? = null,
    keyEnabled: (Int) -> Boolean = { true },
    onBeforeKeyAction: ((Int) -> Unit)? = null,
) {
    if (keys.isEmpty()) return
    val view = LocalView.current
    val prefs = remember { AppPrefs.getInstance().keyboard }
    val hapticOnRepeat = prefs.hapticOnRepeat.preferenceState()
    val spaceSwipeMoveCursor = prefs.spaceSwipeMoveCursor.preferenceState()

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(modifier) {
            keys.forEachIndexed { index, def ->
                val listener = if (onBeforeKeyAction == null) {
                    keyActionListener
                } else {
                    KeyActionListener { action, source ->
                        onBeforeKeyAction(index)
                        keyActionListener?.onKeyAction(action, source)
                    }
                }
                val listenerState = rememberUpdatedState(listener)
                val swipeSpec = remember(def, spaceSwipeMoveCursor) {
                    def.spaceAndBackspaceSwipeSpec(spaceSwipeMoveCursor)
                }
                val gestureListener = remember(def, hapticOnRepeat) {
                    def.spaceAndBackspaceGestureListener(
                        view = view,
                        onAction = { action ->
                            listenerState.value?.onKeyAction(action, KeyActionListener.Source.Keyboard)
                        },
                        hapticOnRepeat = hapticOnRepeat,
                    )
                }
                ComposeKey(
                    def = def,
                    keyId = keyIdBase + index,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(keyWeights?.getOrNull(index) ?: 1f),
                    keyActionListener = listener,
                    popupActionListener = popupActionListener,
                    enabled = keyEnabled(index),
                    swipeSpec = swipeSpec,
                    onSwipeGesture = gestureListener,
                )
            }
        }
    }
}
