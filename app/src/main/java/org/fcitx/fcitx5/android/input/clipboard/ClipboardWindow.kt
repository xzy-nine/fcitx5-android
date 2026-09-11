/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.clipboard

import android.content.Intent
import android.view.View
import androidx.annotation.Keep
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.BooleanKey.ClipboardDbEmpty
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.BooleanKey.ClipboardListeningEnabled
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.State.AddMore
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.State.EnableListening
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.State.Normal
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.TransitionEvent.ClipboardDbUpdated
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.TransitionEvent.ClipboardListeningUpdated
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.wm.ComposeWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.input.wm.createComposeWindowView
import org.fcitx.fcitx5.android.utils.EventStateMachine
import org.mechdancer.dependency.manager.must
import kotlin.time.Duration.Companion.milliseconds

/**
 * 剪贴板主页窗口（Compose 化）。
 *
 * 复用 [ClipboardStateMachine]（EnableListening / AddMore / Normal 三态）与
 * [ClipboardTextAnalyzer]（分词实体提取）。列表以 Paging 3 分页消费
 * [ClipboardManager.entriesPager]（内部复用 [ClipboardManager] 的 Room `PagingSource`），
 * 其余状态（UI 态、待撤销 id）以 `MutableStateFlow` 驱动。
 *
 * 删除采用「软删除 + 限时撤销 + 到期物理清理」：软删除后立即调用 [invalidatePaging]
 * 让分页重排，撤销或超时清理后再失效一次。
 *
 * 交互：点击条目上屏、长按出操作菜单（置顶/取消置顶/编辑/分享/删除）、
 * 条目内实体气泡点击上屏片段、工具栏“删除全部”按钮经 Compose 确认层二次确认。
 */
class ClipboardWindow : InputWindow.ExtendedInputWindow<ClipboardWindow>(), ComposeWindow {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()
    private val theme by manager.theme()

    @Keep
    private val clipboardEnabledListener = ManagedPreference.OnChangeListener<Boolean> { _, it ->
        stateMachine.push(
            ClipboardListeningUpdated, ClipboardListeningEnabled to it
        )
    }

    private val prefs = AppPrefs.getInstance().clipboard

    private val clipboardEnabledPref = prefs.clipboardListening
    private val clipboardReturnAfterPaste by prefs.clipboardReturnAfterPaste
    private val clipboardMaskSensitive by prefs.clipboardMaskSensitive

    private val _uiState = MutableStateFlow<ClipboardStateMachine.State>(Normal)
    private val _showDeleteAllDialog = MutableStateFlow(false)
    private val _deleteAllLabel = MutableStateFlow("")
    private val _pendingDeleteIds = MutableStateFlow<List<Int>>(emptyList())

    /** 分页数据流：单例复用 [ClipboardManager.entriesPager]，不可在本窗口重复构造。 */
    private val pagedEntries: Flow<PagingData<ClipboardEntry>> = ClipboardManager.entriesPager

    // 删除全部确认：skipPinned 由点击时 haveUnpinned() 决定
    private var deleteAllSkipPinned = true
    private var clearUndoJob: Job? = null

    private lateinit var stateMachine: EventStateMachine<
        ClipboardStateMachine.State,
        ClipboardStateMachine.TransitionEvent,
        ClipboardStateMachine.BooleanKey
        >

    override fun onCreateView(): View = createComposeWindowView(context) { Content() }

