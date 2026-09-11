/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import androidx.annotation.CallSuper
import androidx.annotation.DrawableRes
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.children
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView.GestureType
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView.OnGestureListener
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.leftOfParent
import splitties.views.dsl.constraintlayout.leftToRightOf
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.rightOfParent
import splitties.views.dsl.constraintlayout.rightToLeftOf
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.startToEndOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import timber.log.Timber
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

abstract class BaseKeyboard(
    context: Context,
    protected val theme: Theme,
    private val keyLayout: List<List<KeyDef>>
) : ConstraintLayout(context) {

    var keyActionListener: KeyActionListener? = null

    protected open val supportsSplitLayout: Boolean = true

    private val prefs = AppPrefs.getInstance()

    private val popupOnKeyPress by prefs.keyboard.popupOnKeyPress
    private val expandKeypressArea by prefs.keyboard.expandKeypressArea
    private val swipeSymbolDirection by prefs.keyboard.swipeSymbolDirection

    private val spaceSwipeMoveCursor = prefs.keyboard.spaceSwipeMoveCursor
    private val spaceKeys = mutableListOf<KeyView>()
    private val spaceSwipeChangeListener = ManagedPreference.OnChangeListener<Boolean> { _, v ->
        spaceKeys.forEach {
            it.swipeEnabled = v
        }
    }
    private val splitKeyboard = prefs.keyboard.splitKeyboard
    private val splitKeyboardListener = ManagedPreference.OnChangeListener<Boolean> { _, v ->
        rebuildKeyboardRows(v)
    }
    private val splitThresholdListener = ManagedPreference.OnChangeListener<Float> { _, _ ->
        rebuildKeyboardRows(splitKeyboard.getValue())
    }
    private val splitBlankRatioListener = ManagedPreference.OnChangeListener<Int> { _, _ ->
        rebuildKeyboardRows(splitKeyboard.getValue())
    }

    private val vivoKeypressWorkaround by prefs.advanced.vivoKeypressWorkaround

    private val hapticOnRepeat by prefs.keyboard.hapticOnRepeat

    var popupActionListener: PopupActionListener? = null

    private val selectionSwipeThreshold = dp(10f)
    private val inputSwipeThreshold = dp(36f)

    // a rather large threshold effectively disables swipe of the direction
    private val disabledSwipeThreshold = dp(800f)

    private val bounds = Rect()
    protected var keyRows: List<ConstraintLayout> = emptyList()
    private var lastInputMethod: InputMethodEntry? = null
    private var lastSplitAllowed = false
    private var lastSplitRequested = false
    private var layoutCallbacksEnabled = false
    private var lastMeasuredWidth = 0
    private var lastMeasuredHeight = 0

    private var isSplitLayout = false
    private var lastGapRatio = -1f

    init {
        isMotionEventSplittingEnabled = true
        rebuildKeyboardRows(splitKeyboard.getValue())
        spaceSwipeMoveCursor.registerOnChangeListener(spaceSwipeChangeListener)
        splitKeyboard.registerOnChangeListener(splitKeyboardListener)
        prefs.keyboard.splitKeyboardThreshold.registerOnChangeListener(splitThresholdListener)
        prefs.keyboard.splitKeyboardBlankRatio.registerOnChangeListener(splitBlankRatioListener)
        prefs.keyboard.splitKeyboardBlankRatioLandscape.registerOnChangeListener(splitBlankRatioListener)
    }

    protected open fun rebuildKeyboardRows(split: Boolean) {
        val effectiveSplit = split && supportsSplitLayout && isSplitAllowed()
        val gapRatio = splitGapRatio()
        // 空白比例变化也必须触发重建，否则调节间距后只有重新开关分离键盘才生效
        if (isSplitLayout == effectiveSplit && lastSplitRequested == split &&
            lastGapRatio == gapRatio && keyRows.isNotEmpty()
        ) return
        isSplitLayout = effectiveSplit
        lastSplitRequested = split
        lastGapRatio = gapRatio
        spaceKeys.clear()
        removeAllViews()
        val rowGroupPercents = if (effectiveSplit) {
            computeRowGroupPercents(keyLayout, gapRatio)
        } else {
            emptyList()
        }
        keyRows = keyLayout.mapIndexed { index, row ->
            if (effectiveSplit) {
                if (rowContainsSpaceKey(row)) {
                    createSpaceSplitRow(row, gapRatio)
                } else {
                    val groupPercent = rowGroupPercents.getOrNull(index) ?: 1f
                    createSplitRowWithGap(row, gapRatio, groupPercent)
                }
            } else {
                createKeyRow(row)
            }
        }
        keyRows.forEachIndexed { index, row ->
            if (effectiveSplit) {
                add(row, lParams(matchConstraints, matchConstraints) {
                    if (index == 0) topOfParent()
                    else below(keyRows[index - 1])
                    if (index == keyRows.size - 1) bottomOfParent()
                    else above(keyRows[index + 1])
                    startOfParent()
                    endOfParent()
                    // Use percentage-based height so rows are divided evenly regardless of
                    // layout timing. This guards against the last row collapsing to 0 height
                    // if a rebuild ever happens during a layout pass.
                    matchConstraintDefaultHeight = LayoutParams.MATCH_CONSTRAINT_PERCENT
                    matchConstraintPercentHeight = rowHeightPercent(keyRows.size)
                })
            } else {
                add(row, lParams {
                    if (index == 0) topOfParent()
                    else below(keyRows[index - 1])
                    if (index == keyRows.size - 1) bottomOfParent()
                    else above(keyRows[index + 1])
                    centerHorizontally()
                })
            }
        }
        lastInputMethod?.let { onInputMethodUpdate(it) }
        if (layoutCallbacksEnabled) {
            onKeyboardLayoutRebuilt()
        }
        Timber.d("rebuildKeyboardRows split=%s rows=%d", effectiveSplit, keyRows.size)
    }

    private fun splitGapRatio(): Float {
        val percent = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            prefs.keyboard.splitKeyboardBlankRatioLandscape.getValue()
        } else {
            prefs.keyboard.splitKeyboardBlankRatio.getValue()
        }
        return gapRatioFromBlankPercent(percent)
    }

    protected fun isSplitAllowed(width: Int = this.width, height: Int = this.height): Boolean {
        val w = if (width > 0) width else lastMeasuredWidth
        val h = if (height > 0) height else lastMeasuredHeight
        return isSplitAllowedByRatio(w, h, prefs.keyboard.splitKeyboardThreshold.getValue())
    }

    private fun createSplitRowWithGap(
        row: List<KeyDef>,
        gapRatio: Float,
        rowScale: Float
    ): ConstraintLayout {
        // 组内宽度分配见 KeyboardLayoutMath.computeSplitRowSpec（与下方布局一一对应）
        val spec = computeSplitRowSpec(row, gapRatio, rowScale)
        return constraintLayout {
            val group = constraintLayout {
                val gap = view(::View)
                val leftLayout = createKeyRow(
                    spec.leftRow,
                    chainBias = 1f,
                    widthScale = spec.leftScale,
                    allowExpand = false
                )
                val rightLayout = createKeyRow(
                    spec.rightRow,
                    chainBias = 0f,
                    widthScale = spec.rightScale,
                    allowExpand = false
                )
                add(leftLayout, lParams(0, matchParent) {
                    startOfParent()
                    topOfParent()
                    bottomOfParent()
                    matchConstraintDefaultWidth = LayoutParams.MATCH_CONSTRAINT_PERCENT
                    matchConstraintPercentWidth = spec.leftInGroup + spec.halfKeyInGroup
                })
                add(gap, lParams(0, matchParent) {
                    startToEndOf(leftLayout)
                    topOfParent()
                    bottomOfParent()
                    matchConstraintDefaultWidth = LayoutParams.MATCH_CONSTRAINT_PERCENT
                    matchConstraintPercentWidth = spec.gapInGroup - 2f * spec.halfKeyInGroup
                })
                add(rightLayout, lParams(0, matchParent) {
                    startToEndOf(gap)
                    endOfParent()
                    topOfParent()
                    bottomOfParent()
                    matchConstraintDefaultWidth = LayoutParams.MATCH_CONSTRAINT_PERCENT
                    matchConstraintPercentWidth = spec.rightInGroup + spec.halfKeyInGroup
                })
            }
            add(group, lParams(0, matchParent) {
                startOfParent()
                endOfParent()
                topOfParent()
                bottomOfParent()
                matchConstraintDefaultWidth = LayoutParams.MATCH_CONSTRAINT_PERCENT
                matchConstraintPercentWidth = spec.groupPercent
            })
        }
    }

    private fun createSpaceSplitRow(
        row: List<KeyDef>,
        gapRatio: Float
    ): ConstraintLayout {
        // 宽度分配见 KeyboardLayoutMath.computeSpaceSplitSpec（与下方布局一一对应）
        val spec = computeSpaceSplitSpec(row, gapRatio) ?: return createKeyRow(row)
        return constraintLayout {
            val group = constraintLayout {
                val leftLayout = createKeyRow(
                    spec.leftRow,
                    chainBias = 1f,
                    widthScale = spec.leftScale,
                    allowExpand = false
                )
                val rightLayout = createKeyRow(
                    spec.rightRow,
                    chainBias = 0f,
                    widthScale = spec.rightScale,
                    allowExpand = false
                )
                val spaceView = createKeyView(spec.spaceDef)
                add(leftLayout, lParams(0, matchParent) {
                    startOfParent()
                    topOfParent()
                    bottomOfParent()
                    matchConstraintDefaultWidth = LayoutParams.MATCH_CONSTRAINT_PERCENT
                    matchConstraintPercentWidth = spec.leftWidth
                })
                add(spaceView, lParams(0, matchParent) {
                    startToEndOf(leftLayout)
                    topOfParent()
                    bottomOfParent()
                    matchConstraintDefaultWidth = LayoutParams.MATCH_CONSTRAINT_PERCENT
                    matchConstraintPercentWidth = spec.spaceWidth
                })
                add(rightLayout, lParams(0, matchParent) {
                    startToEndOf(spaceView)
                    endOfParent()
                    topOfParent()
                    bottomOfParent()
                    matchConstraintDefaultWidth = LayoutParams.MATCH_CONSTRAINT_PERCENT
                    matchConstraintPercentWidth = spec.rightWidth
                })
            }
            add(group, lParams(0, matchParent) {
                startOfParent()
                endOfParent()
                topOfParent()
                bottomOfParent()
                matchConstraintDefaultWidth = LayoutParams.MATCH_CONSTRAINT_PERCENT
                matchConstraintPercentWidth = 1f
            })
        }
    }

    protected open fun createKeyRow(
        row: List<KeyDef>,
        chainBias: Float? = null,
        widthScale: Float = 1f,
        allowExpand: Boolean = true
    ): ConstraintLayout {
        val keyViews = row.map(::createKeyView)
        if (keyViews.isEmpty()) {
            return constraintLayout { }
        }
        // 宽度与「扩展触摸区」内缩比例见 KeyboardLayoutMath.computeKeyRowSlots
        val slots = computeKeyRowSlots(
            row = row,
            widthScale = widthScale,
            allowExpand = allowExpand,
            expandKeypressArea = expandKeypressArea
        )
        return constraintLayout Row@{
            keyViews.forEachIndexed { index, view ->
                val slot = slots[index]
                add(view, lParams {
                    centerVertically()
                    if (index == 0) {
                        leftOfParent()
                        horizontalChainStyle = LayoutParams.CHAIN_PACKED
                        if (chainBias != null) {
                            horizontalBias = chainBias
                        }
                    } else {
                        leftToRightOf(keyViews[index - 1])
                    }
                    if (index == keyViews.size - 1) {
                        rightOfParent()
                        // for RTL
                        horizontalChainStyle = LayoutParams.CHAIN_PACKED
                    } else {
                        rightToLeftOf(keyViews[index + 1])
                    }
                    matchConstraintPercentWidth = slot.percentWidth
                })
            }
            // 触摸区扩展：首尾键把多出来的宽度让给内容内缩（视觉宽度不变、触摸区变宽）
            val first = slots.first()
            if (first.marginStart != 0f) {
                keyViews.first().layoutMarginLeft = first.marginStart
            }
            val last = slots.last()
            if (last.marginEnd != 0f) {
                keyViews.last().layoutMarginRight = last.marginEnd
            }
        }
    }

    protected open fun createKeyView(def: KeyDef): KeyView {
        return when (def) {
            is SymbolSliderKey -> SymbolSliderKeyView(context, theme, def)
            else -> when (def.appearance) {
                is KeyDef.Appearance.AltText -> AltTextKeyView(context, theme, def.appearance)
                is KeyDef.Appearance.ImageText -> ImageTextKeyView(context, theme, def.appearance)
                is KeyDef.Appearance.Text -> TextKeyView(context, theme, def.appearance)
                is KeyDef.Appearance.Image -> ImageKeyView(context, theme, def.appearance)
            }
        }.apply {
            soundEffect = when (def) {
                is SpaceKey -> InputFeedbacks.SoundEffect.SpaceBar
                is MiniSpaceKey -> InputFeedbacks.SoundEffect.SpaceBar
                is BackspaceKey -> InputFeedbacks.SoundEffect.Delete
                is ReturnKey -> InputFeedbacks.SoundEffect.Return
                else -> InputFeedbacks.SoundEffect.Standard
            }
            if (def is SpaceKey) {
                spaceKeys.add(this)
                swipeEnabled = spaceSwipeMoveCursor.getValue()
                swipeRepeatEnabled = true
                swipeThresholdX = selectionSwipeThreshold
                swipeThresholdY = disabledSwipeThreshold
                onGestureListener = OnGestureListener { view, event ->
                    when (event.type) {
                        GestureType.Move -> when (val count = event.countX) {
                            0 -> false
                            else -> {
                                val sym =
                                    if (count > 0) FcitxKeyMapping.FcitxKey_Right else FcitxKeyMapping.FcitxKey_Left
                                val action = KeyAction.SymAction(KeySym(sym), KeyStates.Virtual)
                                repeat(count.absoluteValue) {
                                    onAction(action)
                                    if (hapticOnRepeat) InputFeedbacks.hapticFeedback(view)
                                }
                                true
                            }
                        }
                        else -> false
                    }
                }
            } else if (def is BackspaceKey) {
                swipeEnabled = true
                swipeRepeatEnabled = true
                swipeThresholdX = selectionSwipeThreshold
                swipeThresholdY = disabledSwipeThreshold
                onGestureListener = OnGestureListener { view, event ->
                    when (event.type) {
                        GestureType.Move -> {
                            val count = event.countX
                            if (count != 0) {
                                onAction(KeyAction.MoveSelectionAction(count))
                                if (hapticOnRepeat) InputFeedbacks.hapticFeedback(view)
                                true
                            } else false
                        }
                        GestureType.Up -> {
                            onAction(KeyAction.DeleteSelectionAction(event.totalX))
                            false
                        }
                        else -> false
                    }
                }
            }
            def.behaviors.forEach {
                when (it) {
                    is KeyDef.Behavior.Press -> {
                        setOnClickListener { _ ->
                            onAction(it.action)
                        }
                    }
                    is KeyDef.Behavior.LongPress -> {
                        setOnLongClickListener { _ ->
                            onAction(it.action)
                            true
                        }
                    }
                    is KeyDef.Behavior.Repeat -> {
                        repeatEnabled = true
                        onRepeatListener = { view ->
                            onAction(it.action)
                            if (hapticOnRepeat) InputFeedbacks.hapticFeedback(view)
                        }
                    }
                    is KeyDef.Behavior.Swipe -> {
                        swipeEnabled = true
                        swipeThresholdX = disabledSwipeThreshold
                        swipeThresholdY = inputSwipeThreshold
                        val oldOnGestureListener = onGestureListener ?: OnGestureListener.Empty
                        onGestureListener = OnGestureListener { view, event ->
                            when (event.type) {
                                GestureType.Up -> {
                                    if (!event.consumed && swipeSymbolDirection.checkY(event.totalY)) {
                                        onAction(it.action)
                                        true
                                    } else {
                                        false
                                    }
                                }
                                else -> false
                            } || oldOnGestureListener.onGesture(view, event)
                        }
                    }
                    is KeyDef.Behavior.DoubleTap -> {
                        doubleTapEnabled = true
                        onDoubleTapListener = { _ ->
                            onAction(it.action)
                        }
                    }
                }
            }
            def.popup?.forEach {
                when (it) {
                    // TODO: gesture processing middleware
                    is KeyDef.Popup.Menu -> {
                        setOnLongClickListener { view ->
                            view as KeyView
                            onPopupAction(PopupAction.ShowMenuAction(view.id, it, view.bounds))
                            // do not consume this LongClick gesture
                            false
                        }
                        val oldOnGestureListener = onGestureListener ?: OnGestureListener.Empty
                        swipeEnabled = true
                        onGestureListener = OnGestureListener { view, event ->
                            view as KeyView
                            when (event.type) {
                                GestureType.Move -> {
                                    onPopupChangeFocus(view.id, event.x, event.y)
                                }
                                GestureType.Up -> {
                                    onPopupTrigger(view.id)
                                }
                                else -> false
                            } || oldOnGestureListener.onGesture(view, event)
                        }
                    }
                    is KeyDef.Popup.Keyboard -> {
                        setOnLongClickListener { view ->
                            view as KeyView
                            onPopupAction(PopupAction.ShowKeyboardAction(view.id, it, view.bounds))
                            // do not consume this LongClick gesture
                            false
                        }
                        val oldOnGestureListener = onGestureListener ?: OnGestureListener.Empty
                        swipeEnabled = true
                        onGestureListener = OnGestureListener { view, event ->
                            view as KeyView
                            when (event.type) {
                                GestureType.Move -> {
                                    onPopupChangeFocus(view.id, event.x, event.y)
                                }
                                GestureType.Up -> {
                                    onPopupTrigger(view.id)
                                }
                                else -> false
                            } || oldOnGestureListener.onGesture(view, event)
                        }
                    }
                    is KeyDef.Popup.AltPreview -> {
                        val oldOnGestureListener = onGestureListener ?: OnGestureListener.Empty
                        onGestureListener = OnGestureListener { view, event ->
                            view as KeyView
                            if (popupOnKeyPress) {
                                when (event.type) {
                                    GestureType.Down -> onPopupAction(
                                        PopupAction.PreviewAction(view.id, it.content, view.bounds)
                                    )
                                    GestureType.Move -> {
                                        val triggered = swipeSymbolDirection.checkY(event.totalY)
                                        val text = if (triggered) it.alternative else it.content
                                        onPopupAction(
                                            PopupAction.PreviewUpdateAction(view.id, text)
                                        )
                                    }
                                    GestureType.Up -> {
                                        onPopupAction(PopupAction.DismissAction(view.id))
                                    }
                                }
                            }
                            // never consume gesture in preview popup
                            oldOnGestureListener.onGesture(view, event)
                        }
                    }
                    is KeyDef.Popup.Preview -> {
                        val oldOnGestureListener = onGestureListener ?: OnGestureListener.Empty
                        onGestureListener = OnGestureListener { view, event ->
                            view as KeyView
                            if (popupOnKeyPress) {
                                when (event.type) {
                                    GestureType.Down -> onPopupAction(
                                        PopupAction.PreviewAction(view.id, it.content, view.bounds)
                                    )
                                    GestureType.Up -> {
                                        onPopupAction(PopupAction.DismissAction(view.id))
                                    }
                                    else -> {}
                                }
                            }
                            // never consume gesture in preview popup
                            oldOnGestureListener.onGesture(view, event)
                        }
                    }
                }
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Skip transient zero-size callbacks (e.g. h=0 during rotation/config change).
        // Otherwise isSplitAllowed would flip lastSplitAllowed and trigger a spurious
        // rebuildKeyboardRows (removeAllViews + rebuild), occasionally dropping the last row.
        if (w <= 0 || h <= 0) return
        lastMeasuredWidth = w
        lastMeasuredHeight = h
        val allowed = isSplitAllowed(w, h)
        if (allowed != lastSplitAllowed) {
            lastSplitAllowed = allowed
            // Defer the rebuild to the next frame. Calling rebuildKeyboardRows
            // (removeAllViews + add) synchronously inside onSizeChanged puts it in the
            // middle of the layout pass, so the newly added last row is never measured
            // in that pass and ends up with a height of 0 (the bottom row vanishes).
            // Posting it runs after layout completes, where the rows are measured normally.
            removeCallbacks(rebuildOnSizeChange)
            post(rebuildOnSizeChange)
        }
        val (x, y) = intArrayOf(0, 0).also { getLocationInWindow(it) }
        bounds.set(x, y, x + width, y + height)
    }

    private val rebuildOnSizeChange = Runnable { rebuildKeyboardRows(splitKeyboard.getValue()) }

    private class TouchTarget(val view: KeyView, val hitRect: Rect)

    /**
     * HashMap of [PointerId (Int)][MotionEvent.getPointerId] to [TouchTarget]
     * for custom touch event dispatching
     */
    private val touchTargets = hashMapOf<Int, TouchTarget>()

    private fun releaseAllTouchTargets() {
        touchTargets.forEach {
            val keyView = it.value.view
            keyView.cancelGestures()
            onPopupAction(PopupAction.DismissAction(keyView.id))
        }
        touchTargets.clear()
    }

    private fun findTouchTarget(event: MotionEvent, pointerIndex: Int): TouchTarget? {
        val x0 = event.getX(pointerIndex).roundToInt()
        val y0 = event.getY(pointerIndex).roundToInt()
        val rowHitRect = Rect()
        val row = keyRows.find {
            it.getHitRect(rowHitRect)
            rowHitRect.contains(x0, y0)
        } ?: return null
        val x1 = x0 - rowHitRect.left
        val y1 = y0 - rowHitRect.top
        val keyHitRect = Rect()
        val key = row.children.filterIsInstance<KeyView>().find {
            it.getHitRect(keyHitRect)
            keyHitRect.contains(x1, y1)
        } ?: return null
        keyHitRect.offset(rowHitRect.left, rowHitRect.top)
        return TouchTarget(key, keyHitRect)
    }

    private fun dispatchMotionEventToTarget(
        event: MotionEvent,
        action: Int,
        pointerIndex: Int,
        target: TouchTarget
    ) {
        val childX = event.getX(pointerIndex) - target.hitRect.left
        val childY = event.getY(pointerIndex) - target.hitRect.top
        val e = MotionEvent.obtain(
            event.downTime, event.eventTime, action,
            childX, childY, event.getPressure(pointerIndex), event.getSize(pointerIndex),
            event.metaState, event.xPrecision, event.yPrecision,
            event.deviceId, event.edgeFlags
        )
        target.view.dispatchTouchEvent(e)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // intercept ACTION_DOWN and all following events will go to parent's onTouchEvent
        return if (vivoKeypressWorkaround && ev.actionMasked == MotionEvent.ACTION_DOWN) true
        else super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (vivoKeypressWorkaround) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    releaseAllTouchTargets()
                    val pid = event.getPointerId(0)
                    val target = findTouchTarget(event, 0) ?: return false
                    touchTargets[pid] = target
                    dispatchMotionEventToTarget(event, MotionEvent.ACTION_DOWN, 0, target)
                    return true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    val i = event.actionIndex
                    val pid = event.getPointerId(i)
                    val target = findTouchTarget(event, i) ?: return true
                    touchTargets[pid] = target
                    dispatchMotionEventToTarget(event, MotionEvent.ACTION_DOWN, i, target)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    for (i in 0 until event.pointerCount) {
                        val pid = event.getPointerId(i)
                        val target = touchTargets[pid] ?: continue
                        dispatchMotionEventToTarget(event, MotionEvent.ACTION_MOVE, i, target)
                    }
                    return true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val i = event.actionIndex
                    val pid = event.getPointerId(i)
                    val target = touchTargets[pid] ?: return true
                    dispatchMotionEventToTarget(event, MotionEvent.ACTION_UP, i, target)
                    touchTargets.remove(pid)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val pid = event.getPointerId(0)
                    val target = touchTargets[pid]
                    if (target == null) {
                        releaseAllTouchTargets()
                        return true
                    }
                    dispatchMotionEventToTarget(event, MotionEvent.ACTION_UP, 0, target)
                    touchTargets.remove(pid)
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    releaseAllTouchTargets()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    @CallSuper
    protected open fun onAction(
        action: KeyAction,
        source: KeyActionListener.Source = KeyActionListener.Source.Keyboard
    ) {
        keyActionListener?.onKeyAction(action, source)
    }

    @CallSuper
    protected open fun onPopupAction(action: PopupAction) {
        popupActionListener?.onPopupAction(action)
    }

    private fun onPopupChangeFocus(viewId: Int, x: Float, y: Float): Boolean {
        val changeFocusAction = PopupAction.ChangeFocusAction(viewId, x, y)
        popupActionListener?.onPopupAction(changeFocusAction)
        return changeFocusAction.outResult
    }

    private fun onPopupTrigger(viewId: Int): Boolean {
        val triggerAction = PopupAction.TriggerAction(viewId)
        // ask popup keyboard whether there's a pending KeyAction
        onPopupAction(triggerAction)
        val action = triggerAction.outAction ?: return false
        onAction(action, KeyActionListener.Source.Popup)
        onPopupAction(PopupAction.DismissAction(viewId))
        return true
    }

    open fun onAttach() {
        layoutCallbacksEnabled = true
        onKeyboardLayoutRebuilt()
    }

    open fun onReturnDrawableUpdate(@DrawableRes returnDrawable: Int) {
        // do nothing by default
    }

    open fun onPunctuationUpdate(mapping: Map<String, String>) {
        // do nothing by default
    }

    @CallSuper
    open fun onInputMethodUpdate(ime: InputMethodEntry) {
        lastInputMethod = ime
    }

    protected open fun onKeyboardLayoutRebuilt() {
        // for subclasses
    }

    fun refreshLayoutForPrefs() {
        rebuildKeyboardRows(splitKeyboard.getValue())
    }

    open fun onDetach() {
        layoutCallbacksEnabled = false
        releaseAllTouchTargets()
    }

}
