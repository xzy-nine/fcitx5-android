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
    contentDescription: String? = null,
    onClick: () -> Unit,
) {
    val label = stringResource(ToolbarButtonSpec.labelOf(button))
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
        when (val icon = ToolbarButtonSpec.iconOf(button, splitKeyboardEnabled)) {
            is ToolbarIcon.Res -> Icon(
                painter = painterResource(icon.id),
                contentDescription = contentDescription ?: label,
                tint = contentColor,
                modifier = Modifier.size(24.dp),
            )
            is ToolbarIcon.Vector -> Icon(
                imageVector = icon.image,
                contentDescription = contentDescription ?: label,
                tint = contentColor,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
