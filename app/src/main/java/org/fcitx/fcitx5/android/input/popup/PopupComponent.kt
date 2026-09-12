/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import android.graphics.Rect
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.broadcast.PunctuationComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import kotlin.time.Duration.Companion.milliseconds

/**
 * 按键弹窗层协调器。
 *
 * 对外契约（[PopupAction] / [PopupActionListener] / [listener] / [dismissAll]）与旧 View 实现完全一致，
 * 但内部不再持有 View，而是维护一份 [PopupLayerState]，由根组合经 [PopupOverlayContent] 订阅渲染。
 *
 * 所有生产方（`BaseKeyboard` / `TextKeyboard` / `PickerWindow` / `PickerPageUi` / `ComposeNumberRow`）
 * 仍以「窗口绝对坐标 [Rect]」作为锚点，无需改动。
 *
 * 旧实现 [PopupEntryUi] / [PopupKeyboardUi] / [PopupMenuUi] / [PopupContainerUi] 已断开接线，保留供对比。
 */
class PopupComponent :
    UniqueComponent<PopupComponent>(), Dependent, ManagedHandler by managedHandler() {

    private val service by manager.inputMethodService()
    private val context by manager.context()
    private val theme by manager.theme()
    private val punctuation: PunctuationComponent by manager.must()

    private val _state = MutableStateFlow(PopupLayerState())

    /** 100ms 最小显示时长对应的延迟隐藏任务，按触发键 id 索引 */
    private val dismissJobs = HashMap<Int, Job>()

    private val keyBottomMargin by lazy {
        context.dp(ThemeManager.prefs.keyVerticalMargin.getValue())
    }
    private val popupWidth by lazy {
        context.dp(38)
    }
    private val popupHeight by lazy {
        context.dp(116)
    }
    private val popupKeyHeight by lazy {
        context.dp(48)
    }
    private val menuKeySize by lazy {
        context.dp(48)
    }
    private val menuVerticalOffset by lazy {
        context.dp(-52)
    }
    /** 圆角半径：偏好值本身以 dp 为单位（原实现是 `context.dp(...)` 转 px） */
    private val popupRadius by lazy {
        ThemeManager.prefs.keyRadius.getValue().dp
    }
    private val hideThreshold = 100L

    /**
     * 本层在窗口中的位置。
     *
     * 由 [PopupOverlayContent] 的 `onGloballyPositioned` 持续更新（ComposeView 本身也是 View，
     * 语义与原 `getLocationInWindow` 一致），原 px 定位算式可以逐字保留。
     */
    private val rootBounds: Rect = Rect()

    private val visuals by lazy {
        PopupVisuals(
            backgroundColor = Color(theme.popupBackgroundColor),
            textColor = Color(theme.popupTextColor),
            activeBackgroundColor = Color(theme.genericActiveBackgroundColor),
            activeForegroundColor = Color(theme.genericActiveForegroundColor),
            menuInactiveBackgroundColor = Color(theme.accentKeyBackgroundColor),
            menuActiveBackgroundColor = Color(
                ColorUtils.compositeColors(
                    theme.keyPressHighlightColor,
                    theme.accentKeyBackgroundColor
                )
            ),
            menuIconTintColor = Color(theme.accentKeyTextColor),
            radius = popupRadius,
            elevation = 2.dp,
        )
    }

    /**
     * 弹窗层 Compose 覆盖层内容。
     *
     * 由根组合（`FcitxInputMethodService.createComposeInputView`）在 `AndroidView(InputView)` 之上
     * 调用，与键盘内容同处单一 Composition，替代原先独立的 `root` ComposeView 宿主。
     * 本层**不接收触摸**：Box 无任何 pointer handler，触摸穿透到下方 `AndroidView(InputView)`；
     * 手势仍由 Compose 键盘侧（`ComposeKey` / `ComposePickerPage` / `ComposeRecentSymbolsPanel`）
     * 捕获后经 [listener] 转发。
     *
     * 定位沿用「窗口绝对坐标」契约：`rootBounds` 由 [onGloballyPositioned] 追踪本层在窗口中的
     * 位置（填满 IME 窗口时即为窗口原点），原 px 定位算式无需改动。
     */
    @Composable
    fun PopupOverlayContent(modifier: Modifier = Modifier) {
        val state by _state.collectAsState()
        Box(
            modifier = modifier.onGloballyPositioned { coords ->
                val b = coords.boundsInWindow()
                rootBounds.set(
                    b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt()
                )
            }
        ) {
            PopupContent(state = state, visuals = visuals)
        }
    }

    // region 状态读写

    private fun entryOf(viewId: Int) = _state.value.entries.firstOrNull { it.viewId == viewId }

    private fun containerOf(viewId: Int) = _state.value.containers.firstOrNull { it.viewId == viewId }

    private fun upsertEntry(entry: PopupEntryState) = _state.update { state ->
        val index = state.entries.indexOfFirst { it.viewId == entry.viewId }
        val entries = if (index >= 0) {
            state.entries.toMutableList().also { it[index] = entry }
        } else {
            state.entries + entry
        }
        state.copy(entries = entries)
    }

    private fun upsertContainer(container: PopupContainerState) = _state.update { state ->
        val containers = state.containers.filterNot { it.viewId == container.viewId } + container
        state.copy(containers = containers)
    }

    private fun removeEntry(viewId: Int) = _state.update { state ->
        val entries = state.entries.filterNot { it.viewId == viewId }
        if (entries.size == state.entries.size) state else state.copy(entries = entries)
    }

    private fun removeContainer(viewId: Int) = _state.update { state ->
        val containers = state.containers.filterNot { it.viewId == viewId }
        if (containers.size == state.containers.size) state else state.copy(containers = containers)
    }

    private fun cancelDismissJob(viewId: Int) {
        dismissJobs.remove(viewId)?.cancel()
    }

    // endregion

    // region 弹窗操作

    private fun showPopup(viewId: Int, content: String, bounds: Rect) {
        val existing = entryOf(viewId)
        if (existing != null) {
            // 位置保持不变，只刷新内容与显示时间
            cancelDismissJob(viewId)
            upsertEntry(existing.withText(content, System.currentTimeMillis()))
            return
        }
        // 新建时顺带取消可能残留的延迟隐藏任务，避免把刚出现的气泡提前移除
        cancelDismissJob(viewId)
        val (windowX, windowY) = PopupLayoutMath.computeEntryPosition(
            bounds = bounds,
            popupWidth = popupWidth,
            popupHeight = popupHeight,
            keyBottomMargin = keyBottomMargin,
        )
        upsertEntry(
            PopupEntryState(
                viewId = viewId,
                text = content,
                // 与容器路径保持一致：窗口绝对坐标 → 相对 root 左上角
                x = windowX - rootBounds.left,
                y = windowY - rootBounds.top,
                width = popupWidth,
                height = popupHeight,
                contentHeight = popupKeyHeight,
                lastShowTime = System.currentTimeMillis(),
            )
        )
    }

    private fun updatePopup(viewId: Int, content: String) {
        // 与原实现一致：仅刷新内容，不更新 lastShowTime（最小显示时长从首次显示算起）
        entryOf(viewId)?.let { upsertEntry(it.withText(content, it.lastShowTime)) }
    }

    private fun showKeyboard(viewId: Int, keyboard: KeyDef.Popup.Keyboard, bounds: Rect) {
        val keys: List<String>
        val labels: List<String>
        when (keyboard) {
            is KeyDef.Popup.Keyboard.Preset -> {
                val preset = PopupPreset[keyboard.label] ?: return
                keys = preset.toList()
                labels = if (keyboard.transformPunctuation && punctuation.enabled) {
                    keys.map { punctuation.transform(it) }
                } else keys
            }

            is KeyDef.Popup.Keyboard.Explicit -> {
                keys = keyboard.items.toList()
                labels = keys
            }
        }
        if (labels.isEmpty()) return

        // 气泡是小键盘的「底座」占位：已存在则清空文本，否则新建一个空气泡
        val existing = entryOf(viewId)
        if (existing != null) {
            updatePopup(viewId, "")
        } else {
            showPopup(viewId, "", bounds)
        }

        val keyCount = labels.size
        val (rowCount, columnCount) = PopupLayoutMath.keyboardGrid(keyCount)
        // 注意：原实现把 popupWidth 当作小键盘的 keyWidth（见 PopupKeyboardUi 的构造参数顺序）
        val keyWidth = popupWidth
        val keyHeight = popupKeyHeight
        val focusColumn = PopupLayoutMath.calcInitialFocusedColumn(
            columnCount = columnCount,
            columnWidth = keyWidth,
            outerBounds = rootBounds,
            triggerBounds = bounds,
        )
        val (offsetX, offsetY) = PopupLayoutMath.computeKeyboardOffset(
            triggerBounds = bounds,
            keyWidth = keyWidth,
            keyHeight = keyHeight,
            // position popup keyboard higher, because of [^1]
            popupHeight = popupHeight + keyBottomMargin,
            rowCount = rowCount,
            focusColumn = focusColumn,
        )
        val columnOrder = PopupLayoutMath.createColumnOrder(columnCount, focusColumn)
        val keyOrders = PopupLayoutMath.computeKeyOrders(rowCount, columnCount, columnOrder)
        upsertContainer(
            PopupContainerState.Keyboard(
                viewId = viewId,
                x = bounds.left + offsetX - rootBounds.left,
                y = bounds.top + offsetY - rootBounds.top,
                // 初始焦点在最底行（行号 0）
                focusedIndex = keyOrders[0][focusColumn],
                offsetX = offsetX,
                offsetY = offsetY,
                keys = keys,
                labels = labels,
                keyOrders = keyOrders,
                rowCount = rowCount,
                columnCount = columnCount,
                keyWidth = keyWidth,
                keyHeight = keyHeight,
            )
        )
    }

    private fun showMenu(viewId: Int, menu: KeyDef.Popup.Menu, bounds: Rect) {
        // 空菜单没有可聚焦项，且会让 calcInitialFocusedColumn 的 coerceIn(0, -1) 抛异常
        if (menu.items.isEmpty()) return

        // 菜单与气泡不共存：原实现立即移除气泡（不经过 100ms 延迟）
        cancelDismissJob(viewId)
        removeEntry(viewId)

        val keySize = menuKeySize
        val columnCount = menu.items.size
        val focusColumn = PopupLayoutMath.calcInitialFocusedColumn(
            columnCount = columnCount,
            columnWidth = keySize,
            outerBounds = rootBounds,
            triggerBounds = bounds,
        )
        val (offsetX, offsetY) = PopupLayoutMath.computeMenuOffset(
            triggerBounds = bounds,
            keySize = keySize,
            focusColumn = focusColumn,
            verticalOffset = menuVerticalOffset,
        )
        val columnOrder = PopupLayoutMath.createColumnOrder(columnCount, focusColumn)
        upsertContainer(
            PopupContainerState.Menu(
                viewId = viewId,
                x = bounds.left + offsetX - rootBounds.left,
                y = bounds.top + offsetY - rootBounds.top,
                focusedIndex = columnOrder[focusColumn],
                offsetX = offsetX,
                offsetY = offsetY,
                items = menu.items.toList(),
                columnOrder = columnOrder,
                keySize = keySize,
            )
        )
    }

    /**
     * 手势焦点变更。
     *
     * 返回值由调用方**同步**读取（[PopupAction.ChangeFocusAction.outResult]），用于决定是否消费手势。
     */
    private fun changeFocus(viewId: Int, x: Float, y: Float): Boolean {
        return when (val container = containerOf(viewId)) {
            is PopupContainerState.Keyboard -> {
                val index = PopupLayoutMath.keyboardFocusIndex(
                    x = x - container.offsetX,
                    y = y - container.offsetY,
                    rowCount = container.rowCount,
                    columnCount = container.columnCount,
                    keyWidth = container.keyWidth,
                    keyHeight = container.keyHeight,
                    keyCount = container.labels.size,
                    keyOrders = container.keyOrders,
                )
                applyFocus(container, index)
            }

            is PopupContainerState.Menu -> {
                val index = PopupLayoutMath.menuFocusIndex(
                    x = x - container.offsetX,
                    columnCount = container.items.size,
                    keySize = container.keySize,
                    columnOrder = container.columnOrder,
                )
                applyFocus(container, index)
            }

            null -> false
        }
    }

    private fun applyFocus(container: PopupContainerState, index: Int): Boolean = when (index) {
        PopupLayoutMath.FOCUS_DISMISS -> {
            // 越界：关闭弹窗并消费手势
            dismissPopup(container.viewId)
            true
        }

        PopupLayoutMath.FOCUS_KEEP -> false

        else -> {
            if (index != container.focusedIndex) {
                upsertContainer(
                    when (container) {
                        is PopupContainerState.Keyboard -> container.withFocus(index)
                        is PopupContainerState.Menu -> container.withFocus(index)
                    }
                )
            }
            false
        }
    }

    /**
     * 取当前焦点项对应的按键动作。
     *
     * 返回值由调用方**同步**读取（[PopupAction.TriggerAction.outAction]）。
     */
    private fun triggerFocused(viewId: Int): KeyAction? = when (val container = containerOf(viewId)) {
        is PopupContainerState.Keyboard ->
            container.keys.getOrNull(container.focusedIndex)?.let { KeyAction.FcitxKeyAction(it) }

        is PopupContainerState.Menu ->
            container.items.getOrNull(container.focusedIndex)?.action

        null -> null
    }

    private fun dismissPopup(viewId: Int) {
        // 先取消同 id 上残留的延迟隐藏任务，避免它稍后把新显示的气泡提前移除
        cancelDismissJob(viewId)
        removeContainer(viewId)
        val entry = entryOf(viewId) ?: return
        val timeLeft = entry.lastShowTime + hideThreshold - System.currentTimeMillis()
        if (timeLeft <= 0L) {
            removeEntry(viewId)
        } else {
            dismissJobs[viewId] = service.lifecycleScope.launch {
                delay(timeLeft.milliseconds)
                removeEntry(viewId)
                dismissJobs.remove(viewId)
            }
        }
    }

    fun dismissAll() {
        dismissJobs.values.forEach { it.cancel() }
        dismissJobs.clear()
        _state.value = PopupLayerState()
    }

    // endregion

    val listener = PopupActionListener { action ->
        with(action) {
            when (this) {
                is PopupAction.ChangeFocusAction -> outResult = changeFocus(viewId, x, y)
                is PopupAction.DismissAction -> dismissPopup(viewId)
                is PopupAction.PreviewAction -> showPopup(viewId, content, bounds)
                is PopupAction.PreviewUpdateAction -> updatePopup(viewId, content)
                is PopupAction.ShowKeyboardAction -> showKeyboard(viewId, keyboard, bounds)
                is PopupAction.ShowMenuAction -> showMenu(viewId, menu, bounds)
                is PopupAction.TriggerAction -> outAction = triggerFocused(viewId)
            }
        }
    }
}
