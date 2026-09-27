/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Compose 键盘手势的**纯**部分：阈值、事件契约、滑行取模累加。
 *
 * 与 View 侧 [`CustomGestureView`] 逐项对应（`Docs/KeyboardComposePlan.md` D8），
 * 不放任何 Compose 状态或 Android 事件代码，因此可直接单测。
 */

/**
 * 滑行阈值，与 `BaseKeyboard` 的同名常量同口径（单位 px 由调用方按 `Density` 换算）。
 *
 * - [Selection]：空格左右滑移动光标（`BaseKeyboard.selectionSwipeThreshold`）
 * - [Input]：滑行输入 / 上下滑符号（`BaseKeyboard.inputSwipeThreshold`）
 * - [Disabled]：把某个方向的滑行关掉（`BaseKeyboard.disabledSwipeThreshold`）
 */
object KeySwipeThresholds {
    val Selection = 10.dp
    val Input = 36.dp
    val Disabled = 800.dp
}

/**
 * 键型专属的滑行配置。
 *
 * 对应 `BaseKeyboard.createKeyView` 里按键型（空格 / 退格）设置的那几条
 * `swipeEnabled / swipeRepeatEnabled / swipeThresholdX / swipeThresholdY`
 * —— 它们不属于 `KeyDef` 数据，所以在 Compose 侧由键盘容器（批次 C）传入。
 */
@Immutable
data class ComposeKeySwipeSpec(
    val thresholdX: Dp = KeySwipeThresholds.Disabled,
    val thresholdY: Dp = KeySwipeThresholds.Disabled,
    /** 对应 `CustomGestureView.swipeRepeatEnabled`（滑行一次后抑制长按/重复）。 */
    val repeatEnabled: Boolean = false,
)

/**
 * 键内手势事件，字段与 `CustomGestureView.Event` 一一对应。
 *
 * [Type] 是自有的枚举（不复用 View 侧的 `CustomGestureView.GestureType`），
 * 这样 Compose 实现不依赖即将断线的 View 文件；两者语义完全相同。
 */
@Immutable
data class ComposeKeyGestureEvent(
    val type: Type,
    /** 事件是否已被更早的处理器消费（对应 `CustomGestureView.gestureConsumed`）。 */
    val consumed: Boolean,
    /** 键内局部坐标（对应 View 侧转发后的 `event.x/y`）。 */
    val x: Float,
    val y: Float,
    /** 本次事件按阈值取模后的滑行计数（对应 `countX/countY`）。 */
    val countX: Int,
    val countY: Int,
    /** 本次手势累计滑行计数（对应 `totalX/totalY`），Up 时用于「划过了几格」。 */
    val totalX: Int,
    val totalY: Int,
) {
    enum class Type { Down, Move, Up }
}

/** 与 `CustomGestureView.OnGestureListener` 等价；返回 true 表示消费掉本次手势。 */
fun interface ComposeKeyGestureListener {
    fun onGesture(event: ComposeKeyGestureEvent): Boolean
}

/**
 * 滑行取模累加器，逐字复刻 `CustomGestureView.consumeSwipe`：
 *
 * ```
 * unconsumed = current - last + unconsumedRemainder
 * count      = unconsumed / threshold   (整数除法，向零取整)
 * remainder  = unconsumed % threshold   (符号跟随被除数)
 * ```
 *
 * 即「不足一格的位移留到下一次」，`countX/Y` 是本次新增的格数，`totalX/Y` 是累计格数。
 * 阈值很大（`Disabled`）时行为自然退化为永不触发。
 */
class SwipeAccumulator(thresholdX: Float, thresholdY: Float) {

    private val thresholdX = thresholdX.coerceAtLeast(1e-3f)
    private val thresholdY = thresholdY.coerceAtLeast(1e-3f)

    var totalX: Int = 0
        private set
    var totalY: Int = 0
        private set

    private var lastX = 0f
    private var lastY = 0f
    private var remainderX = 0f
    private var remainderY = 0f

    /** 按下时调用（View: `ACTION_DOWN` 里记录 `swipeLastX/Y` 并清零累计）。 */
    fun start(x: Float, y: Float) {
        lastX = x
        lastY = y
        remainderX = 0f
        remainderY = 0f
        totalX = 0
        totalY = 0
    }

    fun consumeX(x: Float): Int {
        val delta = x - lastX + remainderX
        lastX = x
        val count = (delta / thresholdX).toInt()
        remainderX = delta % thresholdX
        totalX += count
        return count
    }

    fun consumeY(y: Float): Int {
        val delta = y - lastY + remainderY
        lastY = y
        val count = (delta / thresholdY).toInt()
        remainderY = delta % thresholdY
        totalY += count
        return count
    }
}

/**
 * 触点是否仍在本键内，等价于 `CustomGestureView.pointInView`（含 `touchSlop` 边界）。
 *
 * 注意：Compose 的 `pointerInput` 会让指针在移出节点后**继续投递**给同一节点，
 * 所以「移出即取消」必须自己判 —— 这条判定就是 `touchMovedOutside`。
 */
fun isPointInKeyBounds(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    touchSlop: Float,
): Boolean = -touchSlop <= x && -touchSlop <= y &&
        x < (width + touchSlop) && y < (height + touchSlop)
