/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.horizontal

import android.content.res.Configuration
import android.graphics.Rect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.bar.ComposeKawaiiBarComponent
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.BooleanKey.ExpandedCandidatesEmpty
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.TransitionEvent.ExpandedCandidatesUpdated
import org.fcitx.fcitx5.android.input.bar.LocalToolbarHeight
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.candidates.expanded.ComposeExpandedCandidateWindow
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.inputView
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import org.mechdancer.dependency.manager.must
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Compose 候选栏组件
 * 替代 HorizontalCandidateComponent，使用 Compose LazyRow 实现
 */
class ComposeCandidateComponent :
    UniqueComponent<ComposeCandidateComponent>(),
    Dependent,
    ManagedHandler by managedHandler(),
    InputBroadcastReceiver {

    private val context by manager.context()
    private val fcitx by manager.fcitx()
    private val inputView by manager.inputView()
    private val bar: ComposeKawaiiBarComponent by manager.must()
    private val service by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()

    private val keyboardPrefs = AppPrefs.getInstance().keyboard
    private val fillStyle by keyboardPrefs.horizontalCandidateStyle
    private val swipeEnabledPref = keyboardPrefs.horizontalCandidateSwipe
    private val candidateDividerPref = keyboardPrefs.candidateDivider
    // expandedCandidateStyle 偏好已随展开候选 Compose 化移除（只保留表格一种形态）；列数改为动态计算。
    // maxSpanCountPref（下方 lazy）保留：既被本组件的填宽逻辑复用（loadMoreBatch），也是展开候选列数上限来源。
    private val maxSpanCountPref by lazy {
        keyboardPrefs.run {
            if (context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT)
                expandedCandidateGridSpanCount
            else
                expandedCandidateGridSpanCountLandscape
        }
    }

    // 候选栏状态
    private val _state = MutableStateFlow<CandidateBarState>(CandidateBarState.Idle)
    /**
     * 首个可见候选下标（候选栏横向滑动偏移）。
     * **不放进 [_state]**：它是「通知展开窗口」的纯事件，寄生于内容状态会让滑动期间
     * 每跨过一个 item 就触发一次 _state 更新，连带重组工具栏等所有订阅者。
     */
    private val _scrollOffset = MutableStateFlow(0)

    /**
     * 候选集整体更换（非前缀延续）令牌：每次 [applyCandidates] 判定需重置滚动时自增，
     * 供 ComposeCandidateBar 把 LazyRow 滚回首位。不放进 [_state]：仅影响可视滚动位置，
     * 寄生于内容状态会让每次替换都触发多余重组。
     */
    private val _candidateResetToken = MutableStateFlow(0)
    val candidateResetToken: StateFlow<Int> = _candidateResetToken.asStateFlow()
    private val _expandedCandidateOffset = MutableSharedFlow<Int>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    // 展开窗口是否已显示
    private val _isExpandedWindowShown = MutableStateFlow(false)

    /**
     * 候选栏内容状态（唯一事实源）。
     * 工具栏据此驱动候选栏可见性：**内容就绪（Active）才显示**，避免与 barState 跨帧
     * 不同步导致候选栏显示但内容为空的闪烁。
     */
    val barState: StateFlow<CandidateBarState> = _state.asStateFlow()

    val expandedCandidateOffset = _expandedCandidateOffset.asSharedFlow()
    val isExpandedWindowShown = _isExpandedWindowShown.asStateFlow()
    val currentState: CandidateBarState get() = _state.value

    /**
     * 获取当前候选词总数
     * 兼容 HorizontalCandidateComponent 的 adapter.total 接口
     */
    val total: Int
        get() = when (val state = _state.value) {
            is CandidateBarState.Active -> state.total
            is CandidateBarState.Idle -> -1
        }

    /**
     * 获取当前已加载的候选词数量
     */
    val loadedCount: Int
        get() = when (val state = _state.value) {
            is CandidateBarState.Active -> state.candidates.size
            is CandidateBarState.Idle -> 0
        }

    private var lastExpandedOffset = -1
    @Volatile
    private var loadingMore = false
    @Volatile
    private var noMoreData = false

    /** 候选列表代数：每次 [applyCandidates] 递增，供展开候选窗口作分页刷新键。 */
    private var _candidateGeneration by mutableIntStateOf(0)
    val candidateGeneration: Int get() = _candidateGeneration

    private var lastCandidateData = FcitxEvent.CandidateListEvent.Data()

    private val loadMoreBatch by lazy {
        maxSpanCountPref.getValue().coerceAtLeast(LOAD_MORE_BATCH_MIN)
    }

    private fun refreshExpanded() {
        val currentState = _state.value
        if (currentState !is CandidateBarState.Active) {
            val offset = 0
            if (offset == lastExpandedOffset) return
            lastExpandedOffset = offset
            _expandedCandidateOffset.tryEmit(offset)
            bar.expandButtonStateMachine.push(
                ExpandedCandidatesUpdated,
                ExpandedCandidatesEmpty to true
            )
            return
        }

        val offset = _scrollOffset.value
        if (offset == lastExpandedOffset) return
        lastExpandedOffset = offset
        _expandedCandidateOffset.tryEmit(offset)
        val done = currentState.candidates.size >= currentState.total
        bar.expandButtonStateMachine.push(
            ExpandedCandidatesUpdated,
            ExpandedCandidatesEmpty to done
        )
    }

    /**
     * 当 LazyRow 滚动时更新 offset 并通知展开窗口
     */
    fun onScrollOffsetChanged(firstVisibleIndex: Int) {
        if (_state.value !is CandidateBarState.Active) return
        if (firstVisibleIndex == _scrollOffset.value) return
        _scrollOffset.value = firstVisibleIndex
        refreshExpanded()
    }

    private fun loadMoreIfNeeded() {
        if (loadingMore || noMoreData) return
        val currentState = _state.value
        if (currentState !is CandidateBarState.Active) return
        if (currentState.total >= 0 && currentState.candidates.size >= currentState.total) return
        loadMore()
    }

    private fun loadMore() {
        if (loadingMore || noMoreData) return
        val currentState = _state.value
        if (currentState !is CandidateBarState.Active) return
        if (currentState.total >= 0 && currentState.candidates.size >= currentState.total) return

        loadingMore = true
        val generation = _candidateGeneration
        val currentOffset = currentState.candidates.size

        fcitx.launchOnReady {
            try {
                val more = it.getCandidates(currentOffset, loadMoreBatch)
                if (generation == _candidateGeneration) {
                    if (more.isNotEmpty()) {
                        _state.update { state ->
                            when (state) {
                                is CandidateBarState.Active -> {
                                    state.copy(
                                        candidates = state.candidates.append(more),
                                    )
                                }
                                else -> state
                            }
                        }
                    } else {
                        noMoreData = true
                    }
                }
            } finally {
                if (generation == _candidateGeneration) {
                    loadingMore = false
                }
            }
        }
    }

    private fun applyCandidates(
        data: FcitxEvent.CandidateListEvent.Data,
        prevData: FcitxEvent.CandidateListEvent.Data = FcitxEvent.CandidateListEvent.Data()
    ) {
        val candidates = data.candidates
        val total = data.total

        // 在滑动模式下，如果新数据是旧数据的前缀，保持滚动位置
        val keepScroll = isPrefixOf(prevData.candidates, candidates)

        // 非「前缀延续」时候选集整体更换，滚动偏移归零（与旧实现的 offset 语义一致）
        if (!keepScroll) {
            _scrollOffset.value = 0
            _candidateResetToken.value++
        }

        _state.value = CandidateBarState.from(candidates, total)

        loadingMore = false
        noMoreData = false
        _candidateGeneration++
        lastExpandedOffset = -1

        refreshExpanded()
    }

    /**
     * [next] 是否以 [prev] 为前缀。用于判断候选集是「追加」还是「整体更换」。
     * 用索引循环代替 `take(...).toTypedArray().contentEquals(...)`，避免每次击键两次集合分配。
     */
    private fun isPrefixOf(prev: Array<CandidateWord>, next: Array<CandidateWord>): Boolean {
        if (prev.isEmpty() || next.size < prev.size) return false
        for (i in prev.indices) {
            if (next[i] != prev[i]) return false
        }
        return true
    }

    override fun onCandidateUpdate(data: FcitxEvent.CandidateListEvent.Data) {
        if (data == lastCandidateData) {
            return
        }
        val prevData = lastCandidateData
        lastCandidateData = data
        applyCandidates(data, prevData)
    }

    override fun onWindowDetached(window: InputWindow) {
        if (window is ComposeExpandedCandidateWindow) {
            _isExpandedWindowShown.value = false
        }
    }

    /**
     * 获取当前候选栏的视觉配置，全部取自 miuix 主题（不再读 fcitx View 主题）。
     */
    @Composable
    private fun getVisuals(): CandidateBarVisuals {
        return CandidateBarVisuals(
            textColor = MiuixTheme.colorScheme.onSurface,
            commentColor = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            pressHighlightColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.1f),
            dividerColor = MiuixTheme.colorScheme.dividerLine,
        )
    }

    /**
     * 获取填充模式
     */
    private fun getFillMode(): CandidateFillMode {
        return when (fillStyle) {
            HorizontalCandidateMode.NeverFillWidth -> CandidateFillMode.NeverFillWidth
            HorizontalCandidateMode.AutoFillWidth -> CandidateFillMode.AutoFillWidth
            HorizontalCandidateMode.AlwaysFillWidth -> CandidateFillMode.AlwaysFillWidth
        }
    }

    /**
     * 候选栏 Composable 内容。
     * 不再持有独立 ComposeView，由父级（合并后的单一 Composition）在 MiuixTheme 内直接调用，
     * 从而消除「ComposeView 内嵌 ComposeView」。
     */
    @Composable
    fun CandidateBarContent(modifier: Modifier = Modifier) {
        val state by _state.collectAsState()
        val isExpandedWindowShown by _isExpandedWindowShown.collectAsState()
        val scrollResetToken by candidateResetToken.collectAsState()

        // 监听多个偏好变化，触发重组
        // 偏好「值」就地缓存：组合期只读 State，不再反复走 AppPrefs/SharedPreferences；
        // 监听器写值即自然触发重组（无需额外的 version 计数）
        val swipeEnabled = remember { mutableStateOf(swipeEnabledPref.getValue()) }
        val showDivider = remember { mutableStateOf(candidateDividerPref.getValue()) }
        val fillMode = remember { mutableStateOf(getFillMode()) }
        val maxSpanCount = remember { mutableStateOf(maxSpanCountPref.getValue()) }

        androidx.compose.runtime.DisposableEffect(Unit) {
            val swipeListener = object : org.fcitx.fcitx5.android.data.prefs.ManagedPreference.OnChangeListener<Boolean> {
                override fun onChange(key: String, value: Boolean) {
                    swipeEnabled.value = value
                }
            }
            val dividerListener = object : org.fcitx.fcitx5.android.data.prefs.ManagedPreference.OnChangeListener<Boolean> {
                override fun onChange(key: String, value: Boolean) {
                    showDivider.value = value
                }
            }
            val fillStyleListener = org.fcitx.fcitx5.android.data.prefs.ManagedPreference.OnChangeListener<HorizontalCandidateMode> { _, _ ->
                fillMode.value = getFillMode()
            }
            val spanCountListener = org.fcitx.fcitx5.android.data.prefs.ManagedPreference.OnChangeListener<Int> { _, _ ->
                maxSpanCount.value = maxSpanCountPref.getValue()
            }
            swipeEnabledPref.registerOnChangeListener(swipeListener)
            candidateDividerPref.registerOnChangeListener(dividerListener)
            keyboardPrefs.horizontalCandidateStyle.registerOnChangeListener(fillStyleListener)
            maxSpanCountPref.registerOnChangeListener(spanCountListener)
            onDispose {
                swipeEnabledPref.unregisterOnChangeListener(swipeListener)
                candidateDividerPref.unregisterOnChangeListener(dividerListener)
                keyboardPrefs.horizontalCandidateStyle.unregisterOnChangeListener(fillStyleListener)
                maxSpanCountPref.unregisterOnChangeListener(spanCountListener)
            }
        }

        ComposeCandidateBar(
            state = state,
            visuals = getVisuals(),
            callbacks = CandidateBarCallbacks(
                onCandidateSelect = { index ->
                    fcitx.launchOnReady { it.select(index) }
                },
                onCandidateLongClick = { index, candidate, windowOffset ->
                    // windowOffset 已是「窗口绝对坐标」（由 CandidateItem 用自身
                    // positionInWindow() + 长按点换算），可直接作为菜单锚点。
                    // 候选词所在的 ComposeView 与 InputView 同处一棵 View 树
                    // （window == decorView），故与 showCandidateActionMenu 的坐标约定一致。
                    inputView.showCandidateActionMenu(
                        index,
                        candidate.text,
                        Rect(
                            windowOffset.x.toInt(),
                            windowOffset.y.toInt(),
                            windowOffset.x.toInt(),
                            windowOffset.y.toInt(),
                        )
                    )
                },
                onExpandClick = {
                    if (_isExpandedWindowShown.value) {
                        windowManager.attachWindow(KeyboardWindow)
                        _isExpandedWindowShown.value = false
                    } else {
                        windowManager.attachWindow(ComposeExpandedCandidateWindow())
                        _isExpandedWindowShown.value = true
                    }
                },
                onLoadMore = {
                    loadMoreIfNeeded()
                },
                onScrollOffsetChanged = { offset ->
                    onScrollOffsetChanged(offset)
                },
            ),
            scrollResetToken = scrollResetToken,
            fillMode = fillMode.value,
            maxSpanCount = maxSpanCount.value,
            userScrollEnabled = swipeEnabled.value,
            isExpandMode = isExpandedWindowShown,
            barHeight = LocalToolbarHeight.current,
            showDivider = showDivider.value,
            modifier = modifier,
        )
    }

    companion object {
        private const val LOAD_MORE_BATCH_MIN = 16
    }
}
