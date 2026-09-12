/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.expanded

import android.content.res.Configuration
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.core.CandidateAction
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.bar.ComposeKawaiiBarComponent
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.BooleanKey.ExpandedCandidatesEmpty
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.TransitionEvent.ExpandedCandidatesAttached
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.TransitionEvent.ExpandedCandidatesDetached
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.candidates.SplitCandidatesKeyboard
import org.fcitx.fcitx5.android.input.candidates.horizontal.CandidateBarState
import org.fcitx.fcitx5.android.input.candidates.horizontal.ComposeCandidateComponent
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.inputView
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.keyboard.preferenceState
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.wm.ComposeWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.input.wm.createComposeWindowView
import org.mechdancer.dependency.manager.must

/**
 * 「展开候选」窗口（Compose 版，取代 `BaseExpandedCandidateWindow` /
 * `FlexboxExpandedCandidateWindow` / `GridExpandedCandidateWindow` 与 `ExpandedCandidateLayout`）。
 *
 * **形态只有一种**：表格（网格）。原「流式（Flexbox）」形态与 `expandedCandidateStyle` 偏好已移除；
 * 列数由 [computeGridSpanCount] 按列表**前段候选的实测宽度**（em）与列数上限反推
 * （与 View 侧 `SpanHelper` 同口径，避免一行词数过多导致滚动掉帧），
 * `expandedCandidateGridSpanCount` 偏好降级为列数上限。
 *
 * 职责分配（对标 View 侧 `BaseExpandedCandidateWindow`）：
 * - 窗口壳与生命周期：本类。类头与 `onCreateView()` 写法照抄 [KeyboardWindow]（非 essential
 *   Compose 窗口每次 attach 都新建实例、detach 时 `disposeComposition()`）；
 * - 渲染：`ComposeExpandedCandidatesUi`（标签栏 + 表格 + 内嵌键盘）；
 * - 数据：`CandidatesPagingSource` 分页 + [ComposeCandidateComponent] 的 `total` /
 *   `expandedCandidateOffset`（不直接订阅 `CandidateListEvent`）。
 */
