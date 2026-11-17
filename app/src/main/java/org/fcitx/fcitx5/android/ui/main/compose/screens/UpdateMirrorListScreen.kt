/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.update.MirrorRule
import org.fcitx.fcitx5.android.ui.main.update.UpdatePrefs
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 更新镜像规则列表。
 *
 * 每条规则对应一次地址替换；条目点击进入编辑页，编辑页直接写回偏好，
 * 因此返回时重新读取即可得到最新内容。
 */
@Composable
fun UpdateMirrorListScreen(
    onBack: () -> Unit,
    onEdit: (String?) -> Unit,
) {
    val context = LocalContext.current
    var rules by remember { mutableStateOf<List<MirrorRule>>(emptyList()) }

    fun reload() {
        rules = UpdatePrefs.loadMirrorRules(context)
    }

    // 编辑页只写偏好不回传数据，故在恢复时重读。
    LifecycleResumeEffect(Unit) {
        reload()
        onPauseOrDispose { }
    }


    PageScaffold(
        title = stringResource(R.string.update_mirror_settings_title),
        onBack = onBack,
        floatingActionButton = {
            TextButton(
                text = stringResource(R.string.add),
                onClick = { onEdit(null) },
                colors = ButtonDefaults.textButtonColorsPrimary(),
                modifier = Modifier.padding(16.dp),
            )
        },
    ) {
        if (rules.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.none),
                    modifier = Modifier.padding(16.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        } else {
            item { SmallTitle(text = stringResource(R.string.update_mirror_settings_title)) }
            item {
                Card(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    colors = CardDefaults.defaultColors(
                        color = MiuixTheme.colorScheme.surfaceContainerHighest,
                    ),
                ) {
                    rules.forEach { rule ->
                        ArrowPreference(
                            title = rule.name,
                            summary = rule.pattern + " → " + rule.replacement,
                            onClick = { onEdit(rule.id) },
                        )
                    }
                }
            }
        }
    }
}