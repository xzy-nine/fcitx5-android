/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.compose

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 系统栏（含手势提示线）取顶部栏的底色。
 *
 * 顶部栏是 PageScaffold 里的 barColor，即 MiuixTheme.colorScheme.surface；
 * 这里用同一个值写入窗口，并关闭导航栏对比度蒙层。
 */
@Composable
fun SystemBars() {
    val barColor = MiuixTheme.colorScheme.surface
    val argb = barColor.toArgb()
    val lightIcons = barColor.luminance() > 0.5f
    val context = LocalContext.current

    SideEffect {
        val activity = context as? Activity ?: return@SideEffect
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = lightIcons
        controller.isAppearanceLightNavigationBars = lightIcons
        // Android 15 起导航栏由窗口背景透出，setNavigationBarColor 不再生效；
        // 因此同时把窗口背景设为同一色。
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(argb))
        @Suppress("DEPRECATION")
        window.navigationBarColor = argb
        @Suppress("DEPRECATION")
        window.statusBarColor = argb
        window.isNavigationBarContrastEnforced = false
    }
}
