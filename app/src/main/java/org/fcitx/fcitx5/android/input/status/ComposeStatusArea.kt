/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.status

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.Action
import org.fcitx.fcitx5.android.input.bar.ToolbarButton
import org.fcitx.fcitx5.android.input.bar.ToolbarButtonSpec
import org.fcitx.fcitx5.android.input.bar.ToolbarIcon
import org.fcitx.fcitx5.android.input.bar.inputFeedback
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/**
 * 更多页的 Compose 渲染层（纯 UI，无业务逻辑）。
 *
 * 上下两段各自独立滚动的 4 列网格：
 * - 上半 = 按钮池（未停靠的可移动按钮，普通模式下点击即执行）；
 * - 下半 = 固定功能项（撤销开关 / 重置 / 重载配置 / 设置 / 编辑器信息 / fcitx 引擎项）。
 *
 * 分成两个容器而不是一个混排网格：两段的滚动与拖动语义不同（池子参与拖动重排、
 * 固定段完全不参与），分开后边界清晰，也不会出现「拖动插入位落在固定段里」这种歧义。
 *
 * 颜色一律取 [MiuixTheme] 系统主题；fcitx 项带子菜单时以 Compose 弹层展开
 * （不用系统 PopupMenu：IME 浮窗内会抛 BadTokenException）。
 *
 * [editing] 为真时除撤销开关与重置外的格子全部置灰不可点 —— 编辑期间只应拖动，
 * 点到设置页/主题等会造成编辑会话被顶掉。
 */
@Composable
fun MorePageGrid(
    poolCells: List<MorePageCell.Movable>,
    fixedCells: List<MorePageCell>,
    splitKeyboardEnabled: Boolean,
    editing: Boolean,
    showUndoRedo: Boolean,
    onMoveableClick: (ToolbarButton) -> Unit,
    onFcitxClick: (Action) -> Unit,
    onFcitxMenuAction: (Action) -> Unit,
    onToggleUndoRedo: () -> Unit,
    onReloadConfig: () -> Unit,
    onSettings: () -> Unit,
    onEditorInfo: () -> Unit,
    onResetLayout: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        PoolSection(
            cells = poolCells,
            splitKeyboardEnabled = splitKeyboardEnabled,
            editing = editing,
            onMoveableClick = onMoveableClick,
            modifier = Modifier.weight(1f),
        )
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 12.dp),
            thickness = 1.dp,
        )
        FixedSection(
            cells = fixedCells,
            editing = editing,
            showUndoRedo = showUndoRedo,
            onFcitxClick = onFcitxClick,
            onFcitxMenuAction = onFcitxMenuAction,
            onToggleUndoRedo = onToggleUndoRedo,
            onReloadConfig = onReloadConfig,
            onSettings = onSettings,
            onEditorInfo = onEditorInfo,
            onResetLayout = onResetLayout,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PoolSection(
    cells: List<MorePageCell.Movable>,
    splitKeyboardEnabled: Boolean,
    editing: Boolean,
    onMoveableClick: (ToolbarButton) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = modifier.fillMaxWidth(),
    ) {
        items(cells.size) { index ->
            val cell = cells[index]
            MovableCell(
                cell = cell,
                splitKeyboardEnabled = splitKeyboardEnabled,
                editing = editing,
                onClick = { onMoveableClick(cell.button) },
            )
        }
    }
}

