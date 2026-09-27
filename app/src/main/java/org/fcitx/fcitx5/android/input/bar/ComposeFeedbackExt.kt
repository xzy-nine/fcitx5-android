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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.prefs.AppPrefs

/**
 * 用偏好 [AppPrefs.keyboard.longPressDelay] 覆盖 Compose 手势的长按阈值。
 *
 * `detectTapGestures` 的长按判定读的是 `PointerInputScope.viewConfiguration`（来自
 * [LocalViewConfiguration]），默认取系统 `ViewConfiguration.getLongPressTimeout()` ——
 * 各 ROM 不一致（AOSP 400ms，实测 MIUI 约 300ms）。包一层本 Provider 后，其内部所有基于
 * `detectTapGestures` 的手势统一走用户可调的口径，与 [repeatableClick] 共用同一偏好。
 *
 * 只覆盖 `longPressTimeoutMillis`，其余字段全部委托给系统实现，因此
 * `touchSlop` / `doubleTapTimeoutMillis` 等行为不变（`LazyColumn` 的滚动判定不受影响）。
 *
 * 注意：偏好读取不是响应式的（[org.fcitx.fcitx5.android.data.prefs.ManagedPreference]
 * 的 `getValue()` 是普通读取），与 [repeatableClick] 一致 —— 改偏好后需窗口重建才生效。
 */
@Composable
fun LongPressDelayProvider(content: @Composable () -> Unit) {
    val systemViewConfiguration = LocalViewConfiguration.current
    val longPressDelay by AppPrefs.getInstance().keyboard.longPressDelay
    val viewConfiguration = remember(systemViewConfiguration, longPressDelay) {
        object : ViewConfiguration by systemViewConfiguration {
            override val longPressTimeoutMillis: Long = longPressDelay.toLong()
        }
    }
    CompositionLocalProvider(LocalViewConfiguration provides viewConfiguration, content = content)
}

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
    // 重复协程挂在与 composable 同存亡的普通作用域上（pointerInput / awaitEachGesture 的
    // 作用域受限，不能直接 launch）。重复协程的停止由手势末段的 finally 统一负责：无论
    // 手势正常结束还是指针作用域被取消，都会先 cancelAndJoin。交互发射改用非挂起的
    // tryEmit，避免在受限作用域的 finally 里再发起协程。
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
            interactionSource.tryEmit(PressInteraction.Press(down.position))

            // 是否已进入重复，用于抑制松手时的单击。
            // 写入来自重复协程、读取在主协程，用原子量保证可见性，
            // 避免「长按已生效却仍触发单击」的竞态。
            val repeated = AtomicBoolean(false)
            // 重复协程：长按阈值后开始循环触发 onClick。放在手势外层的普通作用域里运行，
            // 由下面的 finally cancelAndJoin 保证在 enabled 变化 / 手势被取消 / 正常结束
            // 三种情况下都停止，不会漏掉取消导致 onClick 无限泄漏。
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
            try {
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
            } finally {
                // 无论手势正常结束还是被取消（enabled 变化 / pointerInput 失效导致本次
                // 手势协程取消），都先把重复协程停掉再收尾，杜绝重复 onClick 泄漏。
                // 受限作用域里不能 suspend，故用非挂起的 cancel()：它标记取消后，重复协程
                // 在下一次 delay 立即抛出 CancellationException，循环随即停止，效果等价。
                repeatJob.cancel()
                interactionSource.tryEmit(PressInteraction.Release(PressInteraction.Press(down.position)))
                InputFeedbacks.hapticFeedback(view, longPress = false, keyUp = true)
            }

            // 未进入重复且正常抬起 → 单击
            if (released && !repeated.get()) currentOnClick()
        }
    }
}

/** 长按重复触发的间隔，与旧 [org.fcitx.fcitx5.android.input.keyboard.CustomGestureView] 保持一致 */
private const val RepeatInterval = 50L
