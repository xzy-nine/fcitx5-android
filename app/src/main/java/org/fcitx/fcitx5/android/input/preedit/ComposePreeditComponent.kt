/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.preedit

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Compose 预编辑栏组件
 * 替代 PreeditComponent，使用 Compose 实现
 */
class ComposePreeditComponent :
    UniqueComponent<ComposePreeditComponent>(),
    Dependent,
    ManagedHandler by managedHandler(),
    InputBroadcastReceiver {

    private val _state = MutableStateFlow(PreeditState())

    private val _heightPx = MutableStateFlow(0)

    /**
     * 预编辑栏当前高度（px），由 [PreeditContent] 的 `onSizeChanged` 上报（空态为 0、贴合内容）。
     *
     * 供 `onComputeInsets` 补偿与键盘背景裁剪使用：`contentTopInsets = keyboardView 顶部 +
     * 预编辑高度`，而 keyboardView 顶部本身已含预编辑高度，两者抵消 —— 因此预编辑栏高度
     * **可贴合内容变化而不影响 insets**，无需固定占位（固定占位会造成空隙）。
     */
    val heightPx: StateFlow<Int> = _heightPx.asStateFlow()

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
     * 预编辑栏 Composable 内容，由父级（合并后的单一 Composition）在 MiuixTheme 内直接调用。
     *
     * 高度**贴合内容**（空态 0 / 单行 / 双行），不固定占位，避免与工具栏之间出现空隙；
     * insets 恒定由 `onComputeInsets` 补偿实际高度保证（见 [heightPx]）。
     */
    @Composable
    fun PreeditContent(modifier: Modifier = Modifier) {
        val state by _state.collectAsState()
        Box(
            modifier = modifier.onSizeChanged { _heightPx.value = it.height },
            contentAlignment = Alignment.TopStart,
        ) {
            ComposePreedit(
                state = state,
                visuals = getVisuals(),
            )
        }
    }
}
