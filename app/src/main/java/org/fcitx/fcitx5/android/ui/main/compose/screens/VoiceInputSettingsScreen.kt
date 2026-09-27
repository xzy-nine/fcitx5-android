/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.compose.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.speech.AsrModelManager
import com.kingzcheung.xime.speech.AsrPluginHostRegistry
import com.kingzcheung.xime.util.PermissionHelper
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.voice.VoicePermissionState
import org.fcitx.fcitx5.android.data.voice.VoicePluginBootstrap
import org.fcitx.fcitx5.android.data.voice.VoicePluginConfigEntryPoint
import org.fcitx.fcitx5.android.utils.InputMethodUtil
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * custom: 应用设置内的「语音输入」页面。
 *
 * 页面结构与其它设置页（WebDavSyncScreen / BroadcastScreen / StaticScreens）保持一致：
 * `PageScaffold` + `SmallTitle` 分节 + `Card(surfaceContainerHighest)` 分组，
 * 行一律使用 miuix 官方 preference 组件（`SwitchPreference` / `ArrowPreference` /
 * `RadioButtonPreference` / `BasicComponent`），不手写 `Row` + `Text` + `Switch` 行，
 * 颜色也全部交给组件处理（选中态、禁用态由组件按 `MiuixTheme` 主题色渲染）。
 *
 * 选项按本项目的内置引擎能力裁剪：本地/在线引擎选择、本地模型入口、简体输出、
 * 录音静音、自动模式、在线插件与其网络授权、回落的外部语音输入法、录音权限。
 */
