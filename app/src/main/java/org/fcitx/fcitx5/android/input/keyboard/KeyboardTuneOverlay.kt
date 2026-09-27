/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.keyboard

import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.toAndroidRect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
 *
 * Ported from the old View `KeyboardTuneOverlay` to a Compose IME overlay layer:
 * the root `Box(fillMaxSize)` fills the IME window, so all coordinates are
 * window-absolute. [TuneMetrics.keyboardRect]/[TuneMetrics.bottomRect] are therefore
 * reported in window-absolute coordinates by the provider in [InputView].
 */
data class TuneMetrics(
    val isLandscape: Boolean,
    /**
     * 键盘区顶的窗口绝对 y。
     *
     * 它上方依次是「键盘体顶部延伸带（圆角延伸，不计入 IME 可见区）+ 预编辑栏 + 工具栏」——
     * 这些区域不属于可调校的键盘体，触摸应放行（工具栏按钮要保持可点），故用它作为拦截下界。
     */
    val topGuardPx: Int,
    /** real bounds of the keyboard container, in window-absolute coordinates */
    val keyboardRect: Rect,
    /** real bounds of the bottom padding space, in window-absolute coordinates */
    val bottomRect: Rect,
    val heightBasePx: Int
)

private enum class DragMode {
    NONE, HEIGHT, BOTTOM, SIDE_LEFT, SIDE_RIGHT, GAP
}

/**
 * Computed geometry for one layout pass, in window-absolute coordinates.
 * `fullRect` is null when split; `leftRect`/`rightRect` are null when not split.
 */
private data class TuneLayout(
    val top: Int,
    val kbBottom: Int,
    val left: Int,
    val right: Int,
    val split: Boolean,
    val fullRect: Rect?,
    val leftRect: Rect?,
    val rightRect: Rect?,
    val buttonBarRect: Rect
)

