/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import kotlin.math.roundToInt

/**
 * Glassmorphism tuning overlay shown **on top of the keyboard area only**.
 * Reference: Xime `KeyboardResizeOverlay`.
 *
 * - The keyboard behind is blurred (RenderEffect on API 31+) while tuning.
 * - Frosted cards mark the adjustable regions. You drag a card to tune:
 *   - the keyboard card  -> keyboard height (drag up = taller)
 *   - split left/right cards -> the gap between them (split blank ratio)
 * - Toggling the toolbar tune button again, or tapping 完成/取消, exits.
 *
 * Edge guard (landscape) is intentionally NOT tuned here — it stays in settings.
 *
 * The overlay never covers the toolbar, so the toolbar toggle stays reachable.
 */
class KeyboardTuneOverlay(
    context: Context,
    private val theme: Theme,
    private val keyboardPrefs: AppPrefs.Keyboard,
    private val onDismiss: () -> Unit,
    private val metricsProvider: () -> TuneMetrics
) : FrameLayout(context) {

    data class TuneMetrics(
        val isLandscape: Boolean,
        val toolbarHeightPx: Int,
        /** real bounds of the keyboard container, in overlay coordinates */
        val keyboardRect: android.graphics.Rect,
        /** real bounds of the bottom padding space, in overlay coordinates */
        val bottomRect: android.graphics.Rect,
        val heightBasePx: Int
    )

    private enum class DragMode {
        NONE, HEIGHT, BOTTOM, SIDE_LEFT, SIDE_RIGHT, GAP
    }

    private val density = context.resources.displayMetrics.density
    private val accent = ContextCompat.getColor(context, R.color.tune_accent)

    // card region rectangles (in overlay coordinate space)
    private val fullRect = android.graphics.Rect()
    private val leftRect = android.graphics.Rect()
    private val rightRect = android.graphics.Rect()
    private val buttonBarRect = android.graphics.Rect()

    private var dragMode = DragMode.NONE
    private var startX = 0f
    private var startY = 0f
    private var startKeyboardHeightPx = 0
    private var startBottomPx = 0
    private var startSidePx = 0
    private var startGapPx = 0
    private var startBaseWidthPx = 0
    private var startLandscape = false

    private data class Snapshot(
        val landscape: Boolean,
        val heightPercent: Int,
        val sideDp: Int,
        val bottomDp: Int,
        val splitEnabled: Boolean,
        val splitRatio: Int
    )

    private var snapshot = Snapshot(false, 30, 0, 0, false, 30)

    // ----- views -----
    private val fullCard: Card
    private val leftCard: Card
    private val rightCard: Card
    private val leftGrip: View
    private val rightGrip: View
    private val bottomGrip: View
    private val buttonBar: LinearLayout

    private val slopPx get() = dp(24f)
    /** width of the grab band for side/bottom margins; kept inside the keyboard
     *  so screen-edge system gestures never swallow the drag. */
    private val grabPx get() = dp(20f)

    init {
        visibility = View.GONE
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = true
        isFocusable = true

        val panelCtx = ContextThemeWrapper(
            context,
            com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar
        )

        fullCard = Card(context, theme, R.string.keyboard_tune_height)
        leftCard = Card(context, theme, R.string.keyboard_tune_split)
        rightCard = Card(context, theme, R.string.keyboard_tune_split)
        leftGrip = makeGrip()
        rightGrip = makeGrip()
        bottomGrip = makeGrip()

        // ----- action bar (always inside keyboard region, always clickable) -----
        val resetBtn = actionButton(panelCtx, R.drawable.ic_baseline_settings_backup_restore_24, R.string.tune_reset) {
            resetToDefaults()
        }
        val cancelBtn = actionButton(panelCtx, R.drawable.ic_baseline_close_24, R.string.tune_cancel) {
            applySnapshot(true)
        }
        val confirmBtn = actionButton(panelCtx, R.drawable.ic_baseline_check_24, R.string.tune_confirm) {
            hide()
        }
        buttonBar = LinearLayout(panelCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24f).toFloat()
                setColor(theme.barColor)
            }
            elevation = dp(6f).toFloat()
            addView(resetBtn, LinearLayout.LayoutParams(dp(44f), dp(44f)))
            addView(cancelBtn, LinearLayout.LayoutParams(dp(44f), dp(44f)).apply { leftMargin = dp(12f) })
            addView(confirmBtn, LinearLayout.LayoutParams(dp(44f), dp(44f)).apply { leftMargin = dp(12f) })
        }

        addView(fullCard)
        addView(leftCard)
        addView(rightCard)
        addView(leftGrip)
        addView(rightGrip)
        addView(bottomGrip)
        addView(buttonBar)
    }

    // ---------- builders ----------

    private fun dp(v: Float) = (v * density).toInt()

    /** A frosted, rounded, semi-transparent card with a centered label. */
    private class Card(
        context: Context,
        theme: Theme,
        labelRes: Int
    ) : FrameLayout(context) {
        private val label: TextView = TextView(context).apply {
            setText(labelRes)
            setTextColor(theme.keyTextColor)
            textSize = 12f
            gravity = Gravity.CENTER
            alpha = 0.85f
        }

        init {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12f, context).toFloat()
                // frosted glass: theme bar color at ~55% opacity + accent stroke
                val c = theme.barColor
                setColor(Color.argb(140, Color.red(c), Color.green(c), Color.blue(c)))
                setStroke(dp(2f, context), accentColor(context))
            }
            addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            })
        }

        fun setActive(active: Boolean) {
            (background as? GradientDrawable)?.alpha = if (active) 200 else 140
        }

        /** Hide the label on narrow cards (e.g. edge-guard strips) where it would be clipped. */
        fun setLabelVisible(visible: Boolean) {
            label.visibility = if (visible) View.VISIBLE else View.GONE
        }

        private fun dp(v: Float, ctx: Context) = (v * ctx.resources.displayMetrics.density).toInt()
        private fun accentColor(ctx: Context) =
            ContextCompat.getColor(ctx, R.color.tune_accent)
    }

    /** Thin accent bar marking the draggable band for side/bottom margins. */
    private fun makeGrip(): View = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(2f).toFloat()
            setColor(accent)
        }
        alpha = 0.85f
        isClickable = false
        isFocusable = false
    }

    private fun actionButton(
        ctx: Context, iconRes: Int, descRes: Int, onClick: () -> Unit
    ): ImageButton = ImageButton(ctx).apply {
        setImageResource(iconRes)
        contentDescription = ctx.getString(descRes)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(40, Color.red(accent), Color.green(accent), Color.blue(accent)))
        }
        imageTintList = android.content.res.ColorStateList.valueOf(accent)
        setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        setOnClickListener { onClick() }
    }

    // ---------- pref accessors ----------

    private fun heightPref(landscape: Boolean) =
        if (landscape) keyboardPrefs.keyboardHeightPercentLandscape else keyboardPrefs.keyboardHeightPercent

    private fun sidePref(landscape: Boolean) =
        if (landscape) keyboardPrefs.keyboardSidePaddingLandscape else keyboardPrefs.keyboardSidePadding

    private fun bottomPref(landscape: Boolean) =
        if (landscape) keyboardPrefs.keyboardBottomPaddingLandscape else keyboardPrefs.keyboardBottomPadding

    private fun splitRatioPref(landscape: Boolean) =
        if (landscape) keyboardPrefs.splitKeyboardBlankRatioLandscape else keyboardPrefs.splitKeyboardBlankRatio

    // ---------- public API ----------

    fun show() {
        val m = metricsProvider()
        snapshot = Snapshot(
            landscape = m.isLandscape,
            heightPercent = heightPref(m.isLandscape).getValue(),
            sideDp = sidePref(m.isLandscape).getValue(),
            bottomDp = bottomPref(m.isLandscape).getValue(),
            splitEnabled = keyboardPrefs.splitKeyboard.getValue(),
            splitRatio = splitRatioPref(m.isLandscape).getValue()
        )
        visibility = View.VISIBLE
        if (width > 0 && height > 0) {
            syncBox()
        } else {
            // overlay 首次显示时尚未布局，等键盘容器布局完成后再同步尺寸
            doOnLayout { syncBox() }
        }
    }

    fun hide() {
        onDismiss()
        // 退出后必须归还系统手势区域，否则返回手势在键盘边缘会一直失效
        if (Build.VERSION.SDK_INT >= 29) systemGestureExclusionRects = emptyList()
        visibility = View.GONE
        fullCard.setActive(false)
        leftCard.setActive(false)
        rightCard.setActive(false)
    }

    private fun applySnapshot(close: Boolean) {
        heightPref(snapshot.landscape).setValue(snapshot.heightPercent)
        sidePref(snapshot.landscape).setValue(snapshot.sideDp)
        bottomPref(snapshot.landscape).setValue(snapshot.bottomDp)
        keyboardPrefs.splitKeyboard.setValue(snapshot.splitEnabled)
        splitRatioPref(snapshot.landscape).setValue(snapshot.splitRatio)
        if (close) hide() else syncBox()
    }

    /** “重置”写入各 ManagedPreference 的默认值，而非恢复打开浮层时的值。 */
    private fun resetToDefaults() {
        val landscape = snapshot.landscape
        heightPref(landscape).setValue(heightPref(landscape).defaultValue)
        sidePref(landscape).setValue(sidePref(landscape).defaultValue)
        bottomPref(landscape).setValue(bottomPref(landscape).defaultValue)
        keyboardPrefs.splitKeyboard.setValue(keyboardPrefs.splitKeyboard.defaultValue)
        splitRatioPref(landscape).setValue(splitRatioPref(landscape).defaultValue)
        syncBox()
    }

    // ---------- layout sync ----------

    private fun syncBox() {
        if (width == 0 || height == 0) return
        val m = metricsProvider()
        // 以键盘容器的实测矩形为准，保证卡片严格落在键盘主体内
        val top = m.keyboardRect.top
        val kbBottom = m.keyboardRect.bottom
        val left = m.keyboardRect.left
        val right = m.keyboardRect.right
        if (kbBottom <= top || right <= left) return
        // 与 BaseKeyboard.isSplitAllowed() 保持一致：需同时满足偏好开启 + 宽高比 > 阈值
        val splitPref = keyboardPrefs.splitKeyboard.getValue()
        val threshold = keyboardPrefs.splitKeyboardThreshold.getValue()
        val kbW = (right - left).toFloat()
        val kbH = (kbBottom - top).toFloat()
        val split = splitPref && kbH > 0f && (kbW / kbH) > threshold

        if (split) {
            fullCard.visibility = View.GONE
            leftCard.visibility = View.VISIBLE
            rightCard.visibility = View.VISIBLE
            val baseW = (right - left).coerceAtLeast(1)
            val gap = (baseW * splitRatioPref(m.isLandscape).getValue() / 100)
                .coerceIn(0, baseW * 60 / 100)
            val half = (baseW - gap) / 2
            leftRect.set(left, top, left + half, kbBottom)
            rightRect.set(right - half, top, right, kbBottom)
            positionCard(leftCard, leftRect)
            positionCard(rightCard, rightRect)
        } else {
            fullCard.visibility = View.VISIBLE
            leftCard.visibility = View.GONE
            rightCard.visibility = View.GONE
            fullRect.set(left, top, right, kbBottom)
            positionCard(fullCard, fullRect)
        }

        // grips: thin accent bars just inside the card edges
        val bar = dp(3f)
        val inset = dp(24f)
        positionCard(
            leftGrip,
            android.graphics.Rect(left + bar, top + inset, left + bar + dp(4f), kbBottom - inset)
        )
        positionCard(
            rightGrip,
            android.graphics.Rect(right - bar - dp(4f), top + inset, right - bar, kbBottom - inset)
        )
        positionCard(
            bottomGrip,
            android.graphics.Rect(left + inset, kbBottom - bar - dp(4f), right - inset, kbBottom - bar)
        )
        updateGestureExclusion()

        // action bar pinned to the bottom of the keyboard region, centered
        buttonBar.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        val barW = buttonBar.measuredWidth
        val barH = buttonBar.measuredHeight
        val barLeft = ((width - barW) / 2).coerceAtLeast(0)
        val barTop = (kbBottom - barH - dp(8f)).coerceAtLeast(top + dp(8f))
        positionCard(buttonBar, android.graphics.Rect(barLeft, barTop, barLeft + barW, barTop + barH))
        buttonBarRect.set(barLeft, barTop, barLeft + barW, barTop + barH)
    }

    private fun positionCard(v: View, r: android.graphics.Rect) {
        val lp = v.layoutParams as? FrameLayout.LayoutParams
            ?: FrameLayout.LayoutParams(r.width(), r.height())
        lp.width = r.width()
        lp.height = r.height()
        lp.gravity = Gravity.TOP or Gravity.START
        lp.leftMargin = r.left
        lp.topMargin = r.top
        v.layoutParams = lp
        if (v is Card) v.setLabelVisible(r.width() >= dp(64f))
    }

    /**
     * Ask the system not to treat touches inside the grab bands as edge-back /
     * home gestures. Without this the side-margin drag is swallowed whenever the
     * current padding is 0 (the band then sits right on the screen edge).
     */
    private fun updateGestureExclusion() {
        if (Build.VERSION.SDK_INT < 29) return
        val m = metricsProvider()
        val top = m.keyboardRect.top
        val bottom = m.keyboardRect.bottom
        val left = m.keyboardRect.left
        val right = m.keyboardRect.right
        systemGestureExclusionRects = listOf(
            android.graphics.Rect(left, top, left + grabPx, bottom),
            android.graphics.Rect(right - grabPx, top, right, bottom),
            android.graphics.Rect(left + grabPx, bottom - grabPx, right - grabPx, bottom)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (visibility == View.VISIBLE) syncBox()
    }

    // ---------- touch handling ----------

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (visibility != View.VISIBLE) return false
        // let the toolbar (above the keyboard region) stay interactive -> toggle exits
        if (ev.y < metricsProvider().toolbarHeightPx) return false
        // let the action bar buttons receive their own clicks
        if (buttonBarRect.contains(ev.x.toInt(), ev.y.toInt())) return false
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (visibility != View.VISIBLE) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val m = metricsProvider()
                dragMode = hitTest(event.x.toInt(), event.y.toInt(), m)
                if (dragMode == DragMode.NONE) return false
                startX = event.x
                startY = event.y
                startKeyboardHeightPx = m.keyboardRect.height()
                startBottomPx = m.bottomRect.height()
                startSidePx = m.keyboardRect.left
                startLandscape = m.isLandscape
                startBaseWidthPx = m.keyboardRect.width().coerceAtLeast(1)
                startGapPx = (startBaseWidthPx * splitRatioPref(m.isLandscape).getValue() / 100)
                setCardActive(dragMode, true)
                if (Build.VERSION.SDK_INT >= 21) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragMode == DragMode.NONE) return false
                applyDrag(dragMode, event.x - startX, event.y - startY)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                setCardActive(dragMode, false)
                dragMode = DragMode.NONE
                return true
            }
        }
        return false
    }

    private fun hitTest(x: Int, y: Int, m: TuneMetrics): DragMode {
        val s = slopPx
        if (buttonBarRect.contains(x, y)) return DragMode.NONE

        val kbTop = m.keyboardRect.top
        val kbBottom = m.keyboardRect.bottom
        val cardLeft = m.keyboardRect.left
        val cardRight = m.keyboardRect.right
        val splitPref = keyboardPrefs.splitKeyboard.getValue()
        val threshold = keyboardPrefs.splitKeyboardThreshold.getValue()
        val kbW = (cardRight - cardLeft).toFloat()
        val kbH = (kbBottom - kbTop).toFloat()
        val split = splitPref && kbH > 0f && (kbW / kbH) > threshold

        // bottom band (inside the card) -> bottom margin (drag up = more space below)
        if (y in (kbBottom - grabPx)..kbBottom &&
            x in (cardLeft + grabPx)..(cardRight - grabPx)
        ) return DragMode.BOTTOM

        // side bands (inside the card, never on the screen edge) -> side margin
        if (y in kbTop..kbBottom) {
            if (x in cardLeft..(cardLeft + grabPx)) return DragMode.SIDE_LEFT
            if (x in (cardRight - grabPx)..cardRight) return DragMode.SIDE_RIGHT
        }

        // split gap: inner edges of the two cards
        if (split) {
            if (y in leftRect.top..leftRect.bottom) {
                if (x in (leftRect.right - s)..(leftRect.right + s)) return DragMode.GAP
                if (x in (rightRect.left - s)..(rightRect.left + s)) return DragMode.GAP
            }
            if (leftRect.contains(x, y) || rightRect.contains(x, y)) return DragMode.HEIGHT
        } else {
            if (fullRect.contains(x, y)) return DragMode.HEIGHT
        }
        return DragMode.NONE
    }

    private fun applyDrag(mode: DragMode, totalDx: Float, totalDy: Float) {
        when (mode) {
            DragMode.HEIGHT -> {
                val base = metricsProvider().heightBasePx
                if (base <= 0) return
                val minPx = base * 10 / 100
                val maxPx = base * 90 / 100
                val newPx = (startKeyboardHeightPx - totalDy).coerceIn(minPx.toFloat(), maxPx.toFloat())
                val percent = ((newPx * 100 / base).roundToInt()).coerceIn(10, 90)
                heightPref(startLandscape).setValue(percent)
            }
            DragMode.BOTTOM -> {
                // drag the card's bottom edge up -> more space below the keyboard
                val newPx = (startBottomPx - totalDy).coerceIn(0f, 100f * density)
                bottomPref(startLandscape).setValue((newPx / density).roundToInt().coerceIn(0, 100))
            }
            DragMode.SIDE_LEFT -> {
                val newPx = (startSidePx + totalDx).coerceIn(0f, 300f * density)
                sidePref(startLandscape).setValue((newPx / density).roundToInt().coerceIn(0, 300))
            }
            DragMode.SIDE_RIGHT -> {
                val newPx = (startSidePx - totalDx).coerceIn(0f, 300f * density)
                sidePref(startLandscape).setValue((newPx / density).roundToInt().coerceIn(0, 300))
            }
            DragMode.GAP -> {
                val newGap = (startGapPx + totalDx).coerceIn(0f, startBaseWidthPx * 60 / 100f)
                val ratio = ((newGap * 100 / startBaseWidthPx).roundToInt()).coerceIn(0, 60)
                splitRatioPref(startLandscape).setValue(ratio)
            }
            else -> return
        }
        // 等 IME 窗口（windowManager.view）完成布局、约束更新生效后再刷新，
        // 否则 syncBox() 读到的 keyboardRect 还是旧的键盘高度。
        doOnLayout { syncBox() }
    }

    private fun setCardActive(mode: DragMode, active: Boolean) {
        when (mode) {
            DragMode.HEIGHT, DragMode.BOTTOM, DragMode.SIDE_LEFT, DragMode.SIDE_RIGHT ->
                (if (leftCard.visibility == View.VISIBLE) leftCard else fullCard).setActive(active)
            DragMode.GAP -> {
                leftCard.setActive(active)
                rightCard.setActive(active)
            }
            else -> {}
        }
    }
}
