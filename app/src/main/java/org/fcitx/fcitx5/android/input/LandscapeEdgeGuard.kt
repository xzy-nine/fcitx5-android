package org.fcitx.fcitx5.android.input

import android.view.MotionEvent
import android.view.View
import android.view.View.OnClickListener
import android.view.View.OnTouchListener
import androidx.constraintlayout.widget.ConstraintLayout
import org.fcitx.fcitx5.android.utils.unset
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.endToStartOf
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.startToEndOf

object LandscapeEdgeGuard {

    private const val TEST_COLOR = 0x40FF0000.toInt()
    private const val FADE_DURATION_MS = 1000L

    private var active = false
    private var leftGuard: View? = null
    private var rightGuard: View? = null
    private var bottomGuard: View? = null

    // 当前按下的 guard 数量（用于处理多指）
    private var pressedCount = 0

    private val clickListener = OnClickListener { }

    private val touchListener = OnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedCount++
                showAllGuards()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (pressedCount > 0) {
                    pressedCount--
                }
                if (pressedCount == 0) {
                    fadeOutAllGuards()
                }
            }
        }
        // 消费事件，防止误触后面的按键
        true
    }

    fun update(
        keyboardView: ConstraintLayout,
        topAnchor: View,
        guardWidthDp: Int,
        active: Boolean
    ) {
        if (this.active == active && leftGuard != null) return
        this.active = active

        if (active && guardWidthDp > 0) {
            ensureViews(keyboardView, topAnchor)
            val w = keyboardView.context.dp(guardWidthDp)
            show(w)
        } else {
            hide()
        }
    }

    private fun ensureViews(keyboardView: ConstraintLayout, topAnchor: View) {
        if (leftGuard != null) return
        val ctx = keyboardView.context

        leftGuard = View(ctx).apply {
            setBackgroundColor(TEST_COLOR)
            alpha = 0f
            isClickable = true
            setOnClickListener(clickListener)
            setOnTouchListener(touchListener)
        }.also {
            keyboardView.addView(it, ConstraintLayout.LayoutParams(0, 0).apply {
                startToEnd = unset
                endToStart = unset
                startOfParent()
                below(topAnchor)
                bottomOfParent()
            })
        }

        rightGuard = View(ctx).apply {
            setBackgroundColor(TEST_COLOR)
            alpha = 0f
            isClickable = true
            setOnClickListener(clickListener)
            setOnTouchListener(touchListener)
        }.also {
            keyboardView.addView(it, ConstraintLayout.LayoutParams(0, 0).apply {
                startToEnd = unset
                endToStart = unset
                endOfParent()
                below(topAnchor)
                bottomOfParent()
            })
        }

        bottomGuard = View(ctx).apply {
            setBackgroundColor(TEST_COLOR)
            alpha = 0f
            isClickable = true
            setOnClickListener(clickListener)
            setOnTouchListener(touchListener)
        }.also {
            keyboardView.addView(it, ConstraintLayout.LayoutParams(0, 0).apply {
                startToEndOf(leftGuard!!)
                endToStartOf(rightGuard!!)
                bottomOfParent()
            })
        }
    }

    private fun show(guardWidthPx: Int) {
        // 重置按压计数
        pressedCount = 0

        leftGuard?.apply {
            animate().cancel()
            alpha = 0f
            visibility = View.VISIBLE
            (layoutParams as ConstraintLayout.LayoutParams).width = guardWidthPx
            requestLayout()
        }
        rightGuard?.apply {
            animate().cancel()
            alpha = 0f
            visibility = View.VISIBLE
            (layoutParams as ConstraintLayout.LayoutParams).width = guardWidthPx
            requestLayout()
        }
        bottomGuard?.apply {
            animate().cancel()
            alpha = 0f
            visibility = View.VISIBLE
            (layoutParams as ConstraintLayout.LayoutParams).height = guardWidthPx
            requestLayout()
        }
    }

    private fun showAllGuards() {
        listOf(leftGuard, rightGuard, bottomGuard).forEach { guard ->
            guard?.animate()?.cancel()
            guard?.alpha = 1f
        }
    }

    private fun fadeOutAllGuards() {
        listOf(leftGuard, rightGuard, bottomGuard).forEach { guard ->
            guard?.animate()?.cancel()
            guard?.animate()
                ?.alpha(0f)
                ?.setDuration(FADE_DURATION_MS)
                ?.start()
        }
    }

    private fun hide() {
        pressedCount = 0
        listOf(leftGuard, rightGuard, bottomGuard).forEach { guard ->
            guard?.animate()?.cancel()
            guard?.visibility = View.GONE
        }
    }
}