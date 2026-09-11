/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.content.ClipData
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.wm.ComposeWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.input.wm.createComposeWindowView
import org.fcitx.fcitx5.android.utils.clipboardManager
import org.mechdancer.dependency.manager.must

/**
 * IME-embedded editor for a clipboard entry (or the most recent entry), Compose 化。
 *
 * 复用 [ClipboardTextAnalyzer] 的分词重组；分词模式展示可点选的词块流，
 * 文本模式提供基本文本编辑区。复制/上屏/退出等窗口逻辑保留，渲染层为 Compose。
 */
class ClipboardEditWindow(
    private val entryId: Int = -1,
    private val useLastEntry: Boolean = false,
    private val returnToClipboard: Boolean = false
) : InputWindow.ExtendedInputWindow<ClipboardEditWindow>(), ComposeWindow {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()

    private val _segments = MutableStateFlow<List<String>>(emptyList())
    private val _insertSpace = MutableStateFlow(
        AppPrefs.getInstance().clipboard.clipboardEditInsertSpace.getValue()
    )
    private val segments: StateFlow<List<String>> = _segments.asStateFlow()
    private val insertSpace: StateFlow<Boolean> = _insertSpace.asStateFlow()

    override fun onCreateView(): View = createComposeWindowView(context) { Content() }

    @Composable
    override fun Content() {
        val segs by segments.collectAsState()
        val insert by insertSpace.collectAsState()
        ClipboardEditContent(
            segments = segs,
            insertSpace = insert,
            callbacks = ClipboardEditCallbacks(
                onCommit = ::commitToInput,
                onCopy = ::copyOnly,
                onExit = ::exitToPrevWindow,
                onInsertSpaceChange = { _insertSpace.value = it },
            ),
        )
    }

    override fun onAttached() {
        service.lifecycleScope.launch {
            val entry: ClipboardEntry? = if (useLastEntry) {
                ClipboardManager.lastEntry
            } else {
                withContext(Dispatchers.IO) { ClipboardManager.get(entryId) }
            }
            _segments.value = rememberSegments(entry?.text.orEmpty())
        }
    }

    override fun onDetached() {
        _segments.value = emptyList()
    }

    private fun rememberSegments(text: String): List<String> {
        val seg = ClipboardTextAnalyzer.segment(text)
        return if (seg.isEmpty()) listOf("") else seg
    }

    /**
     * 将文本通过输入接口逐字上屏（commitText），不写入剪贴板数据库。
     */
    private fun commitToInput(text: String) {
        service.commitText(text)
        exitToPrevWindow()
    }

    /**
     * 复制：仅写入系统剪贴板（剪贴板数据库会自行监听并新增条目），不上屏。
     */
    private fun copyOnly(text: String) {
        runCatching { context.clipboardManager.setPrimaryClip(ClipData.newPlainText("", text)) }
        exitToPrevWindow()
    }

    /**
     * 从剪切板条目页进入时回到剪切板窗口，否则回到键盘。
     */
    private fun exitToPrevWindow() {
        if (returnToClipboard) windowManager.attachWindow(ClipboardWindow())
        else windowManager.attachWindow(KeyboardWindow)
    }

    override val title: String by lazy { context.getString(R.string.edit_clipboard) }
}