@Composable
private fun FixedSection(
    cells: List<MorePageCell>,
    editing: Boolean,
    showUndoRedo: Boolean,
    onFcitxClick: (Action) -> Unit,
    onFcitxMenuAction: (Action) -> Unit,
    onToggleUndoRedo: () -> Unit,
    onReloadConfig: () -> Unit,
    onSettings: () -> Unit,
    onEditorInfo: () -> Unit,
    onResetLayout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val rootWidthPx = constraints.maxWidth
        val rootHeightPx = constraints.maxHeight
        // 记录每个格子相对根节点的位置，作为子菜单锚点（boundsInRoot 已含滚动偏移）
        val cellBounds = remember { mutableStateMapOf<Int, Rect>() }
        var menu by remember { mutableStateOf<StatusMenuState?>(null) }

        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(cells.size) { index ->
                val cell = cells[index]
                FixedCell(
                    cell = cell,
                    editing = editing,
                    showUndoRedo = showUndoRedo,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { cellBounds[index] = it.boundsInRoot() },
                    onClick = {
                        when (cell) {
                            is MorePageCell.Fcitx -> {
                                if (!cell.action.menu.isNullOrEmpty()) {
                                    menu = cellBounds[index]?.let {
                                        StatusMenuState(cell.action.menu, it)
                                    }
                                } else {
                                    menu = null
                                    onFcitxClick(cell.action)
                                }
                            }
                            MorePageCell.UndoRedoToggle -> onToggleUndoRedo()
                            MorePageCell.ResetLayout -> onResetLayout()
                            MorePageCell.ReloadConfig -> onReloadConfig()
                            MorePageCell.Settings -> onSettings()
                            MorePageCell.EditorInfo -> onEditorInfo()
                            is MorePageCell.Movable -> Unit
                        }
                    },
                )
            }
        }

        menu?.let { state ->
            StatusAreaMenu(
                menu = state.menu,
                anchor = state.anchor,
                rootWidth = rootWidthPx,
                rootHeight = rootHeightPx,
                onDismiss = { menu = null },
                onActionClick = { action ->
                    menu = null
                    onFcitxMenuAction(action)
                },
            )
        }
    }
}

private class StatusMenuState(val menu: Array<Action>, val anchor: Rect)

/**
 * 按钮池里的一格。
 *
 * 编辑模式下置灰不可点（只能被拖走），但**仍可拖动** —— 未启用的按钮也允许先摆到工具栏，
 * 之后启用即生效。
 */
