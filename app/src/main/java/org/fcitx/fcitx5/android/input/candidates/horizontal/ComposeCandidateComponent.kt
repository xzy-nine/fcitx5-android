/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.horizontal

import android.content.Context
import android.content.res.Configuration
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.BooleanKey.ExpandedCandidatesEmpty
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.TransitionEvent.ExpandedCandidatesUpdated
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.candidates.expanded.ExpandedCandidateStyle
import org.fcitx.fcitx5.android.input.candidates.expanded.window.FlexboxExpandedCandidateWindow
import org.fcitx.fcitx5.android.input.candidates.expanded.window.GridExpandedCandidateWindow
import org.fcitx.fcitx5.android.input.dependency.UniqueViewComponent
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputView
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * Compose 候选栏组件
 * 替代 HorizontalCandidateComponent，使用 Compose LazyRow 实现
 *
 * 旧 View 实现对比：
 * - HorizontalCandidateComponent: 使用 RecyclerView + HorizontalCandidateViewAdapter
 * - ComposeCandidateComponent: 使用 Compose LazyRow + CandidateBarState 状态管理
 *
 * 主要变化：
 * 1. 候选列表：RecyclerView.Adapter → Compose LazyRow + itemsIndexed
 * 2. 状态管理：Adapter.notifyDataSetChanged() → MutableStateFlow<CandidateBarState>
 * 3. 滚动监听：RecyclerView.OnScrollListener → LazyListState.snapshotFlow
 * 4. 懒加载：Adapter.getItemCount() → snapshotFlow 检测滚动到底部
 */
