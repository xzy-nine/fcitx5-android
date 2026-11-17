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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.voice.js.JsVoiceField
import org.fcitx.fcitx5.android.input.voice.js.JsVoiceFields
import org.fcitx.fcitx5.android.input.voice.js.JsVoiceProviders
import org.fcitx.fcitx5.android.input.voice.js.JsVoiceSettingsStore
import timber.log.Timber
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 单个 JS 语音插件的设置页。
 *
 * 字段由插件自身声明：普通项与密钥项分开呈现，改动立即保存。
 * 相同声明同时喂给引擎，因此设置页能改的就是引擎会读的。
 */
@Composable
fun JsVoicePluginSettingsScreen(
    pluginId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var fields by remember { mutableStateOf<List<JsVoiceField>>(emptyList()) }
    var values by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var secretStates by remember { mutableStateOf<Map<String, JsVoiceSettingsStore.SecretState>>(emptyMap()) }
    var failure by remember { mutableStateOf<String?>(null) }
    val label = remember(pluginId) { JsVoiceProviders.specOf(pluginId)?.label ?: pluginId }

    LaunchedEffect(pluginId) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val declared = JsVoiceProviders.describe(pluginId)
                val states = declared.filter { it.isSecret }.associate { field ->
                    field.key to JsVoiceSettingsStore.secretState(pluginId, field.key)
                }
                Triple(declared, JsVoiceFields.currentValues(declared, JsVoiceSettingsStore.values(pluginId)), states)
            }
        }
        result.onSuccess { (declared, current, states) ->
            fields = declared
            values = current
            secretStates = states
        }.onFailure {
            Timber.w(it, "读取语音插件设置失败")
            failure = it.message
        }
    }

    /** 写入一项并应用插件声明的联动默认值。 */
    fun update(key: String, value: String) {
        values = values + (key to value)
        JsVoiceSettingsStore.setValue(pluginId, key, value)
        JsVoiceFields.defaultsAfterChange(fields, key, values).forEach { (dependentKey, dependentValue) ->
            values = values + (dependentKey to dependentValue)
            JsVoiceSettingsStore.setValue(pluginId, dependentKey, dependentValue)
        }
    }

    val visible = fields.filter { JsVoiceFields.isVisible(it, values) }
    val parameters = visible.filter { !it.isSecret }
    val credentials = visible.filter { it.isSecret }

    PageScaffold(title = label, onBack = onBack) {
        failure?.let { message ->
            item {
                Text(
                    text = stringResource(R.string.js_voice_settings_failed, message),
                    modifier = Modifier.padding(16.dp),
                    color = MiuixTheme.colorScheme.error,
                )
            }
        }

        if (fields.isEmpty() && failure == null) {
            item {
                Text(
                    text = stringResource(R.string.js_voice_settings_empty),
                    modifier = Modifier.padding(16.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        if (parameters.isNotEmpty()) {
            item { SmallTitle(text = stringResource(R.string.ui_plugin_settings)) }
            parameters.forEach { field ->
                item {
                    Card(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
                    ) {
                        when (field.kind) {
                            JsVoiceField.KIND_SWITCH -> SwitchPreference(
                                title = field.label,
                                summary = field.description.ifBlank { null },
                                checked = values[field.key] == "true",
                                onCheckedChange = { update(field.key, it.toString()) },
                            )
                            JsVoiceField.KIND_SELECT -> {
                                Text(
                                    text = field.label,
                                    modifier = Modifier.padding(start = 16.dp, top = 12.dp),
                                )
                                field.options.forEach { option ->
                                    RadioButtonPreference(
                                        title = option.label,
                                        selected = values[field.key] == option.value,
                                        onClick = { update(field.key, option.value) },
                                    )
                                }
                            }
                            else -> TextField(
                                value = values[field.key].orEmpty(),
                                onValueChange = { update(field.key, it) },
                                label = field.label,
                            )
                        }
                    }
                }
            }
        }

        if (credentials.isNotEmpty()) {
            item { SmallTitle(text = stringResource(R.string.ui_plugin_credentials)) }
            credentials.forEach { field ->
                item {
                    Card(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
                    ) {
                        val ready = secretStates[field.key] == JsVoiceSettingsStore.SecretState.READY
                        TextField(
                            value = values[field.key].orEmpty(),
                            onValueChange = { update(field.key, it) },
                            label = field.label,
                        )
                        Text(
                            text = if (ready) {
                                field.description.ifBlank { stringResource(R.string.js_voice_secret_set) }
                            } else {
                                stringResource(R.string.js_voice_secret_unset)
                            },
                            modifier = Modifier.padding(16.dp),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.padding(12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                ArrowPreference(
                    title = stringResource(R.string.ui_plugin_manage),
                    onClick = { /* 源码替换与移除在后续版本提供 */ },
                )
            }
        }
    }
}