@Composable
fun VoiceInputSettingsScreen(
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = AppPrefs.getInstance().voice
    val kbdPrefs = AppPrefs.getInstance().keyboard

    // ManagedPreference 不是 Compose State：用版本号驱动重组（与 ManagedPrefsScreen 同一做法）。
    // AppPrefs 注册的 OnSharedPreferenceChangeListener 会在任意偏好写入后回调，无需手动刷新。
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
    // 外部语音输入法列表随系统设置变化，不做 remember，与上游 VoiceInputList 同口径
    val voiceImes = InputMethodUtil.listVoiceInputMethods()
    val preferredVoiceIme = kbdPrefs.preferredVoiceInput.getValue()
    // 未显式选择（空）时与 InputMethodUtil.findVoiceSubtype("") 一致，回落到第一个可用项
    val effectiveVoiceIme =
        preferredVoiceIme.ifBlank { voiceImes.firstOrNull()?.first?.id.orEmpty() }

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

    PageScaffold(
        title = stringResource(R.string.voice_input),
        onBack = onBack,
        contentBottomPadding = 24.dp,
    ) {
        item { SmallTitle(text = stringResource(R.string.voice_engine)) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.surfaceContainerHighest,
                ),
            ) {
                SwitchPreference(
                    title = stringResource(R.string.voice_input_enabled),
                    summary = stringResource(R.string.voice_input_enabled_summary),
                    checked = enabled,
                    onCheckedChange = { prefs.voiceInputEnabled.setValue(it) },
                )
                SwitchPreference(
                    title = stringResource(R.string.voice_use_local),
                    summary = stringResource(R.string.voice_use_local_summary),
                    checked = useLocal,
                    enabled = enabled,
                    onCheckedChange = { prefs.voiceUseLocal.setValue(it) },
                )
            }
        }

        if (!useLocal) {
            item { SmallTitle(text = stringResource(R.string.voice_online_provider)) }
            item {
                Card(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    colors = CardDefaults.defaultColors(
                        color = MiuixTheme.colorScheme.surfaceContainerHighest,
                    ),
                ) {
                    if (plugins.isEmpty()) {
                        BasicComponent(title = stringResource(R.string.voice_online_provider_none))
                    } else {
                        plugins.forEach { plugin ->
                            val configured = plugin.isConfigured(context)
                            RadioButtonPreference(
                                title = plugin.displayName,
                                summary = if (configured) null
                                else stringResource(R.string.voice_provider_not_configured),
                                selected = plugin.pluginId == pluginId ||
                                        (pluginId.isBlank() && configured),
                                // 插件配置与「当前是否启用语音输入」无关，行始终可点（选中项写入偏好）
                                onClick = { prefs.voiceOnlinePluginId.setValue(plugin.pluginId) },
                                endActions = {
                                    IconButton(
                                        onClick = {
                                            VoicePluginConfigEntryPoint.open(context, plugin.pluginId)
                                        },
                                    ) {
                                        Icon(
                                            imageVector = MiuixIcons.Tune,
                                            contentDescription = stringResource(R.string.voice_plugin_config),
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        if (pendingHosts.isNotEmpty()) {
            item { SmallTitle(text = stringResource(R.string.voice_plugin_network_auth)) }
            item {
                Card(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    colors = CardDefaults.defaultColors(
                        color = MiuixTheme.colorScheme.surfaceContainerHighest,
                    ),
                ) {
                    pendingHosts.forEach { (hostPluginId, hosts) ->
                        val pluginName = ExtensionManager.getAllInstalledPlugins()
                            .firstOrNull { it.id == hostPluginId }?.name ?: hostPluginId
                        hosts.forEach { host ->
                            ArrowPreference(
                                title = host,
                                summary = pluginName,
                                endActions = {
                                    Text(
                                        text = stringResource(R.string.voice_plugin_authorize),
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                },
                                onClick = {
                                    SettingsPreferences.authorizePluginHost(
                                        context, hostPluginId, host
                                    )
                                    version += 1
                                },
                            )
                        }
                    }
                }
            }
        }

        item { SmallTitle(text = stringResource(R.string.voice_models)) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.surfaceContainerHighest,
                ),
            ) {
                ArrowPreference(
                    title = stringResource(R.string.voice_model_market),
                    summary = "$modelId · " + stringResource(
                        if (localReady) R.string.voice_model_state_ready
                        else R.string.voice_model_state_missing
                    ),
                    onClick = onOpenModels,
                )
            }
        }

        item { SmallTitle(text = stringResource(R.string.group_voice)) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.surfaceContainerHighest,
                ),
            ) {
                SwitchPreference(
                    title = stringResource(R.string.voice_simple_chinese),
                    checked = simpleChinese,
                    enabled = enabled,
                    onCheckedChange = { prefs.voiceSimpleChinese.setValue(it) },
                )
                SwitchPreference(
                    title = stringResource(R.string.voice_auto_mode),
                    summary = stringResource(R.string.voice_auto_mode_summary),
                    checked = autoMode,
                    enabled = enabled,
                    onCheckedChange = { prefs.voiceAutoMode.setValue(it) },
                )
                SwitchPreference(
                    title = stringResource(R.string.voice_mute_during_recording),
                    checked = muteDuringRecording,
                    enabled = enabled,
                    onCheckedChange = { prefs.voiceMuteDuringRecording.setValue(it) },
                )
            }
        }

        item { SmallTitle(text = stringResource(R.string.voice_record_permission)) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.surfaceContainerHighest,
                ),
            ) {
                if (permissionGranted) {
                    BasicComponent(
                        title = stringResource(R.string.voice_record_permission),
                        summary = stringResource(R.string.voice_permission_granted),
                    )
                } else {
                    ArrowPreference(
                        title = stringResource(R.string.voice_grant_permission),
                        summary = stringResource(R.string.voice_record_permission_missing),
                        onClick = { PermissionHelper.requestRecordAudioPermission(context) },
                    )
                }
            }
        }

        item { SmallTitle(text = stringResource(R.string.voice_external_ime)) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.surfaceContainerHighest,
                ),
            ) {
                Text(
                    text = stringResource(R.string.voice_external_ime_summary),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                if (voiceImes.isEmpty()) {
                    BasicComponent(title = stringResource(R.string._not_available_))
                } else {
                    voiceImes.forEach { (info, _) ->
                        RadioButtonPreference(
                            title = info.loadLabel(context.packageManager).toString(),
                            selected = info.id == effectiveVoiceIme,
                            onClick = {
                                kbdPrefs.preferredVoiceInput.setValue(info.id)
                                // 该偏好属于 keyboard 分类，本页监听的是 voice 分类：
                                // 显式 fireChange 才会触发本页重组（与 ManagedPrefsScreen 同做法）
                                kbdPrefs.fireChange(kbdPrefs.preferredVoiceInput.key)
                            },
                        )
                    }
                }
            }
        }
    }
}
