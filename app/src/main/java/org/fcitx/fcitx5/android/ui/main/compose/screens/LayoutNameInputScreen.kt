/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 布局名称输入页。
 *
 * 输入一个布局名；存在可选来源时另选一份现有布局作为初始内容。
 * 名称不能为空。
 */
@Composable
fun LayoutNameInputScreen(
    title: String,
    label: String,
    hint: String,
    initialValue: String,
    copySources: List<String>,
    copySourceLabels: List<String>,
    copySourceDefault: String,
    onConfirm: (name: String, copySource: String?) -> Unit,
    onBack: () -> Unit,
    onEmptyName: () -> Unit,
) {
    var name by remember { mutableStateOf(initialValue) }
    var sourceIndex by remember {
        mutableStateOf(copySources.indexOf(copySourceDefault).coerceAtLeast(0))
    }

    PageScaffold(
        title = title.ifBlank { stringResource(R.string.text_keyboard_layout_add_layout) },
        onBack = onBack,
        floatingActionButton = {
            ArrowPreference(
                title = stringResource(R.string.save),
                modifier = Modifier.padding(16.dp),
                onClick = {
                    val trimmed = name.trim()
                    if (trimmed.isEmpty()) {
                        onEmptyName()
                        return@ArrowPreference
                    }
                    onConfirm(trimmed, copySources.getOrNull(sourceIndex))
                },
            )
        },
    ) {
        item { SmallTitle(text = label.ifBlank { stringResource(R.string.text_keyboard_layout_layout_name) }) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = hint.ifBlank { stringResource(R.string.text_keyboard_layout_layout_name_hint) },
                )
            }
        }

        if (copySources.isNotEmpty()) {
            item { SmallTitle(text = stringResource(R.string.text_keyboard_layout_copy_from)) }
            item {
                Card(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
                ) {
                    copySources.forEachIndexed { index, _ ->
                        RadioButtonPreference(
                            title = copySourceLabels.getOrNull(index) ?: copySources[index],
                            selected = index == sourceIndex,
                            onClick = { sourceIndex = index },
                        )
                    }
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
