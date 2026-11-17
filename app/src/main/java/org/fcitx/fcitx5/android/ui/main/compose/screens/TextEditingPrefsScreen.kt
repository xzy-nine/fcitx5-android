/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.screens

import androidx.compose.runtime.Composable
import org.fcitx.fcitx5.android.ui.main.compose.PrefCategory
import org.fcitx.fcitx5.android.ui.main.compose.settings.ManagedPrefsScreen

/** 文本编辑设置页，偏好项由 [PrefCategory.TextEditing] 统一渲染。 */
@Composable
fun TextEditingPrefsScreen(onBack: () -> Unit) {
    ManagedPrefsScreen(
        category = PrefCategory.TextEditing.provider(),
        onBack = onBack,
    )
}
