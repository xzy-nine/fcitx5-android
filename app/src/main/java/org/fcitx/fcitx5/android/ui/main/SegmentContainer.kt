/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import com.google.android.flexbox.FlexboxLayout

/**
 * 分词编辑区容器：拦截所有触摸事件，由自身统一处理「单击切换 / 长按进入拖选 / 滑动连选」，
 * 避免子 View（词块 chip）消费事件导致长按后 MOVE 无法到达、拖选失效的问题。
 */
class SegmentContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : FlexboxLayout(context, attrs, defStyle) {

    var onDown: ((x: Float, y: Float) -> Unit)? = null
    var onMove: ((x: Float, y: Float) -> Unit)? = null
    var onUp: (() -> Unit)? = null
    // 父 View（如 ScrollView）拦截手势时触发，不应视为点选
    var onCancel: (() -> Unit)? = null

    // 始终拦截，使整个手势归本容器处理（子 View 不再接收任何触摸）
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> onDown?.invoke(ev.x, ev.y)
            MotionEvent.ACTION_MOVE -> onMove?.invoke(ev.x, ev.y)
            MotionEvent.ACTION_UP -> onUp?.invoke()
            MotionEvent.ACTION_CANCEL -> onCancel?.invoke()
        }
        return true
    }
}
