/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.compose.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.plugin.PluginConfigStoreImpl
import com.kingzcheung.xime.plugin.core.api.AsrPlugin
import com.kingzcheung.xime.plugin.core.config.UiNode
import com.kingzcheung.xime.plugin.core.config.UiNodeType
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.voice.VoicePluginBootstrap
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * custom: 在线 ASR 插件的「声明式配置表单」。
 *
 * 插件（Lua 或原生）只声明 `getSettingsSchema(): List<UiNode>`，宿主负责渲染与持久化
 * （值存 [PluginConfigStoreImpl]，每个插件一个独立 prefs 文件）。这与 Xime 的
 * `PluginConfigFormScreen` 设计一致，只是宿主 UI 换成了 miuix。
 */
@Composable
fun VoicePluginConfigScreen(
    pluginId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        VoicePluginBootstrap.ensureLoaded(context)
    }

    val plugin = com.kingzcheung.xime.plugin.ExtensionManager.getPluginById(pluginId) as? AsrPlugin
    val pluginName = com.kingzcheung.xime.plugin.ExtensionManager.getAllInstalledPlugins()
        .firstOrNull { it.id == pluginId }?.name ?: pluginId

    val store = remember(pluginId) {
        PluginConfigStoreImpl(context.applicationContext as android.app.Application, pluginId)
    }
    val schema: List<UiNode> = remember(pluginId) {
        try {
            plugin?.getSettingsSchema().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }
    val values = remember(pluginId) {
        mutableStateMapOf<String, String>().apply {
            schema.forEach { node ->
                node.key?.let { key ->
                    put(key, store.get(key) ?: node.defaultValue.orEmpty().ifBlank { node.value.orEmpty() })
                }
            }
        }
    }
    var dynamicOptions by remember(pluginId) {
        mutableStateOf<Map<String, List<String>>>(emptyMap())
    }

    LaunchedEffect(pluginId, schema) {
        schema.filter { it.type == UiNodeType.SELECT || it.type == UiNodeType.MULTI_SELECT }
            .forEach { node ->
                val key = node.key ?: return@forEach
                if (node.options.isNotEmpty()) return@forEach
                val opts = try {
                    plugin?.getOptions(key)
                } catch (_: Exception) {
                    null
                }
                if (!opts.isNullOrEmpty()) {
                    dynamicOptions = dynamicOptions + (key to opts)
                }
            }
    }

    val missingRequired = schema.any { node ->
        node.required && node.key != null && values[node.key].orEmpty().isBlank()
    }

    PageScaffold(
        title = pluginName,
        onBack = onBack,
        actions = {
            Button(
                onClick = {
                    schema.forEach { node ->
                        node.key?.let { key -> store.set(key, values[key].orEmpty()) }
                    }
                    Toast.makeText(context, R.string.voice_download_ok, Toast.LENGTH_SHORT).show()
                    onBack()
                },
                enabled = !missingRequired,
            ) {
                Text(stringResource(R.string.voice_plugin_config))
            }
        },
    ) {
        item {
            if (plugin == null || schema.isEmpty()) {
                Text(
                    text = stringResource(R.string.voice_plugin_config_summary),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                ) {
                    schema.forEach { node ->
                        when (node.type) {
                            UiNodeType.SECTION -> {
                                SmallTitle(node.label ?: "")
                            }

                            UiNodeType.TEXT, UiNodeType.TEXTAREA, UiNodeType.NUMBER ->
                                ConfigTextField(
                                    node = node,
                                    value = values[node.key].orEmpty(),
                                    onValueChange = { values[node.key!!] = it },
                                )

                            UiNodeType.SECRET -> ConfigTextField(
                                node = node,
                                value = values[node.key].orEmpty(),
                                secret = true,
                                onValueChange = { values[node.key!!] = it },
                            )

                            UiNodeType.SWITCH -> ConfigSwitchRow(
                                node = node,
                                checked = values[node.key] == "true" || values[node.key] == "1",
                                onCheckedChange = { values[node.key!!] = if (it) "true" else "false" },
                            )

                            UiNodeType.SELECT, UiNodeType.MULTI_SELECT -> ConfigSelectRow(
                                node = node,
                                currentValue = values[node.key].orEmpty(),
                                options = node.options.ifEmpty { dynamicOptions[node.key].orEmpty() },
                                multi = node.type == UiNodeType.MULTI_SELECT,
                                onValueChange = { values[node.key!!] = it },
                            )

                            UiNodeType.BUTTON -> {
                                Spacer(Modifier.height(4.dp))
                                TextButton(
                                    text = node.label ?: (node.key ?: ""),
                                    onClick = {
                                        val action = node.key ?: return@TextButton
                                        scope.launch {
                                            val result = try {
                                                plugin.onAction(action)
                                            } catch (e: Exception) {
                                                e.message
                                            }
                                            Toast.makeText(
                                                context,
                                                result ?: context.getString(R.string.voice_download_ok),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    },
                                )
                            }

                            // 面板展示型节点（METRIC/DIVIDER）在设置表单里降级为只读文本
                            UiNodeType.METRIC, UiNodeType.DIVIDER -> Text(
                                text = listOfNotNull(node.label, node.value).joinToString(": "),
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            )
                        }
                        node.helpText?.let {
                            Text(
                                text = it,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun ConfigTextField(
    node: UiNode,
    value: String,
    secret: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = node.label ?: node.key.orEmpty(),
        singleLine = node.type != UiNodeType.TEXTAREA,
        visualTransformation = if (secret && !visible) PasswordVisualTransformation()
        else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                secret -> KeyboardType.Password
                node.type == UiNodeType.NUMBER -> KeyboardType.Number
                else -> KeyboardType.Text
            }
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun ConfigSwitchRow(
    node: UiNode,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = node.label ?: node.key.orEmpty(), modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ConfigSelectRow(
    node: UiNode,
    currentValue: String,
    options: List<String>,
    multi: Boolean,
    onValueChange: (String) -> Unit,
) {
    val selected = currentValue.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(text = node.label ?: node.key.orEmpty())
            Spacer(Modifier.height(6.dp))
            if (options.isEmpty()) {
                Text(
                    text = node.placeholder ?: node.helpText ?: node.key.orEmpty(),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    options.forEach { option ->
                        val isOn = option in selected
                        TextButton(
                            text = option,
                            onClick = {
                                when {
                                    !multi -> onValueChange(option)
                                    isOn -> onValueChange((selected - option).joinToString(","))
                                    else -> onValueChange((selected + option).joinToString(","))
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
