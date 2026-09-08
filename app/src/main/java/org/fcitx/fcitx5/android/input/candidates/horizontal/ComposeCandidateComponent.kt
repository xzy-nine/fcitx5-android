/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.horizontal

import android.content.Context
import android.content.res.Configuration
import android.view.View
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.BooleanKey.ExpandedCandidatesEmpty
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.TransitionEvent.ExpandedCandidatesUpdated
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.candidates.expanded.ExpandedCandidateStyle
import org.fcitx.fcitx5.android.input.candidates.expanded.window.BaseExpandedCandidateWindow
import org.fcitx.fcitx5.android.input.candidates.expanded.window.FlexboxExpandedCandidateWindow
import org.fcitx.fcitx5.android.input.candidates.expanded.window.GridExpandedCandidateWindow
import org.fcitx.fcitx5.android.input.dependency.UniqueViewComponent
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputView
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * Compose 候选栏组件
 * 替代 HorizontalCandidateComponent，使用 Compose LazyRow 实现
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

    private val keyboardPrefs = AppPrefs.getInstance().keyboard
    private val fillStyle by keyboardPrefs.horizontalCandidateStyle
    private val swipeEnabledPref = keyboardPrefs.horizontalCandidateSwipe
    private val candidateDividerPref = keyboardPrefs.candidateDivider
    private val expandedCandidateStyle by keyboardPrefs.expandedCandidateStyle
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
            _state.update { state ->
                when (state) {
                    is CandidateBarState.Active -> state.copy(offset = 0)
                    else -> state
                }
            }
        }

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

    override fun onWindowDetached(window: InputWindow) {
        if (window is BaseExpandedCandidateWindow<*>) {
            _isExpandedWindowShown.value = false
        }
    }

    /**
     * 获取当前候选栏的视觉配置
     */
    private fun getVisuals(): CandidateBarVisuals {
        return CandidateBarVisuals(
            backgroundColor = androidx.compose.ui.graphics.Color(theme.barColor),
            textColor = androidx.compose.ui.graphics.Color(theme.candidateTextColor),
            commentColor = androidx.compose.ui.graphics.Color(theme.candidateCommentColor),
            pressHighlightColor = androidx.compose.ui.graphics.Color(theme.keyPressHighlightColor),
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

                    // 监听多个偏好变化，触发重组
                    val swipeEnabled = remember { mutableStateOf(swipeEnabledPref.getValue()) }
                    val showDivider = remember { mutableStateOf(candidateDividerPref.getValue()) }
                    val fillStyleVersion = remember { mutableIntStateOf(0) }
                    val maxSpanCountVersion = remember { mutableIntStateOf(0) }

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
                            fillStyleVersion.intValue++
                        }
                        val spanCountListener = org.fcitx.fcitx5.android.data.prefs.ManagedPreference.OnChangeListener<Int> { _, _ ->
                            maxSpanCountVersion.intValue++
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

                    // 读取当前值（version 变化触发重组）
                    @Suppress("UNUSED_VARIABLE")
                    val _fillVersion = fillStyleVersion.intValue
                    @Suppress("UNUSED_VARIABLE")
                    val _spanVersion = maxSpanCountVersion.intValue

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
                                    windowManager.attachWindow(KeyboardWindow)
                                    _isExpandedWindowShown.value = false
                                } else {
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
                        barHeight = KawaiiBarComponent.HEIGHT.dp,
                        showDivider = showDivider.value,
                    )
                }
            }
        }
    }

    companion object {
        private const val LOAD_MORE_BATCH_MIN = 16
    }
}