class ComposeExpandedCandidateWindow :
    InputWindow.SimpleInputWindow<ComposeExpandedCandidateWindow>(),
    ComposeWindow,
    InputBroadcastReceiver {

    private val service by manager.inputMethodService()
    private val fcitx by manager.fcitx()
    private val inputView by manager.inputView()
    private val commonKeyActionListener: CommonKeyActionListener by manager.must()
    private val bar: ComposeKawaiiBarComponent by manager.must()
    private val composeCandidate: ComposeCandidateComponent by manager.must()
    private val windowManager: InputWindowManager by manager.must()
    private val returnKeyDrawable: ReturnKeyDrawableComponent by manager.must()
    private val popup: PopupComponent by manager.must()

    private val keyboardPrefs = AppPrefs.getInstance().keyboard
    private val disableAnimation = AppPrefs.getInstance().advanced.disableAnimation

    /**
     * 列数上限（横竖屏各一项）。
     *
     * 不再是「用户固定的列数」：实际列数由 [computeGridSpanCount] 算出，本偏好只参与上限钳制。
     * 该偏好同时还被横向候选栏的填宽逻辑（`ComposeCandidateComponent.maxSpanCountPref`）复用，
     * 因此保留、不随样式偏好一并移除。
     */
    private val maxSpanCountPref by lazy {
        keyboardPrefs.run {
            if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                expandedCandidateGridSpanCountLandscape
            } else {
                expandedCandidateGridSpanCount
            }
        }
    }

    // ------------------------------------------------------------------
    // 状态（窗口实例与 Composition 同生命周期）
    // ------------------------------------------------------------------

    /** 表格滚动状态：窗口持有，翻页键与 UI 共用同一份。 */
    private val gridState = LazyGridState()

    /** 挂起的「对齐到横向候选栏当前位置」目标；分页数据还没到该位置时留着，到达后再消费。 */
    private var pendingScrollOffset by mutableStateOf<Int?>(null)

    /** 最近一次对齐到的候选下标（`onDetached()` 判定「展开页是否已看完」用）。 */
    private var lastAlignedOffset = 0

    /** 候选分组标签：可滚动区 + 固定区拼接、分隔项已丢弃。 */
    private var tabs by mutableStateOf<List<CandidateAction>>(emptyList())

    /**
     * 内嵌键盘的回车图标。
     *
     * **不能在属性初始化器里读 [returnKeyDrawable]**：窗口在 `InputView.<init>` 时构造，
     * 那时依赖作用域尚未装配（`PickerWindow` 曾因此抛 `ComponentNotExistException` 崩溃），
     * 故初值为 0，`onAttached()` 里再取真值。
     */
    /**
     * 内嵌键盘的弹层出口（回车键长按弹 emoji 小键盘）。`lazy` 确保实际读取推迟到 attach 之后。
     */
    private val popupActionListener by lazy { popup.listener }

    private var returnDrawable by mutableStateOf(0)

    private var offsetJob: Job? = null

    /**
     * 由 `Content()` 通过 [rememberCoroutineScope] 捕获的 Compose 协程作用域。
     * 翻页动画（`animateScrollToItem`）必须在带 `MonotonicFrameClock` 的 Compose 作用域里执行，
     * 而 `lifecycleScope` 没有这个时钟，直接调会抛 `IllegalStateException` 崩溃。
     */
    private var composeScope: CoroutineScope? = null

    private val pager by lazy {
        Pager(
            config = PagingConfig(pageSize = PageSize, enablePlaceholders = false),
            pagingSourceFactory = { CandidatesPagingSource(fcitx, composeCandidate.total) }
        )
    }

    /**
     * 内嵌键盘的按键出口：↑/↓ 走翻页，其余（⌫ / ⏎）原样转发给公共按键监听。
     * 对应 `BaseExpandedCandidateWindow` 里的同名包装。
     */
    private val keyActionListener = KeyActionListener { action, source ->
        val handled = action is KeyAction.LayoutSwitchAction && when (action.act) {
            SplitCandidatesKeyboard.PageUp -> {
                prevPage()
                true
            }

            SplitCandidatesKeyboard.PageDown -> {
                nextPage()
                true
            }

            else -> false
        }
        if (!handled) {
            commonKeyActionListener.listener.onKeyAction(action, source)
        }
    }

    /**
     * 兜底路径：`InputWindowManager` 对 [ComposeWindow] 走统一 ComposeView 宿主，
     * 正常不会调用到这里；保留实现以防窗口被非 Compose 路径创建。
     */
    override fun onCreateView(): View = createComposeWindowView(context) { Content() }

    @Composable
    override fun Content() {
        // 捕获带 MonotonicFrameClock 的 Compose 作用域，供翻页动画使用（见 scrollPage）。
        composeScope = rememberCoroutineScope()
        val items = pager.flow.collectAsLazyPagingItems()
        val maxSpanCount = maxSpanCountPref.preferenceState()
        val itemCount = items.itemCount

        // 引擎候选刷新（applyCandidates）时展开窗口的旧分页数据失效：
        // CandidatesPagingSource 在创建时捕获 total，不失效会一直显示旧候选且分页终点错位。
        // candidateGeneration 是 Compose 快照状态，代次变化触发本 effect 重启 → 使分页源失效重建。
        val candidateGeneration = composeCandidate.candidateGeneration
        LaunchedEffect(candidateGeneration) {
            if (candidateGeneration > 0) {
                items.refresh()
            }
        }

        // 对齐横向候选栏当前可见位置：等首屏数据到达（itemCount 覆盖目标）后再滚，
        // 等价于 View 侧的 `refreshWithOffset` + `addLoadStateListener` 首次补偿滚动。
        LaunchedEffect(pendingScrollOffset, itemCount) {
            val target = pendingScrollOffset ?: return@LaunchedEffect
            if (itemCount > target) {
                gridState.scrollToItem(target)
                pendingScrollOffset = null
            }
        }

        ComposeExpandedCandidatesUi(
            items = items,
            tabs = tabs,
            gridState = gridState,
            maxSpanCount = maxSpanCount,
            returnDrawable = returnDrawable,
            keyActionListener = keyActionListener,
            popupActionListener = popupActionListener,
            onTabSelected = ::onTabSelected,
            onCandidateSelect = { index -> fcitx.launchOnReady { it.select(index) } },
            onCandidateLongClick = { index, text, anchor ->
                // 与横向候选栏同一条路径：锚点用窗口绝对坐标，菜单由 InputView 的 Compose 覆盖层承载
                inputView.showCandidateActionMenu(index, text, anchor)
            },
            modifier = Modifier.fillMaxSize(),
        )
    }

    private fun onTabSelected(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        fcitx.launchOnReady { it.triggerCandidateListTabAction(tab.id) }
    }

    // ------------------------------------------------------------------
    // 翻页：两方向都是「一整屏行数」的位移，目标行首项贴到视口顶部（SNAP_TO_START）。
    // —— 下翻：最后一个完全可见行的下一行；
    //    上翻：第一个完全可见行往前「一屏完全可见行数」行。
    // ------------------------------------------------------------------

    private fun prevPage() = scrollPage(forward = false)

    private fun nextPage() = scrollPage(forward = true)

    /**
     * 翻页：把目标行的首项滚到视口顶部（SNAP_TO_START），位移为**一整屏行数**。
     *
     * 目标下标由纯函数 [computePageTargetIndex] 算出（含「上翻是一整页而不是一行」的语义，
     * 见其 KDoc 与 `ExpandedCandidatePageTargetTest`）。
     *
     * 动画滚动必须在 Compose 协程作用域里跑：`animateScrollToItem` 依赖 `MonotonicFrameClock`，
     * 而 `lifecycleScope` 不带该时钟会直接抛 `IllegalStateException` 崩溃（见下方 scope 选择）。
     */
    private fun scrollPage(forward: Boolean) {
        val info = gridState.layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isEmpty() || info.totalItemsCount <= 0) return
        val viewportStart = info.viewportStartOffset
        val viewportEnd = info.viewportEndOffset
        // 完全可见：顶部 >= 视口顶 且 底部 <= 视口底；没有完全可见项时退回首/末可见项。
        val completelyVisible = visible.filter {
            it.offset.y >= viewportStart && it.offset.y + it.size.height <= viewportEnd
        }
        val target = computePageTargetIndex(
            firstVisibleIndex = (completelyVisible.firstOrNull() ?: visible.first()).index,
            lastVisibleIndex = (completelyVisible.lastOrNull() ?: visible.last()).index,
            columns = info.maxSpan,
            totalItemsCount = info.totalItemsCount,
            forward = forward,
        )
        // 动画滚动必须在 Compose 协程作用域里跑（需要 MonotonicFrameClock）；
        // 若作用域尚未就绪则退回无动画滚动，避免 IllegalStateException 崩溃。
        val scope = composeScope
        val block: suspend CoroutineScope.() -> Unit = {
            if (disableAnimation.getValue()) {
                gridState.scrollToItem(target)
            } else {
                gridState.animateScrollToItem(target)
            }
        }
        if (scope != null) {
            scope.launch(block = block)
        } else {
            service.lifecycleScope.launch { gridState.scrollToItem(target) }
        }
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    override fun onAttached() {
        bar.expandButtonStateMachine.push(ExpandedCandidatesAttached)
        // 依赖只能在 attach 之后读（见 returnDrawable 的注释）
        returnDrawable = returnKeyDrawable.resourceId
        updateTabs(fcitx.runImmediately { inputPanelCached.tabs })
        offsetJob = service.lifecycleScope.launch {
            composeCandidate.expandedCandidateOffset.collect { offset ->
                // 滑动模式下 offset 可能为 0 但确实没有候选，故以 total 判定「无候选」
                if (composeCandidate.total <= 0) {
                    windowManager.attachWindow(KeyboardWindow)
                } else {
                    lastAlignedOffset = offset
                    pendingScrollOffset = offset
                }
            }
        }
    }

    override fun onDetached() {
        val isEmpty = when (composeCandidate.currentState) {
            is CandidateBarState.Idle -> true
            is CandidateBarState.Active -> composeCandidate.total == lastAlignedOffset
        }
        bar.expandButtonStateMachine.push(
            ExpandedCandidatesDetached,
            ExpandedCandidatesEmpty to isEmpty
        )
        offsetJob?.cancel()
        offsetJob = null
    }

    override fun onPreeditEmptyStateUpdate(empty: Boolean) {
        if (empty) {
            // 预编辑被清空 → 回退主键盘
            windowManager.attachWindow(KeyboardWindow)
        }
    }

    override fun onInputPanelUpdate(data: FcitxEvent.InputPanelEvent.Data) {
        updateTabs(data.tabs)
    }

    override fun onReturnKeyDrawableUpdate(resourceId: Int) {
        // 编辑框类型变化（回车图标随之变化）时同步给内嵌键盘
        returnDrawable = resourceId
    }

    /**
     * 切分候选分组标签：`isSeparator` 之前的是可滚动区，分隔项之后的是固定区，
     * 两者拼成一条横向标签栏（分隔项本身丢弃，与 View 侧 `updateTabs` 同口径）。
     */
    private fun updateTabs(newTabs: Array<CandidateAction>) {
        val scrollable = newTabs.takeWhile { !it.isSeparator }
        val pinned = newTabs.drop(scrollable.size + 1).filter { !it.isSeparator }
        tabs = scrollable + pinned
    }

    private companion object {
        /** 与 View 侧 `PagingConfig` 一致 */
        const val PageSize = 48
    }
}
