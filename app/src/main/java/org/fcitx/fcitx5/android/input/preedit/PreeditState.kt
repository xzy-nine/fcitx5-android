/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.preedit

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Compose 预编辑栏数据状态
 */
@Immutable
data class PreeditState(
    val upText: String = "",
    val upCursor: Int = -1,
    val downText: String = "",
    val visible: Boolean = false,
)

/**
 * Compose 预编辑栏视觉配置
 */
@Immutable
data class PreeditVisuals(
    val textColor: Color,
    val highlightColor: Color,
    val backgroundColor: Color,
)
