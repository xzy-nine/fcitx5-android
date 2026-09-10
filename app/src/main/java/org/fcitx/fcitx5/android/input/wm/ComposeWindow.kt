/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.wm

import androidx.compose.runtime.Composable

/**
 * 标记接口：实现此接口的 [InputWindow] 由 Compose 渲染。
 *
 * [InputWindowManager] 挂载时用 `window is ComposeWindow` 判断：
 * Compose 窗口由统一的 ComposeView 宿主（[createComposeWindowView]）承载，
 * 其余窗口维持原有 `onCreateView(): View` 兜底。
 */
interface ComposeWindow {

    @Composable
    fun Content()
}
