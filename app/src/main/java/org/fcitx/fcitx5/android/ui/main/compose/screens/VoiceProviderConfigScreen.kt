/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 在线识别平台的配置表单（集中接口渲染）。
 *
 * 平台通过 [OnlineAsrProvider.settings] 声明自己的字段（含读写闭包），本页只负责渲染：
 *  - 普通/密钥字段 → `TextField`（密钥带显隐切换）
 *  - 带 [OnlineSetting.options] 的字段 → 单选列表
 * 因此新增平台不需要动这个页面。
 */
package org.fcitx.fcitx5.android.ui.main.compose.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.voice.js.JsVoiceProviders
import org.fcitx.fcitx5.android.data.voice.online.OnlineAsrRegistry
import org.fcitx.fcitx5.android.data.voice.online.OnlineSetting
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Show
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun VoiceProviderConfigScreen(
    providerId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // 字段由插件脚本声明，只有跑一遍才有；进入设置页先解析一次再渲染。
    var ready by remember(providerId) { mutableStateOf(false) }
    LaunchedEffect(providerId) {
        runCatching { JsVoiceProviders.primeFieldCache() }
        ready = true
    }
    if (!ready) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    val provider = OnlineAsrRegistry.byId(providerId)
    val title = provider?.labelOverride
        ?: provider?.let { stringResource(it.nameRes) }
        ?: stringResource(R.string.voice_online_provider)

    PageScaffold(title = title, onBack = onBack, contentBottomPadding = 24.dp) {
        if (provider == null) {
            item {
                Card(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    colors = CardDefaults.defaultColors(
                        color = MiuixTheme.colorScheme.surfaceContainerHighest,
                    ),
                ) {
                    BasicComponent(title = stringResource(R.string.voice_online_provider_none))
                }
            }
            return@PageScaffold
        }

        item { SmallTitle(text = stringResource(R.string.voice_provider_settings)) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.surfaceContainerHighest,
                ),
            ) {
                provider.settings().forEach { setting ->
                    SettingField(setting)
                }
            }
        }
        if (provider.isConfigured(context)) {
            item {
                BasicComponent(
                    title = stringResource(R.string.voice_provider_settings),
                    summary = stringResource(R.string.voice_provider_configured),
                    endActions = {
                        Icon(
                            imageVector = MiuixIcons.Ok,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    },
                )
            }
        }
        item {
            Text(
                text = stringResource(R.string.voice_provider_hint_console),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun SettingField(setting: OnlineSetting) {
    val title = setting.labelText ?: stringResource(setting.label)
    when (setting.kind) {
        OnlineSetting.KIND_SWITCH -> {
            var checked by remember(setting.key) { mutableStateOf(setting.read() == "true") }
            SwitchPreference(
                title = title,
                summary = setting.description,
                checked = checked,
                onCheckedChange = {
                    checked = it
                    setting.write(it.toString())
                },
            )
        }
        OnlineSetting.KIND_SELECT -> {
            var current by remember(setting.key) { mutableStateOf(setting.read()) }
            BasicComponent(title = title)
            if (!setting.description.isNullOrBlank()) {
                BasicComponent(title = setting.description.orEmpty())
            }
            setting.options.forEach { option ->
                RadioButtonPreference(
                    title = option,
                    selected = option == current,
                    onClick = {
                        current = option
                        setting.write(option)
                    },
                )
            }
        }
        OnlineSetting.KIND_NUMBER -> {
            var value by remember(setting.key) { mutableStateOf(setting.read()) }
            TextField(
                value = value,
                onValueChange = {
                    value = it
                    setting.write(it)
                },
                label = title,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        else -> {
            val isSecret = setting.kind == OnlineSetting.KIND_SECRET || setting.secret
            var value by remember(setting.key) { mutableStateOf(if (isSecret) "" else setting.read()) }
            var visible by remember(setting.key) { mutableStateOf(!isSecret) }
            TextField(
                value = value,
                onValueChange = {
                    value = it
                    setting.write(it)
                },
                label = title,
                singleLine = true,
                visualTransformation = if (visible) VisualTransformation.None
                else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (isSecret) KeyboardType.Password else KeyboardType.Text,
                ),
                trailingIcon = if (isSecret) {
                    {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                imageVector = if (visible) MiuixIcons.Hide else MiuixIcons.Show,
                                contentDescription = null,
                            )
                        }
                    }
                } else null,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            )
            if (!setting.description.isNullOrBlank()) {
                BasicComponent(title = setting.description.orEmpty())
            }
        }
    }
}
