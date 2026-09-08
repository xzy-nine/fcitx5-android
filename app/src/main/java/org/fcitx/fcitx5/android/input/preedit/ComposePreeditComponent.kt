/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.preedit

import android.view.View
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.dependency.UniqueViewComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.theme
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * Compose 预编辑栏组件
 * 替代 PreeditComponent，使用 Compose 实现
 */
class ComposePreeditComponent :
    UniqueViewComponent<ComposePreeditComponent, View>(), InputBroadcastReceiver {

    private val context by manager.context()
    private val theme by manager.theme()

    private val _state = MutableStateFlow(PreeditState())

    override fun onInputPanelUpdate(data: FcitxEvent.InputPanelEvent.Data) {
        val activeBkg = theme.genericActiveBackgroundColor
        val preedit = data.preedit
        val auxUp = data.auxUp
        val auxDown = data.auxDown

        // 构建上行文本：auxUp + preedit
        val upString: String
        val upCursor: Int
        if (auxUp.isEmpty()) {
            upString = preedit.toString()
            upCursor = preedit.cursor
        } else {
            upString = auxUp.toString() + preedit.toString()
            upCursor = preedit.cursor.let {
                if (it < 0) it else auxUp.length + it
            }
        }

        val downString = auxDown.toString()
        val hasUp = upString.isNotEmpty()
        val hasDown = downString.isNotEmpty()
        val visible = hasUp || hasDown

        _state.value = PreeditState(
            upText = upString,
            upCursor = if (visible) upCursor else -1,
            downText = downString,
            visible = visible,
        )
    }

    private fun getVisuals(): PreeditVisuals {
        val keyBorder = ThemeManager.prefs.keyBorder.getValue()
        val bkgColor = if (!keyBorder && theme is Theme.Builtin) {
            androidx.compose.ui.graphics.Color(theme.barColor)
        } else {
            androidx.compose.ui.graphics.Color(theme.backgroundColor)
        }
        return PreeditVisuals(
            textColor = androidx.compose.ui.graphics.Color(theme.keyTextColor),
            highlightColor = androidx.compose.ui.graphics.Color(theme.genericActiveBackgroundColor),
            backgroundColor = bkgColor,
        )
    }

    override val view by lazy {
        ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val themeController = remember { ThemeController(ColorSchemeMode.System) }
                MiuixTheme(controller = themeController) {
                    val state by _state.collectAsState()
                    ComposePreedit(
                        state = state,
                        visuals = getVisuals(),
                    )
                }
            }
        }
    }
}
