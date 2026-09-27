/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editorinfo

import android.content.ClipData
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.MutableStateFlow
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.wm.ComposeWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.createComposeWindowView
import org.fcitx.fcitx5.android.utils.clipboardManager
import org.fcitx.fcitx5.android.utils.toast
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams

class EditorInfoWindow : InputWindow.ExtendedInputWindow<EditorInfoWindow>(), ComposeWindow {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val theme by manager.theme()

    private val _propertyMap = MutableStateFlow<Map<String, String>>(emptyMap())

    private fun buildMarkdownString() = buildString {
        append("# EditorInfo Inspector\n\n")
        append("|Property|Value|\n|:-|:-|\n")
        _propertyMap.value.forEach { (k, v) ->
            append("|$k|")
            append(v.replace("\n", "<br>"))
            append("|\n")
        }
    }

    @Composable
    override fun Content() {
        ComposeEditorInfoContent(properties = _propertyMap.collectAsState().value)
    }

    override val title by lazy {
        context.getString(R.string.editor_info_inspector)
    }

    private val copyButton by lazy {
        ToolButton(context, R.drawable.ic_baseline_content_copy_24, theme).apply {
            setOnClickListener {
                val clipData = ClipData.newPlainText("", buildMarkdownString())
                context.clipboardManager.setPrimaryClip(clipData)
                context.toast(R.string.done)
            }
        }
    }

    private val barExtension by lazy {
        context.horizontalLayout {
            add(copyButton, lParams(dp(40), dp(40)))
        }
    }

    override fun onCreateView() = createComposeWindowView(context) { Content() }

    override fun onCreateBarExtension() = barExtension

    override fun onAttached() {
        _propertyMap.value = EditorInfoParser.parse(service.currentInputEditorInfo)
    }

    override fun onDetached() {}
}
