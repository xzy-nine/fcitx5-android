/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.InputFeedbacks
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 拖选时进入可视区上下该范围内即开始自动滚动 */
private val AutoScrollEdge = 48.dp

/** 手指越过视口边界后、再远离该距离即达到最大滚动速度（越超出越快） */
private val AutoScrollRamp = 48.dp

/** 自动滚动的每帧最大/最小步长 */
private val AutoScrollMaxStep = 16.dp
private val AutoScrollMinStep = 2.dp

/** 自动滚动刷新间隔，约一帧 */
private const val AutoScrollIntervalMillis = 16L

/**
 * 分词芯片流：点击 / 长按拖选由本容器统一处理，其余情况不拦截手势。
 *
 * 手势：
 * - 单击词块 → 切换该词块选中态；
 * - **长按前**（系统长按阈值内）沿任意方向移动超过 `touchSlop` → 本容器放弃本次手势，
 *   事件不做消费，交还父级 `verticalScroll`，因此普通上下拖动可正常滚动列表；
 * - 长按（`ViewConfiguration.getLongPressTimeout()`）→ 进入拖选，锚点为长按处词块
 *   （区间外原有选中态保留），此后才接管（消费）手势；
 * - 拖选滑过词块 → 按「锚点..当前」整段选中，仍保留区间外的原有选中态；
 * - 拖选期间手指进入可视区上/下边缘带 → 自动滚动；越过视口边界后，**超出越远滚动越快**，
 *   并按滚动后的位置继续扩展选区，解决内容超出一屏时无法一次选全的问题。
 *
 * 命中测试用词块相对本容器的坐标（[boundsInParent]）；未命中时退化为「纵向最近的词块」，
 * 与旧实现 `nearestWord` 一致。
 *
 * @param scrollState 外层滚动容器状态，拖选到边缘时由本容器驱动
 * @param viewportBounds 外层滚动容器可视区在窗口坐标系下的范围（用于边缘判定）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ClipboardSegmentFlow(
    segments: List<String>,
    selected: Set<Int>,
    onSelectionChange: (Set<Int>) -> Unit,
    scrollState: ScrollState,
    viewportBounds: Rect?,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    // 手势运行中需要读最新选区/回调/视口，而 pointerInput 的 key 只有 segments，故用 remembered provider
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelectionChange by rememberUpdatedState(onSelectionChange)
    val currentViewport by rememberUpdatedState(viewportBounds)
    val dragState = remember { SegmentDragState() }
    // 词块在本容器内的坐标（局部坐标），每次布局刷新；非 compose state，避免拖动时触发重组
    val chipBounds = remember(segments) { HashMap<Int, Rect>() }
    // 本容器（FlowRow）的布局坐标，用于窗口/局部坐标互转（自动滚动期间词块会随内容移动）
    val flowCoords = remember { mutableStateOf<LayoutCoordinates?>(null) }
    // 手指当前在窗口坐标系下的位置（自动滚动期间没有 move 事件，靠它重算命中）
    val lastWindowPos = remember { mutableStateOf(Offset.Zero) }

    // 依据局部坐标更新选区终点（拖选期间唯一入口）
    fun selectAtLocal(local: Offset) {
        val cur = hitTest(chipBounds, local) ?: nearestByY(chipBounds, local.y)
        if (cur != null && cur != dragState.last) {
            dragState.last = cur
            // 与旧实现一致：仅在拖选「新划入」词块时震动
            if (dragState.applyRange(segments.size, currentOnSelectionChange)) {
                InputFeedbacks.hapticFeedback(view)
            }
        }
    }

    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { flowCoords.value = it }
            .pointerInput(segments) {
                val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                val touchSlop = viewConfiguration.touchSlop
                val edgePx = AutoScrollEdge.toPx()
                val rampPx = AutoScrollRamp.toPx()
                val maxStepPx = AutoScrollMaxStep.toPx()
                val minStepPx = AutoScrollMinStep.toPx()
                // 超出视口边界 beyond 距离 → 步长（越超出越快，封顶 maxStepPx）
                fun stepFor(beyond: Float): Float {
                    val ratio = (beyond / rampPx).coerceIn(0f, 1f)
                    return (maxStepPx * ratio).coerceAtLeast(minStepPx)
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 注意：此处不消费 down —— 未进入拖选前要让父级 verticalScroll 能正常滚动
                    val downIdx = hitTest(chipBounds, down.position) ?: nearestByY(chipBounds, down.position.y)
                    dragState.begin(
                        anchor = downIdx,
                        working = currentSelected,
                        restore = BooleanArray(segments.size) { it in currentSelected },
                    )
                    lastWindowPos.value = flowCoords.value?.localToWindow(down.position) ?: down.position

                    // 长按进入拖选：锚点自身立即选中（保留区间外原选中态）
                    val longPressJob: Job = scope.launch {
                        delay(longPressTimeout)
                        if (dragState.moved) return@launch
                        dragState.dragging = true
                        InputFeedbacks.hapticFeedback(view, longPress = true)
                        dragState.applyRange(segments.size, currentOnSelectionChange)
                    }

                    // 拖选期间持续运行的边缘自动滚动；未进入拖选时该循环空转
                    val autoScrollJob: Job = scope.launch {
                        while (isActive) {
                            delay(AutoScrollIntervalMillis)
                            if (!dragState.dragging) continue
                            val viewport = currentViewport ?: continue
                            if (flowCoords.value == null) continue
                            val y = lastWindowPos.value.y
                            // 视口很小（如键盘矮）时触发带收敛到半高，避免上下带重叠
                            val band = minOf(edgePx, viewport.height / 2f)
                            val delta = when {
                                // 已越过视口边界：超出越远越快
                                y < viewport.top -> -stepFor(viewport.top - y)
                                y > viewport.bottom -> stepFor(y - viewport.bottom)
                                // 仅在边缘带内（尚未越过边界）：最慢速
                                y < viewport.top + band -> -minStepPx
                                y > viewport.bottom - band -> minStepPx
                                else -> 0f
                            }
                            if (delta == 0f) continue
                            scrollState.scrollBy(delta)
                            // 内容已随滚动位移，用当前坐标把窗口坐标换回局部坐标后重算命中
                            val after = flowCoords.value ?: continue
                            selectAtLocal(after.windowToLocal(lastWindowPos.value))
                        }
                    }

                    // 未进入拖选前移动（即普通滑动）→ 放弃手势、不消费，交还父级滚动
                    var abandonedToScroll = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (dragState.dragging) {
                                // 已进入拖选：由本容器接管
                                change.consume()
                                if (!change.pressed) break // 抬起
                                lastWindowPos.value =
                                    flowCoords.value?.localToWindow(change.position) ?: change.position
                                selectAtLocal(change.position)
                            } else {
                                if (!change.pressed) break // 抬起
                                if (abs(change.position.x - down.position.x) > touchSlop ||
                                    abs(change.position.y - down.position.y) > touchSlop
                                ) {
                                    dragState.moved = true
                                    abandonedToScroll = true
                                    break
                                }
                            }
                        }
                    } finally {
                        longPressJob.cancel()
                        autoScrollJob.cancel()
                    }

                    if (!abandonedToScroll && !dragState.dragging && downIdx != null) {
                        // 确认是单击才发声/震动，避免普通滚动起手也触发按键反馈
                        InputFeedbacks.hapticFeedback(view)
                        InputFeedbacks.soundEffect(InputFeedbacks.SoundEffect.Standard)
                        dragState.toggle(downIdx)
                        currentOnSelectionChange(dragState.snapshot())
                    }
                }
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        segments.forEachIndexed { index, seg ->
            val isSel = index in selected
            Box(
                modifier = Modifier
                    .onGloballyPositioned { chipBounds[index] = it.boundsInParent() }
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (isSel) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.surfaceVariant
                    )
                    .border(
                        1.dp,
                        if (isSel) Color.Transparent else MiuixTheme.colorScheme.outline,
                        RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = seg,
                    color = if (isSel) MiuixTheme.colorScheme.onPrimary
                    else MiuixTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 拖选期间的可变状态。放在普通类里、用 `remember` 持有（非 compose state），
 * 拖动过程中只更新选集并回调，不经重组刷新词块。
 */
