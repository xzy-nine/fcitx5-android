/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton

/**
 * 可移动按钮的图标按钮（工具栏行与更多页按钮池共用）。
 *
 * 尺寸跟着 [LocalToolbarHeight]（方形、圆角为半高、最小点击区同尺寸），与固定元素
 * （菜单/更多/收起键盘）保持一致，因此两种区域里的按钮外观完全一致。
 *
 * 不可用时只置灰、不隐藏：位置信息对「布局正在被编辑」这件事本身有意义，
 * 直接消失会让用户以为按钮丢失。置灰策略交给调用方通过 [contentColor] 与 [enabled] 表达。
 */
@Composable
fun ToolbarMovableButton(
    button: ToolbarButton,
    contentColor: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    splitKeyboardEnabled: Boolean = false,
    /** 正在被拖动：源位置留白，避免看起来复制了一份。 */
    dimmed: Boolean = false,
    contentDescription: String? = null,
    onClick: () -> Unit,
) {
    val label = stringResource(ToolbarButtonSpec.labelOf(button))
    val tint = if (dimmed) contentColor.copy(alpha = 0f) else contentColor
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(LocalToolbarHeight.current)
            .inputFeedback(),
        cornerRadius = LocalToolbarHeight.current / 2,
        minWidth = LocalToolbarHeight.current,
        minHeight = LocalToolbarHeight.current,
    ) {
        ToolbarButtonIcon(
            button = button,
            tint = tint,
            splitKeyboardEnabled = splitKeyboardEnabled,
            contentDescription = contentDescription ?: label,
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * 按 [ToolbarButtonSpec] 画一个按钮图标。
 *
 * 三个渲染区（工具栏可停靠行、更多页按钮池、拖动幽灵图标）共用，保证同一按钮
 * 在三处永远是同一个图标；图标尺寸由调用方通过 [modifier] 决定。
 */
@Composable
fun ToolbarButtonIcon(
    button: ToolbarButton,
    tint: Color,
    modifier: Modifier = Modifier,
    splitKeyboardEnabled: Boolean = false,
    contentDescription: String? = null,
) {
    when (val icon = ToolbarButtonSpec.iconOf(button, splitKeyboardEnabled)) {
        is ToolbarIcon.Res -> Icon(
            painter = painterResource(icon.id),
            contentDescription = contentDescription,
            tint = tint,
            modifier = modifier,
        )
        is ToolbarIcon.Vector -> Icon(
            imageVector = icon.image,
            contentDescription = contentDescription,
            tint = tint,
            modifier = modifier,
        )
    }
}