@Composable
private fun MovableCell(
    cell: MorePageCell.Movable,
    splitKeyboardEnabled: Boolean,
    editing: Boolean,
    onClick: () -> Unit,
) {
    val enabled = cell.usable && !editing
    Column(
        modifier = Modifier.height(96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(4.dp))
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.inputFeedback(),
            backgroundColor = if (cell.usable) MiuixTheme.colorScheme.secondaryContainer
            else MiuixTheme.colorScheme.surfaceContainerHighest,
            cornerRadius = 24.dp,
            minWidth = 48.dp,
            minHeight = 48.dp,
        ) {
            val tint = if (cell.usable) MiuixTheme.colorScheme.onSecondaryContainer
            else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.35f)
            val label = stringResource(ToolbarButtonSpec.labelOf(cell.button))
            Box(Modifier.semantics { contentDescription = label }) {
                when (val icon = ToolbarButtonSpec.iconOf(cell.button, splitKeyboardEnabled)) {
                    is ToolbarIcon.Res -> Icon(
                        painter = painterResource(icon.id),
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(24.dp),
                    )
                    is ToolbarIcon.Vector -> Icon(
                        imageVector = icon.image,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(ToolbarButtonSpec.labelOf(cell.button)),
            color = MiuixTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 固定段里的一格。
 *
 * 编辑模式下只有撤销开关与重置可点，其余置灰 —— 它们是「编辑这次布局」的操作，
 * 而重载配置/设置/编辑器信息会把编辑会话顶掉。
 */
@Composable
private fun FixedCell(
    cell: MorePageCell,
    editing: Boolean,
    showUndoRedo: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val isActionEnabled = when (cell) {
        MorePageCell.UndoRedoToggle, MorePageCell.ResetLayout -> true
        else -> !editing
    }
    val label = fixedCellLabel(cell)
    val iconRes = fixedCellIcon(cell)
    // 激活态：fcitx 引擎项看自身状态；撤销开关看两个固定槽位当前是否显示
    val active = when (cell) {
        is MorePageCell.Fcitx -> cell.active
        MorePageCell.UndoRedoToggle -> showUndoRedo
        else -> false
    }
    val dimmed = !isActionEnabled
    val circleColor = when {
        dimmed -> MiuixTheme.colorScheme.surfaceContainerHighest
        active -> MiuixTheme.colorScheme.primary
        else -> MiuixTheme.colorScheme.secondaryContainer
    }
    val contentColor = when {
        dimmed -> MiuixTheme.colorScheme.onSurface.copy(alpha = 0.35f)
        active -> MiuixTheme.colorScheme.onPrimary
        else -> MiuixTheme.colorScheme.onSecondaryContainer
    }
    Column(
        modifier = modifier.height(96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(4.dp))
        IconButton(
            onClick = onClick,
            enabled = isActionEnabled,
            modifier = Modifier.inputFeedback(),
            backgroundColor = circleColor,
            cornerRadius = 24.dp,
            minWidth = 48.dp,
            minHeight = 48.dp,
        ) {
            Box(Modifier.semantics { contentDescription = label }) {
                if (iconRes != 0) {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(24.dp),
                    )
                } else {
                    Text(
                        text = firstCharacter(label),
                        color = contentColor,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            color = MiuixTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 固定项的图标；fcitx 项用引擎给的图标，其余用内置 drawable。 */
private fun fixedCellIcon(cell: MorePageCell): Int = when (cell) {
    is MorePageCell.Fcitx -> cell.icon
    MorePageCell.UndoRedoToggle -> R.drawable.ic_baseline_undo_24
    MorePageCell.ResetLayout -> R.drawable.ic_baseline_settings_backup_restore_24
    MorePageCell.ReloadConfig -> R.drawable.ic_baseline_sync_24
    MorePageCell.Settings -> R.drawable.ic_baseline_settings_24
    MorePageCell.EditorInfo -> R.drawable.ic_baseline_info_24
    is MorePageCell.Movable -> 0
}

/** 固定项的文案；fcitx 项用引擎给的短文本。 */
@Composable
private fun fixedCellLabel(cell: MorePageCell): String = when (cell) {
    is MorePageCell.Fcitx -> cell.label
    MorePageCell.UndoRedoToggle -> stringResource(R.string.more_page_undo_redo_toggle)
    MorePageCell.ResetLayout -> stringResource(R.string.more_page_reset_layout)
    MorePageCell.ReloadConfig -> stringResource(R.string.reload_config)
    MorePageCell.Settings -> stringResource(R.string.more_page_settings)
    MorePageCell.EditorInfo -> stringResource(R.string.more_page_editor_info)
    is MorePageCell.Movable -> ""
}

/**
 * fcitx 子菜单：以纯 Compose 弹层渲染，锚定在触发格子下方。
 *
 * 不用 Android PopupMenu（IME 浮窗内会抛 BadTokenException），
 * 也不用 miuix Window* 弹层（底层是系统 Dialog，同样必崩）。
 * 行渲染与配色全部走 miuix [DropdownImpl] / [MiuixTheme] 默认值。
 */
@Composable
private fun StatusAreaMenu(
    menu: Array<Action>,
    anchor: Rect,
    rootWidth: Int,
    rootHeight: Int,
    onDismiss: () -> Unit,
    onActionClick: (Action) -> Unit,
) {
    var menuSize by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier = Modifier.fillMaxSize()) {
        // 点击空白处关闭
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onDismiss)
        )
        Surface(
            modifier = Modifier
                .offset {
                    val menuHeight = menuSize.height.coerceAtLeast(0).toFloat()
                    val x = anchor.left.coerceIn(
                        0f,
                        (rootWidth - menuSize.width).coerceAtLeast(0).toFloat()
                    )
                    val y = if (anchor.bottom + menuHeight > rootHeight) {
                        (anchor.top - menuHeight).coerceAtLeast(0f)
                    } else {
                        anchor.bottom
                    }
                    IntOffset(x.roundToInt(), y.roundToInt())
                }
                .onSizeChanged { menuSize = it }
                .widthIn(min = 160.dp),
            shape = RoundedCornerShape(12.dp),
            color = MiuixTheme.colorScheme.surfaceContainer,
            shadowElevation = 8.dp,
        ) {
            Column {
                menu.forEachIndexed { index, action ->
                    if (action.isSeparator) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    } else {
                        Box(modifier = Modifier.inputFeedback()) {
                            DropdownImpl(
                                text = action.shortText,
                                optionSize = menu.size,
                                isSelected = false,
                                index = index,
                                onSelectedIndexChange = { onActionClick(action) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun firstCharacter(s: String): String {
    if (s.isEmpty()) return ""
    return s.codePointAt(0).let { String(Character.toChars(it)) }
}
