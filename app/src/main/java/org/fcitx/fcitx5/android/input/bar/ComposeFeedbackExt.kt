/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import org.fcitx.fcitx5.android.data.InputFeedbacks

/**
 * Compose 触觉/音效反馈扩展
 *
 * 在手指按下时触发 haptic + sound，松开时触发 keyUp 反馈，
 * 与旧 View 系统的 CustomGestureView 行为一致。
 */
@Composable
fun Modifier.inputFeedback(
    soundEffect: InputFeedbacks.SoundEffect = InputFeedbacks.SoundEffect.Standard,
    longPress: Boolean = false,
): Modifier {
    val view = LocalView.current
    return this.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            InputFeedbacks.hapticFeedback(view, longPress = longPress)
            InputFeedbacks.soundEffect(soundEffect)
            // 等待松开，触发 keyUp 反馈（与 CustomGestureView ACTION_UP 一致）
            do {
                val event = awaitPointerEvent()
            } while (event.changes.any { it.pressed })
            InputFeedbacks.hapticFeedback(view, longPress = false, keyUp = true)
        }
    }
}
