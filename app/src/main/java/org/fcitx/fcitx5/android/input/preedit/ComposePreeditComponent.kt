/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.preedit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import kotlinx.coroutines.flow.MutableStateFlow
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.dependency.context
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * Compose 预编辑栏组件
 * 替代 PreeditComponent，使用 Compose 实现
 */
class ComposePreeditComponent :
    UniqueComponent<ComposePreeditComponent>(),
    Dependent,
    ManagedHandler by managedHandler(),
    InputBroadcastReceiver {

    private val context by manager.context()

    private val _state = MutableStateFlow(PreeditState())

    override fun onInputPanelUpdate(data: FcitxEvent.InputPanelEvent.Data) {
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

    @Composable
    private fun getVisuals(): PreeditVisuals {
        return PreeditVisuals(
            textColor = MiuixTheme.colorScheme.onSurface,
            highlightColor = MiuixTheme.colorScheme.primary,
            backgroundColor = MiuixTheme.colorScheme.background,
        )
    }

    /**
     * 预编辑栏 View 宿主。
     * 预编辑栏必须悬浮在键盘体（keyboardView）之外，否则其可变高度会撑高键盘体，
     * 导致 IME 上报的 insets 随打字变化、应用页面反复伸缩。
     */
    val view by lazy {
        ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MiuixTheme(controller = remember { ThemeController(ColorSchemeMode.System) }) {
                    PreeditContent()
                }
            }
        }
    }

    /**
     * 预编辑栏 Composable 内容，由 [view] 宿主调用。
     */
    @Composable
    fun PreeditContent(modifier: Modifier = Modifier) {
        val state by _state.collectAsState()
        androidx.compose.foundation.layout.Box(
            modifier = modifier,
            contentAlignment = androidx.compose.ui.Alignment.TopStart,
        ) {
            ComposePreedit(
                state = state,
                visuals = getVisuals(),
            )
        }
    }
}
