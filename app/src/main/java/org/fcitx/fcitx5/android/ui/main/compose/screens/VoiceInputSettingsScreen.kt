/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.compose.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.speech.AsrModelManager
import com.kingzcheung.xime.speech.AsrPluginHostRegistry
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.voice.VoicePermissionState
import org.fcitx.fcitx5.android.data.voice.VoicePluginBootstrap
import org.fcitx.fcitx5.android.data.voice.VoicePluginConfigEntryPoint
import org.fcitx.fcitx5.android.utils.InputMethodUtil
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * custom: 应用设置内的「语音输入」页面。
 *
 * 结构与 whisperIME 的语音设置页一致（模型 / 语言 / 简繁 / 杂项），
 * 但组件全部使用 miuix；选项按本项目的内置引擎能力裁剪：
 * 本地/在线引擎选择、本地模型入口、简体输出、录音静音、自动模式、
 * 是否用空格长按唤起、回落的外部语音输入法、录音权限。
 */
@Composable
fun VoiceInputSettingsScreen(
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = AppPrefs.getInstance().voice
    val kbdPrefs = AppPrefs.getInstance().keyboard

    // ManagedPreference 不是 Compose State：用版本号驱动重组（与 ManagedPrefsScreen 同一做法）
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val listener = object : ManagedPreferenceProvider.OnChangeListener {
            override fun onChange(key: String) {
                version += 1
            }
        }
        prefs.registerOnChangeListener(listener)
        onDispose { prefs.unregisterOnChangeListener(listener) }
    }

    val enabled = remember(version) { prefs.voiceInputEnabled.getValue() }
    val useLocal = remember(version) { prefs.voiceUseLocal.getValue() }
    val pluginId = remember(version) { prefs.voiceOnlinePluginId.getValue() }
    val simpleChinese = remember(version) { prefs.voiceSimpleChinese.getValue() }
    val muteDuringRecording = remember(version) { prefs.voiceMuteDuringRecording.getValue() }
    val autoMode = remember(version) { prefs.voiceAutoMode.getValue() }
    val modelId = remember(version) { prefs.voiceAsrModelId.getValue() }
    val permissionGranted by VoicePermissionState.granted.collectAsState()

    val plugins = AsrPluginHostRegistry.enabledAsrPlugins(context)
    val localReady = AsrModelManager(context).isModelReady()

    LaunchedEffect(Unit) {
        VoicePermissionState.refresh(context)
        // 确保插件框架已初始化（冷启动路径也会由 FcitxApplication 触发，这里兜底）
        VoicePluginBootstrap.ensureLoaded(context)
    }

    // 插件的待授权网络域名（每次重组重新读取，授权后即时消失）
    val pendingHosts = remember(version, plugins.size) {
        plugins.associate { it.pluginId to ExtensionManager.getUnauthorizedHosts(context, it.pluginId) }
            .filterValues { it.isNotEmpty() }
    }

    PageScaffold(title = stringResource(R.string.voice_input), onBack = onBack) {
        item {
            SmallTitle(stringResource(R.string.voice_engine))
        }
        item {
            SettingCard {
                SwitchRow(
                    title = stringResource(R.string.voice_input_enabled),
                    summary = stringResource(R.string.voice_input_enabled_summary),
                    checked = enabled,
                    onCheckedChange = { prefs.voiceInputEnabled.setValue(it) },
                )
                SwitchRow(
                    title = stringResource(R.string.voice_use_local),
                    summary = stringResource(R.string.voice_use_local_summary),
                    checked = useLocal,
                    enabled = enabled,
                    onCheckedChange = { prefs.voiceUseLocal.setValue(it) },
                )
            }
        }

        if (!useLocal) {
            item {
                SmallTitle(stringResource(R.string.voice_online_provider))
            }
            item {
                SettingCard {
                    if (plugins.isEmpty()) {
                        Text(
                            text = stringResource(R.string.voice_online_provider_none),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        )
                    } else {
                        plugins.forEach { plugin ->
                            val configured = plugin.isConfigured(context)
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = plugin.displayName)
                                    if (!configured) {
                                        Text(
                                            text = stringResource(R.string.voice_provider_not_configured),
                                            color = MiuixTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                if (plugin.pluginId == pluginId || (pluginId.isBlank() && configured)) {
                                    Text(
                                        text = stringResource(R.string.voice_in_use),
                                        color = MiuixTheme.colorScheme.primary,
                                    )
                                }
                                TextButton(
                                    text = stringResource(R.string.voice_plugin_config),
                                    onClick = {
                                        VoicePluginConfigEntryPoint.open(context, plugin.pluginId)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (pendingHosts.isNotEmpty()) {
            item {
                SmallTitle(stringResource(R.string.voice_plugin_network_auth))
            }
            item {
                SettingCard {
                    pendingHosts.forEach { (pluginId, hosts) ->
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(
                                text = ExtensionManager.getAllInstalledPlugins()
                                    .firstOrNull { it.id == pluginId }?.name ?: pluginId
                            )
                            hosts.forEach { host ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = host,
                                        modifier = Modifier.weight(1f),
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                    TextButton(
                                        text = stringResource(R.string.voice_plugin_authorize),
                                        onClick = {
                                            SettingsPreferences.authorizePluginHost(
                                                context, pluginId, host
                                            )
                                            version += 1
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            SmallTitle(stringResource(R.string.voice_models))
        }
        item {
            SettingCard {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = modelId)
                        Text(
                            text = stringResource(
                                if (localReady) R.string.voice_model_state_ready
                                else R.string.voice_model_state_missing
                            ),
                            color = if (localReady) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    ArrowAction(
                        text = stringResource(R.string.voice_model_market),
                        onClick = onOpenModels,
                    )
                }
            }
        }

        item {
            SmallTitle(stringResource(R.string.group_voice))
        }
        item {
            SettingCard {
                SwitchRow(
                    title = stringResource(R.string.voice_simple_chinese),
                    summary = null,
                    checked = simpleChinese,
                    enabled = enabled,
                    onCheckedChange = { prefs.voiceSimpleChinese.setValue(it) },
                )
                SwitchRow(
                    title = stringResource(R.string.voice_auto_mode),
                    summary = stringResource(R.string.voice_auto_mode_summary),
                    checked = autoMode,
                    enabled = enabled,
                    onCheckedChange = { prefs.voiceAutoMode.setValue(it) },
                )
                SwitchRow(
                    title = stringResource(R.string.voice_mute_during_recording),
                    summary = null,
                    checked = muteDuringRecording,
                    enabled = enabled,
                    onCheckedChange = { prefs.voiceMuteDuringRecording.setValue(it) },
                )
            }
        }

        item {
            SmallTitle(stringResource(R.string.voice_record_permission))
        }

        item {
            SettingCard {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (permissionGranted) {
                            stringResource(R.string.voice_permission_granted)
                        } else {
                            stringResource(R.string.voice_record_permission_missing)
                        },
                        modifier = Modifier.weight(1f),
                        color = if (permissionGranted) MiuixTheme.colorScheme.onSurface
                        else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    if (!permissionGranted) {
                        TextButton(
                            text = stringResource(R.string.voice_grant_permission),
                            onClick = {
                                com.kingzcheung.xime.util.PermissionHelper
                                    .requestRecordAudioPermission(context)
                            },
                        )
                    }
                }
            }
        }

        item {
            SmallTitle(stringResource(R.string.voice_external_ime))
        }
        item {
            SettingCard {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(text = stringResource(R.string.voice_external_ime_summary))
                    Spacer(Modifier.height(8.dp))
                    val voiceImes = InputMethodUtil.listVoiceInputMethods()
                    if (voiceImes.isEmpty()) {
                        Text(
                            text = stringResource(R.string._not_available_),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    } else {
                        val current = kbdPrefs.preferredVoiceInput.getValue()
                        voiceImes.forEach { (info, _) ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = info.loadLabel(context.packageManager).toString(),
                                    modifier = Modifier.weight(1f),
                                )
                                if (info.id == current || (current.isBlank() && voiceImes.first().first.id == info.id)) {
                                    Text(
                                        text = stringResource(R.string.voice_in_use),
                                        color = MiuixTheme.colorScheme.primary,
                                    )
                                }
                                TextButton(
                                    text = stringResource(R.string.voice_use_this),
                                    onClick = {
                                        kbdPrefs.preferredVoiceInput.setValue(info.id)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    summary: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(vertical = 12.dp)) {
            Text(
                text = title,
                color = if (enabled) MiuixTheme.colorScheme.onSurface
                else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            summary?.let {
                Text(
                    text = it,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun ArrowAction(text: String, onClick: () -> Unit) {
    TextButton(text = text, onClick = onClick)
}