private class SegmentDragState {
    var dragging = false
    var moved = false
    var anchor = -1
    var last = -1
    private var working: MutableSet<Int> = mutableSetOf()
    private var restore: BooleanArray = BooleanArray(0)

    fun begin(anchor: Int?, working: Set<Int>, restore: BooleanArray) {
        this.anchor = anchor ?: -1
        this.last = this.anchor
        this.dragging = false
        this.moved = false
        this.working = working.toMutableSet()
        this.restore = restore
    }

    fun snapshot(): Set<Int> = working.toSet()

    /**
     * 按「锚点..当前」整段选中；区间外恢复手势开始前的选中态。
     * @return 本次是否有「新划入」的词块（用于决定是否震动）
     */
    fun applyRange(size: Int, onChange: (Set<Int>) -> Unit): Boolean {
        val lo = minOf(anchor, last)
        val hi = maxOf(anchor, last)
        if (lo < 0 || hi < 0) return false
        var changed = false
        var newlyAdded = false
        for (i in 0 until size) {
            val target = i in lo..hi || restore.getOrElse(i) { false }
            if (target) {
                if (working.add(i)) {
                    changed = true
                    newlyAdded = true
                }
            } else {
                if (working.remove(i)) changed = true
            }
        }
        if (changed) onChange(working.toSet())
        return newlyAdded
    }

    fun toggle(index: Int) {
        if (!working.add(index)) working.remove(index)
    }
}

/** 命中词块：返回其在容器内坐标所覆盖的词块索引。 */
private fun hitTest(bounds: Map<Int, Rect>, position: Offset): Int? {
    var best: Int? = null
    bounds.forEach { (index, rect) ->
        if (rect.contains(position)) best = index
    }
    return best
}

/** 未命中词块时取「纵向中心最近」的词块，与旧实现 `nearestWord` 一致。 */
private fun nearestByY(bounds: Map<Int, Rect>, y: Float): Int? {
    var best: Int? = null
    var bestDist = Float.MAX_VALUE
    bounds.forEach { (index, rect) ->
        val d = abs(rect.center.y - y)
        if (d < bestDist) {
            bestDist = d
            best = index
        }
    }
    return best
}