class ComposeCandidateComponent :
    UniqueViewComponent<ComposeCandidateComponent, View>(), InputBroadcastReceiver {

    private val context by manager.context()
    private val fcitx by manager.fcitx()
    private val theme by manager.theme()
    private val inputView by manager.inputView()
    private val bar: KawaiiBarComponent by manager.must()
    private val service by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()

    private val fillStyle by AppPrefs.getInstance().keyboard.horizontalCandidateStyle
    private val swipeEnabledPref = AppPrefs.getInstance().keyboard.horizontalCandidateSwipe
    private val expandedCandidateStyle by AppPrefs.getInstance().keyboard.expandedCandidateStyle
    private val maxSpanCountPref by lazy {
        AppPrefs.getInstance().keyboard.run {
            if (context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT)
                expandedCandidateGridSpanCount
            else
                expandedCandidateGridSpanCountLandscape
        }
    }

    // 候选栏状态
    private val _state = MutableStateFlow<CandidateBarState>(CandidateBarState.Idle)
    private val _expandedCandidateOffset = MutableSharedFlow<Int>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    // 展开窗口是否已显示
    private val _isExpandedWindowShown = MutableStateFlow(false)

    val expandedCandidateOffset = _expandedCandidateOffset.asSharedFlow()
    val isExpandedWindowShown = _isExpandedWindowShown.asStateFlow()

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
    private var candidateGeneration = 0
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

        val offset = currentState.offset
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
        val currentState = _state.value
        if (currentState !is CandidateBarState.Active) return
        if (firstVisibleIndex == currentState.offset) return
        _state.update { state ->
            when (state) {
                is CandidateBarState.Active -> state.copy(offset = firstVisibleIndex)
                else -> state
            }
        }
        refreshExpanded()
    }

    private fun loadMoreIfNeeded() {
        if (loadingMore || noMoreData) return
        val currentState = _state.value
        if (currentState !is CandidateBarState.Active) return
        if (currentState.total >= 0 && currentState.candidates.size >= currentState.total) return
        // 简化逻辑：总是尝试加载更多
        loadMore()
    }

    private fun loadMore() {
        if (loadingMore || noMoreData) return
        val currentState = _state.value
        if (currentState !is CandidateBarState.Active) return
        if (currentState.total >= 0 && currentState.candidates.size >= currentState.total) return
        
        loadingMore = true
        val generation = candidateGeneration
        val currentOffset = currentState.candidates.size
        
        fcitx.launchOnReady {
            val more = it.getCandidates(currentOffset, loadMoreBatch)
            if (generation == candidateGeneration) {
                if (more.isNotEmpty()) {
                    _state.update { state ->
                        when (state) {
                            is CandidateBarState.Active -> {
                                state.copy(
                                    candidates = state.candidates + more,
                                )
                            }
                            else -> state
                        }
                    }
                } else {
                    noMoreData = true
                }
                loadingMore = false
            }
        }
    }

    private fun applyCandidates(
        data: FcitxEvent.CandidateListEvent.Data,
        prevData: FcitxEvent.CandidateListEvent.Data = FcitxEvent.CandidateListEvent.Data()
    ) {
        val candidates = data.candidates
        val total = data.total
        
        _state.value = CandidateBarState.from(candidates, total)
        
        loadingMore = false
        noMoreData = false
        candidateGeneration++
        lastExpandedOffset = -1
        
        // 在滑动模式下，如果新数据是旧数据的前缀，保持滚动位置
        val keepScroll = prevData.candidates.isNotEmpty() &&
            candidates.size >= prevData.candidates.size &&
            candidates.take(prevData.candidates.size).toTypedArray().contentEquals(prevData.candidates)
        
        if (!keepScroll) {
            // 重置滚动位置
            _state.update { state ->
                when (state) {
                    is CandidateBarState.Active -> state.copy(offset = 0)
                    else -> state
                }
            }
        }
        
        // 始终通知展开窗口更新状态（无论候选是否为空）
        refreshExpanded()
    }

    override fun onCandidateUpdate(data: FcitxEvent.CandidateListEvent.Data) {
        if (data == lastCandidateData) {
            return
        }
        val prevData = lastCandidateData
        lastCandidateData = data
        applyCandidates(data, prevData)
    }

    /**
     * 获取当前候选栏的视觉配置
     */
    private fun getVisuals(): CandidateBarVisuals {
        return CandidateBarVisuals(
            backgroundColor = androidx.compose.ui.graphics.Color(theme.barColor),
            textColor = androidx.compose.ui.graphics.Color(theme.candidateTextColor),
            commentColor = androidx.compose.ui.graphics.Color(theme.candidateCommentColor),
            highlightColor = androidx.compose.ui.graphics.Color(theme.accentKeyBackgroundColor),
            dividerColor = androidx.compose.ui.graphics.Color(theme.dividerColor),
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

    override val view by lazy {
        ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val themeController = remember { ThemeController(ColorSchemeMode.System) }
                MiuixTheme(controller = themeController) {
                    val state by _state.collectAsState()
                    val isExpandedWindowShown by _isExpandedWindowShown.collectAsState()
                    // swipeEnabled 状态，监听偏好变化
                    val swipeEnabled = remember { mutableStateOf(swipeEnabledPref.getValue()) }
                    
                    // 监听 swipe 偏好变化
                    androidx.compose.runtime.DisposableEffect(Unit) {
                        val listener = object : org.fcitx.fcitx5.android.data.prefs.ManagedPreference.OnChangeListener<Boolean> {
                            override fun onChange(key: String, value: Boolean) {
                                swipeEnabled.value = value
                            }
                        }
                        swipeEnabledPref.registerOnChangeListener(listener)
                        onDispose {
                            swipeEnabledPref.unregisterOnChangeListener(listener)
                        }
                    }
                    
                    ComposeCandidateBar(
                        state = state,
                        visuals = getVisuals(),
                        callbacks = CandidateBarCallbacks(
                            onCandidateSelect = { index ->
                                fcitx.launchOnReady { it.select(index) }
                            },
                            onCandidateLongClick = { index, candidate ->
                                inputView.showCandidateActionMenu(
                                    index,
                                    candidate.text,
                                    this@apply
                                )
                            },
                            onExpandClick = {
                                if (_isExpandedWindowShown.value) {
                                    // 已展开，收起回到键盘
                                    windowManager.attachWindow(KeyboardWindow)
                                    _isExpandedWindowShown.value = false
                                } else {
                                    // 未展开，展开候选窗口
                                    windowManager.attachWindow(
                                        when (expandedCandidateStyle) {
                                            ExpandedCandidateStyle.Grid -> GridExpandedCandidateWindow()
                                            ExpandedCandidateStyle.Flexbox -> FlexboxExpandedCandidateWindow()
                                        }
                                    )
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
                        fillMode = getFillMode(),
                        maxSpanCount = maxSpanCountPref.getValue(),
                        userScrollEnabled = swipeEnabled.value,
                        isExpandMode = isExpandedWindowShown,
                    )
                }
            }
        }
    }

    companion object {
        private const val LOAD_MORE_BATCH_MIN = 16
    }
}