    @Composable
    override fun Content() {
        val uiState by _uiState.collectAsState()
        val showDeleteAllDialog by _showDeleteAllDialog.collectAsState()
        val deleteAllLabel by _deleteAllLabel.collectAsState()
        val pendingDeleteIds by _pendingDeleteIds.collectAsState()

        val pagingItems = pagedEntries.collectAsLazyPagingItems()

        // 列表是否为空由分页加载状态推导（替代原先直接读 Flow 列表）：
        // 首屏加载完成且无数据才算空，避免加载中误显示"复制内容后会自动出现"。
        val loadState = pagingItems.loadState
        val isEmpty = loadState.refresh is LoadState.NotLoading &&
                loadState.append.endOfPaginationReached &&
                pagingItems.itemCount < 1
        LaunchedEffect(isEmpty) {
            if (::stateMachine.isInitialized) {
                stateMachine.push(ClipboardDbUpdated, ClipboardDbEmpty to isEmpty)
            }
        }

        ClipboardListContent(
            state = uiState,
            entries = pagingItems,
            maskSensitive = clipboardMaskSensitive,
            callbacks = ClipboardCallbacks(
                onPaste = { entry -> onPaste(entry.text) },
                onPasteText = { text -> onPaste(text) },
                onPin = { id -> service.lifecycleScope.launch { ClipboardManager.pin(id) } },
                onUnpin = { id -> service.lifecycleScope.launch { ClipboardManager.unpin(id) } },
                onEdit = { id ->
                    windowManager.attachWindow(ClipboardEditWindow(id, returnToClipboard = true))
                },
                onShare = { entry ->
                    val target = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, entry.text)
                    }
                    val chooser = Intent.createChooser(target, null).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    service.startActivity(chooser)
                },
                onDelete = ::onDelete,
                onEnableListening = { clipboardEnabledPref.setValue(true) },
            ),
            showDeleteAllDialog = showDeleteAllDialog,
            deleteAllLabel = deleteAllLabel,
            onConfirmDeleteAll = {
                service.lifecycleScope.launch {
                    val ids = ClipboardManager.deleteAll(deleteAllSkipPinned)
                    onDeleted(ids.toList())
                }
                _showDeleteAllDialog.value = false
            },
            onCancelDeleteAll = { _showDeleteAllDialog.value = false },
            pendingDeleteIds = pendingDeleteIds,
            onUndoDelete = ::undoDelete,
            onRetry = { pagingItems.retry() },
        )
    }

    private fun onPaste(text: String) {
        service.commitText(text)
        if (clipboardReturnAfterPaste) windowManager.attachWindow(KeyboardWindow)
    }

    /**
     * 触发分页失效重查。
     *
     * Room 的 `PagingSource` 虽自带表变更检测，但软删除（`deleted=1`）与撤销
     * 会在同一帧内连续改动，显式失效可让列表立即重排，避免已删除条目在分页
     * 边界处短暂残留。
     */
    private fun invalidatePaging() {
        ClipboardManager.invalidatePagingSource()
    }

    /**
     * 删除条目（软删除）并开启撤销窗口。
     *
     * [ClipboardManager.delete] 仅置 `deleted=1`，条目本身仍在库中，因此撤销是可靠的。
     * 撤销窗口结束后由 [onDeleted] 调度 [ClipboardManager.realDelete] 做物理清理。
     */
    private fun onDelete(id: Int) {
        service.lifecycleScope.launch {
            ClipboardManager.delete(id)
            onDeleted(listOf(id))
        }
    }

    private fun onDeleted(ids: Collection<Int>) {
        if (ids.isEmpty()) return
        _pendingDeleteIds.value = _pendingDeleteIds.value + ids
        // 立即重排分页，避免软删除条目在分页边界处短暂残留
        invalidatePaging()
        restartClearUndoJob()
    }

    private fun undoDelete() {
        val ids = _pendingDeleteIds.value
        _pendingDeleteIds.value = emptyList()
        clearUndoJob?.cancel()
        if (ids.isEmpty()) return
        service.lifecycleScope.launch {
            ClipboardManager.undoDelete(*ids.toIntArray())
            invalidatePaging()
        }
    }

    /**
     * 撤销窗口到期后清理软删除记录。
     *
     * 每次新的删除都会重置计时，与旧实现「连续删除时后一个 Snackbar 取代前一个、前一批 id 累积」
     * 的语义一致：窗口内撤销会一并恢复全部待撤销条目，窗口结束则全部物理删除。
     */
    private fun restartClearUndoJob() {
        clearUndoJob?.cancel()
        clearUndoJob = service.lifecycleScope.launch {
            delay(UNDO_WINDOW_MS.milliseconds)
            if (_pendingDeleteIds.value.isNotEmpty()) {
                _pendingDeleteIds.value = emptyList()
                ClipboardManager.realDelete()
                invalidatePaging()
            }
        }
    }

    private val deleteAllButton by lazy {
        ToolButton(context, R.drawable.ic_baseline_delete_sweep_24, theme).apply {
            contentDescription = context.getString(R.string.delete_all)
            setOnClickListener {
                service.lifecycleScope.launch {
                    val skipPinned = ClipboardManager.haveUnpinned()
                    deleteAllSkipPinned = skipPinned
                    _deleteAllLabel.value = context.getString(
                        if (skipPinned) R.string.delete_all_except_pinned
                        else R.string.delete_all_pinned_items
                    )
                    _showDeleteAllDialog.value = true
                }
            }
        }
    }

    override fun onCreateBarExtension(): View = deleteAllButton

    override fun onAttached() {
        val isListening = clipboardEnabledPref.getValue()
        // 空态初始值只能同步取一次（首帧尚无分页结果），随后由 Content() 内的
        // LaunchedEffect 依据分页加载状态纠正为真实值。
        val isEmpty = ClipboardManager.itemCount == 0
        val initialState = when {
            !isListening -> EnableListening
            isEmpty -> AddMore
            else -> Normal
        }
        stateMachine = ClipboardStateMachine.new(initialState, isEmpty, isListening) {
            _uiState.value = it
        }
        _uiState.value = initialState
        // 每次挂载都从第一页重查，避免复用 cachedIn 缓存中的过期页
        invalidatePaging()
        clipboardEnabledPref.registerOnChangeListener(clipboardEnabledListener)
    }

    override fun onDetached() {
        clipboardEnabledPref.unregisterOnChangeListener(clipboardEnabledListener)
        _showDeleteAllDialog.value = false
        // 窗口销毁即撤销窗口结束：已软删除的条目做物理清理
        clearUndoJob?.cancel()
        if (_pendingDeleteIds.value.isNotEmpty()) {
            _pendingDeleteIds.value = emptyList()
            service.lifecycleScope.launch { ClipboardManager.realDelete() }
        }
    }

    override val title: String by lazy {
        context.getString(R.string.clipboard)
    }

    private companion object {
        /** 撤销窗口时长，与旧实现 Snackbar.LENGTH_LONG 的量级一致 */
        const val UNDO_WINDOW_MS = 4000L
    }
}
