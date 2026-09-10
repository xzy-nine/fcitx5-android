/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.wm

import android.content.Context
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 为 [ComposeWindow] 创建统一的 View 宿主（ComposeView + MiuixTheme）。
 *
 * 与 `composeTopView` / `popup.root` 的 Composition 风格保持一致；
 * 强制 LTR 布局方向，保证内部绝对定位（如子菜单锚定）不受 RTL 反转影响。
 */
fun createComposeWindowView(
    context: Context,
    content: @Composable () -> Unit,
): ComposeView = ComposeView(context).apply {
    layoutDirection = View.LAYOUT_DIRECTION_LTR
    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    setContent {
        MiuixTheme(controller = remember { ThemeController(ColorSchemeMode.System) }) {
            content()
        }
    }
}
