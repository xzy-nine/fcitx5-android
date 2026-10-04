/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.status

import android.os.Build
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.Action
import org.fcitx.fcitx5.android.core.SubtypeManager
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.bar.ComposeKawaiiBarComponent
import org.fcitx.fcitx5.android.input.bar.ToolbarEditComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.editorinfo.EditorInfoWindow
import org.fcitx.fcitx5.android.input.keyboard.preferenceState
import org.fcitx.fcitx5.android.input.wm.ComposeWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.input.wm.createComposeWindowView
import org.fcitx.fcitx5.android.utils.AppUtil
import org.mechdancer.dependency.manager.must
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/**
 * 更多页（状态区）。
 *
 * 兼作工具栏布局的编辑页：上半是按钮池（未停靠的可移动按钮），下半是固定功能项
 * （撤销/重做显隐开关、重置布局、重载配置、输入法设置、编辑器信息、fcitx 引擎状态项）。
 *
 * 原「主题 / 输入法选项 / 虚拟键盘」三个跳转项已移除；原顶栏扩展区的设置入口与
 * 编辑器信息入口改为本页网格里的固定格子，因此本页不再提供 `onCreateBarExtension()`。
 */
class StatusAreaWindow : InputWindow.ExtendedInputWindow<StatusAreaWindow>(),
    InputBroadcastReceiver, ComposeWindow {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val fcitx: FcitxConnection by manager.fcitx()
    private val windowManager: InputWindowManager by manager.must()
    private val bar: org.fcitx.fcitx5.android.input.bar.ComposeKawaiiBarComponent by manager.must()
    private val toolbarEdit: ToolbarEditComponent by manager.must()

    private val prefs = AppPrefs.getInstance()

    private val editorInfoInspector = prefs.internal.editorInfoInspector

    /** fcitx 引擎状态项：由广播更新，只驱动固定段末尾那部分。 */
    private val _actions = MutableStateFlow<List<Action>>(emptyList())

    /** 打开「重置布局」确认弹层（IME 内只用 Overlay 系列，系统 Dialog 必崩）。 */
    private val _showResetConfirm = MutableStateFlow(false)

    private fun activateAction(action: Action) {
        fcitx.launchOnReady {
            it.activateAction(action.id)
        }
    }

    private fun onReloadConfig() {
        fcitx.launchOnReady { f ->
            f.reloadConfig()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                SubtypeManager.syncWith(f.enabledIme())
            }
            service.lifecycleScope.launch {
                Toast.makeText(service, R.string.done, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateView(): View = createComposeWindowView(context) { Content() }

    /**
     * 窗口根包一层 miuix [Scaffold]：`OverlayDialog` 本身不依赖系统 Dialog，但其宿主
     * `MiuixPopupHost` 必须由根 Scaffold 提供（Popup slot 最后 place，z 序最高）。
     * 背景透明以沿用键盘主题底图；IME 的 insets 由 InputView 自行处理，故清零。
     */
    @Composable
    override fun Content() {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0),
        ) {
            MorePageContent()
        }
    }

    @Composable
    private fun MorePageContent() {
        val layout by bar.toolbarLayout.collectAsState()
        val editing by toolbarEdit.editing.collectAsState()
        val showUndoRedo by toolbarEdit.showUndoRedo.collectAsState()
        val actions by _actions.collectAsState()
        val splitKeyboardEnabled = prefs.keyboard.splitKeyboard.preferenceState()
        val showResetConfirm by _showResetConfirm.collectAsState()

        val pool = MorePageModel.poolCells(layout.pool) { bar.isButtonUsable(it) }
        val fixed = MorePageModel.fixedCells(
            editing = editing,
            showEditorInfo = editorInfoInspector.getValue(),
            actions = actions,
            iconOf = { drawableFromActionIcon(it) },
        )

        MorePageGrid(
            poolCells = pool,
            fixedCells = fixed,
            splitKeyboardEnabled = splitKeyboardEnabled,
            editing = editing,
            showUndoRedo = showUndoRedo,
            onMoveableClick = { bar.onToolbarButtonClick(it) },
            onFcitxClick = ::activateAction,
            onFcitxMenuAction = ::activateAction,
            onToggleUndoRedo = { toolbarEdit.toggleUndoRedo() },
            onReloadConfig = ::onReloadConfig,
            onSettings = { AppUtil.launchMain(context) },
            onEditorInfo = { windowManager.attachWindow(EditorInfoWindow()) },
            onResetLayout = { _showResetConfirm.value = true },
        )

        if (showResetConfirm) {
            ResetLayoutConfirmDialog(
                onConfirm = {
                    toolbarEdit.resetDraft()
                    _showResetConfirm.value = false
                },
                onDismiss = { _showResetConfirm.value = false },
            )
        }
    }

    /** 引擎项的图标映射沿用 [StatusAreaEntry.fromAction] 的口径，避免两处图标不一致。 */
    private fun drawableFromActionIcon(action: Action): Int =
        StatusAreaEntry.fromAction(action).icon

    @Composable
    private fun ResetLayoutConfirmDialog(
        onConfirm: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        OverlayDialog(
            show = true,
            title = stringResource(R.string.more_page_reset_confirm_title),
            summary = stringResource(R.string.more_page_reset_confirm_msg),
            onDismissRequest = onDismiss,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.ok),
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }

    override fun onStatusAreaUpdate(actions: Array<Action>) {
        _actions.value = actions.toList()
    }

    override fun onAttached() {
        fcitx.launchOnReady {
            val data = it.statusArea()
            service.lifecycleScope.launch {
                onStatusAreaUpdate(data)
            }
        }
    }

    override fun onDetached() {
        // 子菜单由 Compose 弹层随 Composition 一并销毁，无需手动关闭
    }
}
