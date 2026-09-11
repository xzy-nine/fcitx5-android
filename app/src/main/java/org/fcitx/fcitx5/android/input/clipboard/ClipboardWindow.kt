/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.clipboard

import android.content.Intent
import android.view.View
import androidx.annotation.Keep
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 剪贴板主页窗口（Compose 化）。
 *
 * 复用 [ClipboardStateMachine]（EnableListening / AddMore / Normal 三态）与
 * [ClipboardTextAnalyzer]（分词实体提取）。数据以 `MutableStateFlow` 驱动，
 * 列表直接订阅 `ClipboardManager.allEntries()`。
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

    private val _entries = MutableStateFlow<List<ClipboardEntry>>(emptyList())
    private val _uiState = MutableStateFlow<ClipboardStateMachine.State>(Normal)
    private val _showDeleteAllDialog = MutableStateFlow(false)
    private val _deleteAllLabel = MutableStateFlow("")

    // 删除全部确认：skipPinned 由点击时 haveUnpinned() 决定
    private var deleteAllSkipPinned = true
    private var submitJob: Job? = null

    private lateinit var stateMachine: EventStateMachine<
        ClipboardStateMachine.State,
        ClipboardStateMachine.TransitionEvent,
        ClipboardStateMachine.BooleanKey
        >

    override fun onCreateView(): View = createComposeWindowView(context) { Content() }

    @Composable
    override fun Content() {
        val uiState by _uiState.collectAsState()
        val entries by _entries.collectAsState()
        val showDeleteAllDialog by _showDeleteAllDialog.collectAsState()
        val deleteAllLabel by _deleteAllLabel.collectAsState()
        ClipboardListContent(
            state = uiState,
            entries = entries,
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
                onDelete = { id -> service.lifecycleScope.launch { ClipboardManager.delete(id) } },
                onEnableListening = { clipboardEnabledPref.setValue(true) },
            ),
            showDeleteAllDialog = showDeleteAllDialog,
            deleteAllLabel = deleteAllLabel,
            onConfirmDeleteAll = {
                service.lifecycleScope.launch { ClipboardManager.deleteAll(deleteAllSkipPinned) }
            },
            onCancelDeleteAll = { _showDeleteAllDialog.value = false },
        )
    }

    private fun onPaste(text: String) {
        service.commitText(text)
        if (clipboardReturnAfterPaste) windowManager.attachWindow(KeyboardWindow)
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
        val isEmpty = ClipboardManager.itemCount == 0
        val isListening = clipboardEnabledPref.getValue()
        val initialState = when {
            !isListening -> EnableListening
            isEmpty -> AddMore
            else -> Normal
        }
        stateMachine = ClipboardStateMachine.new(initialState, isEmpty, isListening) {
            _uiState.value = it
        }
        _uiState.value = initialState

        submitJob = service.lifecycleScope.launch {
            ClipboardManager.observeAllEntries().collect { list ->
                _entries.value = list
                stateMachine.push(ClipboardDbUpdated, ClipboardDbEmpty to list.isEmpty())
            }
        }
        clipboardEnabledPref.registerOnChangeListener(clipboardEnabledListener)
    }

    override fun onDetached() {
        clipboardEnabledPref.unregisterOnChangeListener(clipboardEnabledListener)
        submitJob?.cancel()
        _showDeleteAllDialog.value = false
    }

    override val title: String by lazy {
        context.getString(R.string.clipboard)
    }
}