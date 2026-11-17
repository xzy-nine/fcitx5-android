/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.fcitx.fcitx5.android.R
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 布局文件名输入页。
 *
 * 新建或重命名一份布局文件；名称需通过规范化校验。
 * 新建时可选择是否复制当前布局作为初始内容。
 */
@Composable
fun LayoutFileProfileInputScreen(
    isRename: Boolean,
    initialProfile: String,
    showCopySwitch: Boolean,
    copyCurrentDefault: Boolean,
    onConfirm: (profile: String, copyCurrent: Boolean) -> Unit,
    onBack: () -> Unit,
    onInvalidName: () -> Unit,
) {
    var profile by remember { mutableStateOf(initialProfile) }
    var copyCurrent by remember { mutableStateOf(copyCurrentDefault) }

    PageScaffold(
        title = stringResource(
            if (isRename) R.string.text_keyboard_layout_file_rename
            else R.string.text_keyboard_layout_file_create,
        ),
        onBack = onBack,
        floatingActionButton = {
            ArrowPreference(
                title = stringResource(R.string.save),
                modifier = Modifier.padding(16.dp),
                onClick = {
                    val normalized = org.fcitx.fcitx5.android.input.config.UserConfigFiles
                        .normalizeTextKeyboardLayoutProfile(profile)
                    if (normalized == null) {
                        onInvalidName()
                        return@ArrowPreference
                    }
                    onConfirm(normalized, if (showCopySwitch) copyCurrent else true)
                },
            )
        },
    ) {
        item { SmallTitle(text = stringResource(R.string.text_keyboard_layout_file_name)) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                TextField(
                    value = profile,
                    onValueChange = { profile = it },
                    label = stringResource(R.string.text_keyboard_layout_file_name_hint),
                )
            }
        }
        if (showCopySwitch) {
            item {
                Card(
                    modifier = Modifier.padding(12.dp),
                    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
                ) {
                    SwitchPreference(
                        title = stringResource(R.string.text_keyboard_layout_file_copy_current),
                        checked = copyCurrent,
                        onCheckedChange = { copyCurrent = it },
                    )
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                ArrowPreference(
                    title = stringResource(android.R.string.cancel),
                    onClick = onBack,
                )
            }
        }
    }
}
