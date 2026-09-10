/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.status

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.fcitx.fcitx5.android.core.Action
import org.fcitx.fcitx5.android.input.bar.inputFeedback
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/**
 * 状态区（更多页）的 Compose 渲染层，纯 UI 无业务逻辑。
 *
 * 4 列网格渲染 [StatusAreaEntry]；fcitx 项带子菜单时在根容器内以
 * Compose 弹层展开（不触发系统 Dialog / PopupMenu，IME 浮窗内安全）。
 *
 * 颜色一律取 [MiuixTheme] 系统主题（不再读 fcitx View 主题），
 * 组件复用 miuix 的 [IconButton] / [DropdownImpl]；后续其他 Compose 层（工具栏/候选/弹窗）同此迁移。
 */
@Composable
fun StatusAreaGrid(
    entries: List<StatusAreaEntry>,
    onItemClick: (StatusAreaEntry) -> Unit,
    onMenuActionClick: (Action) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
    ) {
        val rootWidthPx = constraints.maxWidth
        val rootHeightPx = constraints.maxHeight
        // 记录每个格子相对根节点的位置，作为子菜单锚点（boundsInRoot 已含滚动偏移）
        val cellBounds = remember { mutableStateMapOf<Int, Rect>() }
        var menu by remember { mutableStateOf<StatusMenuState?>(null) }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(entries.size) { index ->
                    val entry = entries[index]
                    StatusAreaCell(
                        entry = entry,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { cellBounds[index] = it.boundsInRoot() },
                        onClick = {
                            if (entry is StatusAreaEntry.Fcitx && !entry.action.menu.isNullOrEmpty()) {
                                menu = cellBounds[index]?.let {
                                    StatusMenuState(
                                        entry.action.menu,
                                        it
                                    )
                                }
                            } else {
                                menu = null
                                onItemClick(entry)
                            }
                        },
                    )
                }
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
                    onMenuActionClick(action)
                },
            )
        }
    }
}

private class StatusMenuState(val menu: Array<Action>, val anchor: Rect)

@Composable
private fun StatusAreaCell(
    entry: StatusAreaEntry,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val circleColor =
        if (entry.active) MiuixTheme.colorScheme.primary
        else MiuixTheme.colorScheme.secondaryContainer
    val contentColor =
        if (entry.active) MiuixTheme.colorScheme.onPrimary
        else MiuixTheme.colorScheme.onSecondaryContainer
    Column(
        modifier = modifier.height(96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(4.dp))
        IconButton(
            onClick = onClick,
            modifier = Modifier.inputFeedback(),
            backgroundColor = circleColor,
            cornerRadius = 24.dp,
            minWidth = 48.dp,
            minHeight = 48.dp,
        ) {
            if (entry.icon != 0) {
                Icon(
                    painter = painterResource(entry.icon),
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(24.dp),
                )
            } else {
                Text(
                    text = firstCharacter(entry.label),
                    color = contentColor,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = entry.label,
            color = MiuixTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * fcitx 子菜单：以纯 Compose 弹层渲染，锚定在触发格子下方。
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
        Box(
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
                .shadow(8.dp, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .background(MiuixTheme.colorScheme.surfaceContainer)
                .widthIn(min = 160.dp),
        ) {
            Column {
                menu.forEachIndexed { index, action ->
                    if (action.isSeparator) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .padding(horizontal = 16.dp)
                                .background(MiuixTheme.colorScheme.dividerLine)
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
