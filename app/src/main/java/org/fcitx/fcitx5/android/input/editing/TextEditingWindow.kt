/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import android.view.KeyEvent
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.clipboard.ClipboardWindow
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.wm.ComposeWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.input.wm.createComposeWindowView
import org.fcitx.fcitx5.android.utils.clipboardManager
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams

class TextEditingWindow : InputWindow.ExtendedInputWindow<TextEditingWindow>(),
    ComposeWindow, InputBroadcastReceiver {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()
    private val theme by manager.theme()

    private var hasSelection = false

    private val _userSelection = MutableStateFlow(false)
    private val userSelection: StateFlow<Boolean> = _userSelection.asStateFlow()

    private fun sendDirectionKey(keyEventCode: Int) {
        service.sendCombinationKeyEvents(keyEventCode, shift = hasSelection || _userSelection.value)
    }

    override fun onCreateView(): View = createComposeWindowView(context) { Content() }

    @Composable
    override fun Content() {
        val userSel by userSelection.collectAsState()
        TextEditingContent(
            hasSelection = hasSelection,
            selectActivated = hasSelection || userSel,
            callbacks = TextEditingCallbacks(
                onUp = { sendDirectionKey(KeyEvent.KEYCODE_DPAD_UP) },
                onDown = { sendDirectionKey(KeyEvent.KEYCODE_DPAD_DOWN) },
                onLeft = { sendDirectionKey(KeyEvent.KEYCODE_DPAD_LEFT) },
                onRight = { sendDirectionKey(KeyEvent.KEYCODE_DPAD_RIGHT) },
                onHome = { sendDirectionKey(KeyEvent.KEYCODE_MOVE_HOME) },
                onEnd = { sendDirectionKey(KeyEvent.KEYCODE_MOVE_END) },
                onSelect = {
                    if (hasSelection) {
                        _userSelection.value = false
                        service.cancelSelection()
                    } else {
                        _userSelection.value = !_userSelection.value
                    }
                },
                onSelectAll = {
                    _userSelection.value = true
                    service.currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
                },
                onCut = {
                    _userSelection.value = false
                    service.currentInputConnection?.performContextMenuAction(android.R.id.cut)
                },
                onCopy = {
                    _userSelection.value = false
                    service.currentInputConnection?.performContextMenuAction(android.R.id.copy)
                },
                onPaste = {
                    _userSelection.value = false
                    val text = service.clipboardManager.primaryClip
                        ?.getItemAt(0)?.text?.toString().orEmpty()
                    if (text.isNotEmpty()) service.commitText(text)
                },
                onBackspace = {
                    _userSelection.value = false
                    service.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                },
                onOpenClipboard = {
                    windowManager.attachWindow(ClipboardWindow())
                },
            ),
        )
    }

    override fun onAttached() {
        val range = service.currentInputSelection
        onSelectionUpdate(range.start, range.end)
    }

    override fun onDetached() {
        _userSelection.value = false
    }

    override fun onSelectionUpdate(start: Int, end: Int) {
        hasSelection = start != end
    }

    override fun onCreateBarExtension(): View = context.horizontalLayout {
        add(
            ToolButton(context, R.drawable.ic_clipboard, theme).apply {
                contentDescription = context.getString(R.string.clipboard)
                setOnClickListener {
                    windowManager.attachWindow(ClipboardWindow())
                }
            },
            lParams(context.dp(40), context.dp(40))
        )
    }

    override val title by lazy {
        context.getString(R.string.text_editing)
    }
}