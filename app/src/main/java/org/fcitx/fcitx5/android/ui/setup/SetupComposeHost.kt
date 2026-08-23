/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.setup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * Compose host for the onboarding flow, wrapping [SetupScreen] in the miuix theme so that
 * SetupActivity itself only keeps the native (notification/channel) logic.
 */
@Composable
fun SetupComposeHost(onFinish: () -> Unit) {
    MiuixTheme(controller = remember { ThemeController(ColorSchemeMode.System) }) {
        SetupScreen(onFinish = onFinish)
    }
}
