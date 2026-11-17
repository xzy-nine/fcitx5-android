/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.screens

import android.content.Intent
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.ui.main.compose.AppRoute
import org.fcitx.fcitx5.android.ui.main.compose.PrefCategory
import org.fcitx.fcitx5.android.ui.main.compose.settings.ManagedPrefsScreen
import org.fcitx.fcitx5.android.ui.main.settings.behavior.TextKeyboardLayoutEditorActivity
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 文本编辑设置页。
 *
 * 偏好项由 [AppRoute.Prefs] 统一渲染；底部另设布局编辑器入口，
 * 它不写入任何偏好，只用来调出 text_editor 布局。
 */
@Composable
fun TextEditingPrefsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    ManagedPrefsScreen(
        category = PrefCategory.TextEditing.provider(),
        onBack = onBack,
        extraContent = {
            item {
                Card(
                    modifier = Modifier.padding(12.dp),
                    colors = CardDefaults.defaultColors(
                        color = MiuixTheme.colorScheme.surfaceContainerHighest,
                    ),
                ) {
                    ArrowPreference(
                        title = stringResource(R.string.text_editing_edit_layout),
                        summary = stringResource(R.string.text_editing_edit_layout_summary),
                        onClick = {
                            context.startActivity(
                                Intent(context, TextKeyboardLayoutEditorActivity::class.java).apply {
                                    putExtra(
                                        TextKeyboardLayoutEditorActivity.EXTRA_INITIAL_LAYOUT,
                                        "text_editor",
                                    )
                                },
                            )
                        },
                    )
                }
            }
        },
    )
}