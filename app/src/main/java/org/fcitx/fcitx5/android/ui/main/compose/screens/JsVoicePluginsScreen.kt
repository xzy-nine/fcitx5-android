/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.voice.js.JsAppSelection
import org.fcitx.fcitx5.android.input.voice.js.JsVoiceProviders
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * JS 语音插件管理页。
 *
 * 列出可用的语音识别插件（内置与导入平级），可切换当前使用的插件；
 * 源码替换、移除等操作在各自的插件设置页内。
 */
@Composable
fun JsVoicePluginsScreen(
    onBack: () -> Unit,
    onOpenPlugin: (String) -> Unit = {},
) {
    val context = LocalContext.current
    var providers by remember { mutableStateOf(JsVoiceProviders.all()) }
    var preferred by remember { mutableStateOf(AppPrefs.getInstance().keyboard.preferredVoiceInput.getValue()) }
    var assistant by remember { mutableStateOf(JsAppSelection.preferred()) }

    LaunchedEffect(Unit) {
        providers = JsVoiceProviders.all()
        preferred = AppPrefs.getInstance().keyboard.preferredVoiceInput.getValue()
        assistant = JsAppSelection.preferred()
    }

    val sorted = providers.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })

    PageScaffold(title = stringResource(R.string.js_voice_plugins_title), onBack = onBack) {
        item {
            Text(
                text = stringResource(R.string.js_voice_plugins_summary),
                modifier = Modifier.padding(16.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }

        item { SmallTitle(text = stringResource(R.string.js_app_preferred)) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                val selected = assistant
                ArrowPreference(
                    title = stringResource(R.string.js_app_preferred),
                    summary = selected?.let { (spec, app) -> app.label + " · " + spec.label }
                        ?: stringResource(R.string.js_app_none),
                    enabled = JsAppSelection.available().isNotEmpty(),
                    onClick = { /* 由系统助手选择器承接 */ },
                )
            }
        }

        item { SmallTitle(text = stringResource(R.string.js_voice_plugins_title)) }
        if (sorted.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.js_app_none),
                    modifier = Modifier.padding(16.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        sorted.forEach { spec ->
            item {
                val capabilities = buildList {
                    if (spec.hasVoice) add(context.getString(R.string.ui_plugin_voice))
                    if (spec.apps.isNotEmpty()) add(context.getString(R.string.ui_plugin_tools))
                }.joinToString(" · ")
                val detail = listOf(
                    capabilities,
                    context.getString(R.string.js_voice_plugins_entry_summary, spec.version, spec.originName),
                ).filter { it.isNotBlank() }.joinToString(" · ")
                val isCurrent = spec.id == preferred
                Card(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    colors = CardDefaults.defaultColors(
                        color = if (isCurrent) MiuixTheme.colorScheme.secondaryContainer
                        else MiuixTheme.colorScheme.surfaceContainerHighest,
                    ),
                ) {
                    ArrowPreference(
                        title = spec.label,
                        summary = if (isCurrent) {
                            context.getString(R.string.js_voice_plugins_current, detail)
                        } else {
                            detail
                        },
                        onClick = { onOpenPlugin(spec.id) },
                    )
                }
            }
        }
    }
}
