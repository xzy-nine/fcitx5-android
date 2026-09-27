/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * IME 顶部圆角**半径**（dp）：键盘体（[InputView] 的 customBackground）圆角裁剪用。
 */
const val IME_TOP_CORNER_RADIUS_DP = 16

/**
 * 键盘体顶部圆角**向上延伸的高度**（dp）—— custom 特性「顶部延伸带」。
 *
 */
const val IME_TOP_EXTENSION_DP = 9

/** [IME_TOP_EXTENSION_DP] 的 Compose 单位形式（延伸带高度）。 */
val ImeTopExtension: Dp = IME_TOP_EXTENSION_DP.dp