class KeyboardTuneCompose(
    private val metricsProvider: () -> TuneMetrics,
    private val onDismiss: () -> Unit
) : UniqueComponent<KeyboardTuneCompose>(),
    Dependent,
    ManagedHandler by managedHandler() {

    private val keyboardPrefs = AppPrefs.getInstance().keyboard

    private val _metrics = mutableStateOf<TuneMetrics?>(null)
    val metrics: TuneMetrics? get() = _metrics.value

    fun isShown(): Boolean = _metrics.value != null

    fun show() {
        val m = metricsProvider()
        takeSnapshot(m)
        _metrics.value = m
    }

    fun hide() {
        onDismiss()
        _metrics.value = null
    }

    /** Re-read the geometry after the keyboard relaid out (e.g. during a drag). */
    fun refresh() {
        if (_metrics.value != null) _metrics.value = metricsProvider()
    }

    // ----- pref accessors (same as the original View overlay) -----

    private fun heightPref(landscape: Boolean) =
        if (landscape) keyboardPrefs.keyboardHeightPercentLandscape else keyboardPrefs.keyboardHeightPercent

    private fun sidePref(landscape: Boolean) =
        if (landscape) keyboardPrefs.keyboardSidePaddingLandscape else keyboardPrefs.keyboardSidePadding

    private fun bottomPref(landscape: Boolean) =
        if (landscape) keyboardPrefs.keyboardBottomPaddingLandscape else keyboardPrefs.keyboardBottomPadding

    private fun splitRatioPref(landscape: Boolean) =
        if (landscape) keyboardPrefs.splitKeyboardBlankRatioLandscape else keyboardPrefs.splitKeyboardBlankRatio

    // ----- snapshot (values captured when the overlay is opened) -----

    private data class Snapshot(
        val landscape: Boolean,
        val heightPercent: Int,
        val sideDp: Int,
        val bottomDp: Int,
        val splitEnabled: Boolean,
        val splitRatio: Int
    )

    private var snapshot = Snapshot(false, 30, 0, 0, false, 30)

    // ----- drag state -----

    private var dragMode = DragMode.NONE
    private var startX = 0f
    private var startY = 0f
    private var startKeyboardHeightPx = 0
    private var startBottomPx = 0
    private var startSidePx = 0
    private var startGapPx = 0
    private var startBaseWidthPx = 0
    private var startLandscape = false

    private fun takeSnapshot(m: TuneMetrics) {
        snapshot = Snapshot(
            landscape = m.isLandscape,
            heightPercent = heightPref(m.isLandscape).getValue(),
            sideDp = sidePref(m.isLandscape).getValue(),
            bottomDp = bottomPref(m.isLandscape).getValue(),
            splitEnabled = keyboardPrefs.splitKeyboard.getValue(),
            splitRatio = splitRatioPref(m.isLandscape).getValue()
        )
    }

    private fun applySnapshot(close: Boolean) {
        heightPref(snapshot.landscape).setValue(snapshot.heightPercent)
        sidePref(snapshot.landscape).setValue(snapshot.sideDp)
        bottomPref(snapshot.landscape).setValue(snapshot.bottomDp)
        keyboardPrefs.splitKeyboard.setValue(snapshot.splitEnabled)
        splitRatioPref(snapshot.landscape).setValue(snapshot.splitRatio)
        if (close) hide() else refresh()
    }

    /** “重置”写入各 ManagedPreference 的默认值，而非恢复打开浮层时的值。 */
    private fun resetToDefaults() {
        val landscape = snapshot.landscape
        heightPref(landscape).setValue(heightPref(landscape).defaultValue)
        sidePref(landscape).setValue(sidePref(landscape).defaultValue)
        bottomPref(landscape).setValue(bottomPref(landscape).defaultValue)
        keyboardPrefs.splitKeyboard.setValue(keyboardPrefs.splitKeyboard.defaultValue)
        splitRatioPref(landscape).setValue(splitRatioPref(landscape).defaultValue)
        refresh()
    }

    // ----- geometry -----

    private fun computeLayout(
        m: TuneMetrics,
        barW: Int,
        barH: Int,
        density: Float,
        overlayW: Int
    ): TuneLayout {
        val top = m.keyboardRect.top
        val kbBottom = m.keyboardRect.bottom
        val left = m.keyboardRect.left
        val right = m.keyboardRect.right
        if (kbBottom <= top || right <= left) {
            return TuneLayout(top, kbBottom, left, right, false, null, null, null, Rect())
        }
        val splitPref = keyboardPrefs.splitKeyboard.getValue()
        val threshold = keyboardPrefs.splitKeyboardThreshold.getValue()
        val kbW = (right - left).toFloat()
        val kbH = (kbBottom - top).toFloat()
        val split = splitPref && kbH > 0f && (kbW / kbH) > threshold

        val fullRect: Rect?
        val leftRect: Rect?
        val rightRect: Rect?
        if (split) {
            fullRect = null
            val baseW = (right - left).coerceAtLeast(1)
            val gap = (baseW * splitRatioPref(m.isLandscape).getValue() / 100)
                .coerceIn(0, baseW * 60 / 100)
            val half = (baseW - gap) / 2
            leftRect = Rect(left, top, left + half, kbBottom)
            rightRect = Rect(right - half, top, right, kbBottom)
        } else {
            fullRect = Rect(left, top, right, kbBottom)
            leftRect = null
            rightRect = null
        }

        // action bar pinned to the bottom of the keyboard region, centered
        val buttonBarOffset = (8 * density).toInt()
        val barLeft = ((overlayW - barW) / 2).coerceAtLeast(0)
        val barTop = (kbBottom - barH - buttonBarOffset).coerceAtLeast(top + buttonBarOffset)
        val buttonBarRect = Rect(barLeft, barTop, barLeft + barW, barTop + barH)

        return TuneLayout(top, kbBottom, left, right, split, fullRect, leftRect, rightRect, buttonBarRect)
    }

    private fun hitTest(x: Int, y: Int, layout: TuneLayout, slopPx: Int, grabPx: Int): DragMode {
        if (layout.buttonBarRect.contains(x, y)) return DragMode.NONE

        val kbTop = layout.top
        val kbBottom = layout.kbBottom
        val cardLeft = layout.left
        val cardRight = layout.right

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
        if (layout.split) {
            val l = layout.leftRect ?: return DragMode.NONE
            val r = layout.rightRect ?: return DragMode.NONE
            if (y in l.top..l.bottom) {
                if (x in (l.right - slopPx)..(l.right + slopPx)) return DragMode.GAP
                if (x in (r.left - slopPx)..(r.left + slopPx)) return DragMode.GAP
            }
            if (l.contains(x, y) || r.contains(x, y)) return DragMode.HEIGHT
        } else {
            if (layout.fullRect?.contains(x, y) == true) return DragMode.HEIGHT
        }
        return DragMode.NONE
    }

    private fun applyDrag(mode: DragMode, totalDx: Float, totalDy: Float, density: Float) {
        when (mode) {
            DragMode.HEIGHT -> {
                val base = metrics?.heightBasePx ?: return
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
        // 否则卡片读到的 keyboardRect 还是旧的键盘高度。由 InputView 在 updateKeyboardSize
        // 后通过 doOnLayout{ refresh() } 触发，这里无需手动 syncBox。
    }

    @Composable
    fun OverlayContent(modifier: Modifier = Modifier) {
        val m = _metrics.value ?: return
        val density = LocalDensity.current.density
        val view = LocalView.current

        val grabPx = (20 * density).toInt()
        val slopPx = (24 * density).toInt()
        val bar = (3 * density).toInt()
        val gapBar = (4 * density).toInt()
        val inset = (24 * density).toInt()

        // measured size of the action bar (set by onSizeChanged on the bar)
        var barW by remember { mutableStateOf(0) }
        var barH by remember { mutableStateOf(0) }

        var fullActive by remember { mutableStateOf(false) }
        var leftActive by remember { mutableStateOf(false) }
        var rightActive by remember { mutableStateOf(false) }

        // latest metrics, readable from the (restart-free) pointer handler
        val metricsRef = remember { mutableStateOf(m) }
        SideEffect { metricsRef.value = m }

        // clear gesture exclusion when the overlay disappears
        DisposableEffect(Unit) {
            onDispose {
                if (Build.VERSION.SDK_INT >= 29) view.systemGestureExclusionRects = emptyList()
            }
        }

        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            val overlayW = with(LocalDensity.current) { maxWidth.toPx().roundToInt() }
            val layout = computeLayout(m, barW, barH, density, overlayW)

            // system gesture exclusion bands (window-absolute == overlay coords)
            if (Build.VERSION.SDK_INT >= 29) {
                SideEffect {
                    view.systemGestureExclusionRects = listOf(
                        Rect(layout.left, layout.top, layout.left + grabPx, layout.kbBottom),
                        Rect(layout.right - grabPx, layout.top, layout.right, layout.kbBottom),
                        Rect(
                            layout.left + grabPx,
                            layout.kbBottom - grabPx,
                            layout.right - grabPx,
                            layout.kbBottom
                        )
                    )
                }
            }

            // ---- drag-catching layer: fills the whole window so its coordinate
            //      frame stays fixed (window-absolute), matching the old View overlay.
            //      Touches above the toolbar band or on the action bar are passed
            //      through (not consumed) so the toolbar stays reachable and the
            //      buttons get their own clicks. ----
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        val slop = slopPx
                        val grab = grabPx
                        awaitEachGesture {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.pressed || it.previousPressed }
                                    ?: continue
                                val pos = change.position
                                when {
                                    change.changedToDown() -> {
                                        val cur = metricsRef.value
                                        // 键盘区之上（顶部延伸带 / 预编辑栏 / 工具栏）不拦截触摸，
                                        // 让工具栏与状态按钮保持可点
                                        if (pos.y < cur.topGuardPx) continue
                                        val lay = computeLayout(cur, barW, barH, density, overlayW)
                                        val mode = hitTest(pos.x.toInt(), pos.y.toInt(), lay, slop, grab)
                                        if (mode == DragMode.NONE) continue
                                        dragMode = mode
                                        startX = pos.x
                                        startY = pos.y
                                        startKeyboardHeightPx = cur.keyboardRect.height()
                                        startBottomPx = cur.bottomRect.height()
                                        startSidePx = cur.keyboardRect.left
                                        startLandscape = cur.isLandscape
                                        startBaseWidthPx = cur.keyboardRect.width().coerceAtLeast(1)
                                        startGapPx =
                                            (startBaseWidthPx * splitRatioPref(cur.isLandscape).getValue() / 100)
                                        setCardActive(mode, true) { a, b, c ->
                                            fullActive = a
                                            leftActive = b
                                            rightActive = c
                                        }
                                        if (Build.VERSION.SDK_INT >= 21) {
                                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                        }
                                        change.consume()
                                    }

                                    change.pressed && dragMode != DragMode.NONE -> {
                                        applyDrag(
                                            dragMode,
                                            pos.x - startX,
                                            pos.y - startY,
                                            density
                                        )
                                        change.consume()
                                    }

                                    !change.pressed && dragMode != DragMode.NONE -> {
                                        setCardActive(dragMode, false) { a, b, c ->
                                            fullActive = a
                                            leftActive = b
                                            rightActive = c
                                        }
                                        dragMode = DragMode.NONE
                                        change.consume()
                                    }
                                }
                            }
                        }
                    }
            )

            val cs = MiuixTheme.colorScheme

            // ---- frosted cards (visual only; pointer falls through to the catcher) ----
            if (layout.split) {
                val lr = layout.leftRect ?: return@BoxWithConstraints
                TuneCard(
                    rect = lr,
                    label = R.string.keyboard_tune_split,
                    active = leftActive,
                    density = density
                )
                val rr = layout.rightRect ?: return@BoxWithConstraints
                TuneCard(
                    rect = rr,
                    label = R.string.keyboard_tune_split,
                    active = rightActive,
                    density = density
                )
            } else {
                val fr = layout.fullRect ?: return@BoxWithConstraints
                TuneCard(
                    rect = fr,
                    label = R.string.keyboard_tune_height,
                    active = fullActive,
                    density = density
                )
            }

            // ---- grips (thin accent bars just inside the card edges) ----
            TuneGrip(
                rect = Rect(layout.left + bar, layout.top + inset, layout.left + bar + gapBar, layout.kbBottom - inset),
                density = density
            )
            TuneGrip(
                rect = Rect(layout.right - bar - gapBar, layout.top + inset, layout.right - bar, layout.kbBottom - inset),
                density = density
            )
            TuneGrip(
                rect = Rect(layout.left + inset, layout.kbBottom - bar - gapBar, layout.right - inset, layout.kbBottom - bar),
                density = density
            )

            // ---- action bar (always inside the keyboard region, always clickable) ----
            TuneButtonBar(
                rect = layout.buttonBarRect,
                onReset = { resetToDefaults() },
                onCancel = { applySnapshot(true) },
                onConfirm = { hide() },
                onSize = { w, h -> barW = w; barH = h }
            )
        }
    }

    private fun setCardActive(
        mode: DragMode,
        active: Boolean,
        apply: (full: Boolean, left: Boolean, right: Boolean) -> Unit
    ) {
        val full = when (mode) {
            DragMode.HEIGHT, DragMode.BOTTOM, DragMode.SIDE_LEFT, DragMode.SIDE_RIGHT -> active
            else -> false
        }
        val left = when (mode) {
            DragMode.HEIGHT, DragMode.BOTTOM, DragMode.SIDE_LEFT, DragMode.SIDE_RIGHT -> active
            DragMode.GAP -> active
            else -> false
        }
        val right = when (mode) {
            DragMode.GAP -> active
            else -> false
        }
        apply(full, left, right)
    }

    @Composable
    private fun TuneCard(
        rect: Rect,
        label: Int,
        active: Boolean,
        density: Float
    ) {
        val cs = MiuixTheme.colorScheme
        val wDp = with(LocalDensity.current) { rect.width().toDp() }
        val hDp = with(LocalDensity.current) { rect.height().toDp() }
        Box(
            Modifier
                .offset { IntOffset(rect.left, rect.top) }
                .size(wDp, hDp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    cs.background.copy(alpha = if (active) 0.78f else 0.55f),
                    RoundedCornerShape(12.dp)
                )
                .border(
                    2.dp,
                    cs.primary.copy(alpha = 0.85f),
                    RoundedCornerShape(12.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (rect.width() >= (64 * density).toInt()) {
                Text(
                    text = stringResource(label),
                    color = cs.onSurface.copy(alpha = 0.85f),
                    fontSize = 12.sp
                )
            }
        }
    }

    @Composable
    private fun TuneGrip(rect: Rect, density: Float) {
        val cs = MiuixTheme.colorScheme
        val wDp = with(LocalDensity.current) { rect.width().toDp() }
        val hDp = with(LocalDensity.current) { rect.height().toDp() }
        Box(
            Modifier
                .offset { IntOffset(rect.left, rect.top) }
                .size(wDp, hDp)
                .clip(RoundedCornerShape(2.dp))
                .background(cs.primary.copy(alpha = 0.85f), RoundedCornerShape(2.dp))
        )
    }

    @Composable
    private fun TuneButtonBar(
        rect: Rect,
        onReset: () -> Unit,
        onCancel: () -> Unit,
        onConfirm: () -> Unit,
        onSize: (Int, Int) -> Unit
    ) {
        val cs = MiuixTheme.colorScheme
        // NOTE: size to content (the 3 action buttons), NOT to rect — rect.width()/height()
        // come from barW/barH which are themselves fed back by onSizeChanged below, so forcing
        // a fixed .size() would create a 0x0 feedback loop and the dock would never appear.
        // Position only via offset; onSizeChanged reports the real measured size for hit-testing.
        Box(
            Modifier
                .offset { IntOffset(rect.left, rect.top) }
                .clip(RoundedCornerShape(24.dp))
                .background(cs.background, RoundedCornerShape(24.dp))
                .onSizeChanged {
                    onSize(it.width, it.height)
                },
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TuneActionButton(R.drawable.ic_baseline_settings_backup_restore_24, R.string.tune_reset, onReset)
                Box(Modifier.size(12.dp))
                TuneActionButton(R.drawable.ic_baseline_close_24, R.string.tune_cancel, onCancel)
                Box(Modifier.size(12.dp))
                TuneActionButton(R.drawable.ic_baseline_check_24, R.string.tune_confirm, onConfirm)
            }
        }
    }

    @Composable
    private fun TuneActionButton(iconRes: Int, descRes: Int, onClick: () -> Unit) {
        val cs = MiuixTheme.colorScheme
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(50))
                .background(cs.primary.copy(alpha = 0.16f), RoundedCornerShape(50))
                .clickable { onClick() }
                .padding(10.dp),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.foundation.Image(
                painter = painterResource(iconRes),
                contentDescription = stringResource(descRes),
                colorFilter = ColorFilter.tint(cs.primary),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
