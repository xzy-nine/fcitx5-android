/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.prefs.AppPrefs

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

/**
 * 长按重复触发的点击修饰符。
 *
 * 语义与旧 `CustomGestureView(repeatEnabled = true)` 一致：
 * - 按下即有反馈（haptic + sound）；
 * - 松手早于长按阈值 → 触发一次 [onClick]；
 * - 持续按住超过 [AppPrefs.keyboard.longPressDelay] → 开始自动重复，间隔 [RepeatInterval]，
 *   重复期间不再触发单击；期间可配合 [hapticOnRepeat] 提供震动。
 *
 * 用于文本编辑页的方向键与退格，替代 View 实现里 `repeatEnabled + onRepeatListener` 的行为。
 */
fun Modifier.repeatableClick(
    enabled: Boolean = true,
    hapticOnRepeat: Boolean = false,
    onClick: () -> Unit,
): Modifier = composed {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val currentOnClick by rememberUpdatedState(onClick)
    val currentHaptic by rememberUpdatedState(hapticOnRepeat)
    val interactionSource = remember { MutableInteractionSource() }
    val longPressDelay by AppPrefs.getInstance().keyboard.longPressDelay

    this.pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            InputFeedbacks.hapticFeedback(view)
            InputFeedbacks.soundEffect(InputFeedbacks.SoundEffect.Standard)
            scope.launch {
                interactionSource.emit(PressInteraction.Press(down.position))
            }

            // 是否已进入重复，用于抑制松手时的单击。
            // 写入来自重复协程、读取在主协程，用原子量保证可见性，
            // 避免「长按已生效却仍触发单击」的竞态。
            val repeated = AtomicBoolean(false)
            val repeatJob = scope.launch {
                if (longPressDelay > 0) delay(longPressDelay.toLong())
                repeated.set(true)
                while (isActive) {
                    currentOnClick()
                    if (currentHaptic) InputFeedbacks.hapticFeedback(view)
                    delay(RepeatInterval)
                }
            }

            // 跟踪按压与移出：与 Compose clickable 一致，移出即取消。
            // 注意：此处不复用 isOutOfBounds（其重载在 Size/IntSize 间有歧义），
            // 直接用触摸 slop 判定手指是否已移出控件边界。
            val touchSlop = viewConfiguration.touchSlop
            var released = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    released = true
                    change.consume()
                    break
                }
                change.consume()
                // 进入重复后不再因移出而取消，与 CustomGestureView 一致
                if (!repeated.get()) {
                    val p = change.position
                    val out = p.x < -touchSlop || p.y < -touchSlop ||
                            p.x > size.width + touchSlop || p.y > size.height + touchSlop
                    if (out) break
                }
            }

            repeatJob.cancel()
            scope.launch {
                interactionSource.emit(PressInteraction.Release(PressInteraction.Press(down.position)))
            }
            InputFeedbacks.hapticFeedback(view, longPress = false, keyUp = true)

            // 未进入重复且正常抬起 → 单击
            if (released && !repeated.get()) currentOnClick()
        }
    }
}

/** 长按重复触发的间隔，与旧 [org.fcitx.fcitx5.android.input.keyboard.CustomGestureView] 保持一致 */
private const val RepeatInterval = 50L
