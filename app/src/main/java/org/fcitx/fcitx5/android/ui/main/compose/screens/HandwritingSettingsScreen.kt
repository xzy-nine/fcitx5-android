/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 手写输入设置页（独立输入方案）。
 *
 * 结构对齐 [VoiceInputSettingsScreen]：开关 + 边写边上屏 + 模型入口 + 索引地址覆盖。
 * 模型本身在 IME 覆盖层面板里使用，本页不提供画布。
 */
package org.fcitx.fcitx5.android.ui.main.compose.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.handwriting.HandwritingMarketCategory
import org.fcitx.fcitx5.android.data.handwriting.XiaomiHandwritingEngine
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun HandwritingSettingsScreen(
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenGestureDemo: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = AppPrefs.getInstance().handwriting

    // ManagedPreference 不是 Compose State：用版本号驱动重组（与语音设置页同一做法）
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

    val enabled = remember(version) { prefs.handwritingInputEnabled.getValue() }
    val autoCommit = remember(version) { prefs.handwritingAutoCommit.getValue() }
    val singleChar = remember(version) { prefs.handwritingSingleCharMode.getValue() }
    val stylusToolbox = remember(version) { prefs.stylusToolboxEnabled.getValue() }
    val systemEngine = remember(version) { prefs.handwritingSystemEngineEnabled.getValue() }
    val modelId = remember(version) { prefs.handwritingModelId.getValue() }
    val modelReady = HandwritingMarketCategory.isReady(context, modelId)
    // 系统手写引擎能力探测（读系统设置 + 探测引擎 jar；纯本地、无 IO）
    // 分开探测文字识别与手势：两者可能只装了一个
    val systemTextOk = remember(version) { XiaomiHandwritingEngine.isTextRecognitionAvailable(context) }
    val systemGestureOk = remember(version) { XiaomiHandwritingEngine.isGestureAvailable(context) }
    val systemEngineAvailable = systemTextOk || systemGestureOk

    PageScaffold(
        title = stringResource(R.string.handwriting_input),
        onBack = onBack,
        contentBottomPadding = 24.dp,
    ) {
        item { SmallTitle(text = stringResource(R.string.group_handwriting)) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.surfaceContainerHighest,
                ),
            ) {
                SwitchPreference(
                    title = stringResource(R.string.handwriting_input_enabled),
                    summary = stringResource(R.string.handwriting_input_enabled_summary),
                    checked = enabled,
                    onCheckedChange = { prefs.handwritingInputEnabled.setValue(it) },
                )
                SwitchPreference(
                    title = stringResource(R.string.handwriting_auto_commit),
                    summary = stringResource(R.string.handwriting_auto_commit_summary),
                    checked = autoCommit,
                    enabled = enabled,
                    onCheckedChange = { prefs.handwritingAutoCommit.setValue(it) },
                )
                SwitchPreference(
                    title = stringResource(R.string.handwriting_single_char),
                    summary = stringResource(R.string.handwriting_single_char_summary),
                    checked = singleChar,
                    enabled = enabled,
                    onCheckedChange = { prefs.handwritingSingleCharMode.setValue(it) },
                )
                SwitchPreference(
                    title = stringResource(R.string.handwriting_stylus_toolbox),
                    summary = stringResource(R.string.handwriting_stylus_toolbox_summary),
                    checked = stylusToolbox,
                    enabled = enabled,
                    onCheckedChange = { prefs.stylusToolboxEnabled.setValue(it) },
                )
                SwitchPreference(
                    title = stringResource(R.string.handwriting_system_engine),
                    // 分能力回显：文字识别 / 手势各自可用性（只装一个时能一眼看出）
                    summary = stringResource(R.string.handwriting_system_engine_summary) + "\n" +
                            if (systemEngineAvailable) {
                                stringResource(
                                    R.string.handwriting_system_engine_capabilities,
                                    if (systemTextOk) "✓" else "✗",
                                    if (systemGestureOk) "✓" else "✗",
                                )
                            } else {
                                stringResource(R.string.handwriting_system_engine_unavailable)
                            },
                    checked = systemEngine,
                    enabled = enabled,
                    onCheckedChange = { prefs.handwritingSystemEngineEnabled.setValue(it) },
                )
            }
        }

        item { SmallTitle(text = stringResource(R.string.handwriting_stylus_gestures)) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.surfaceContainerHighest,
                ),
            ) {
                ArrowPreference(
                    title = stringResource(R.string.handwriting_gesture_try),
                    summary = stringResource(R.string.handwriting_stylus_gestures_summary),
                    onClick = onOpenGestureDemo,
                )
            }
        }

        item { SmallTitle(text = stringResource(R.string.handwriting_model)) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.surfaceContainerHighest,
                ),
            ) {
                ArrowPreference(
                    title = stringResource(R.string.handwriting_model_market),
                    summary = "$modelId · " + stringResource(
                        if (modelReady) R.string.handwriting_model_state_ready
                        else R.string.handwriting_model_state_missing
                    ),
                    onClick = onOpenModels,
                )
            }
        }

        item { SmallTitle(text = stringResource(R.string.handwriting_index_url)) }
        item {
            var indexUrl by remember { mutableStateOf(prefs.handwritingIndexUrl.getValue()) }
            TextField(
                value = indexUrl,
                onValueChange = {
                    indexUrl = it
                    prefs.handwritingIndexUrl.setValue(it.trim())
                },
                label = stringResource(R.string.handwriting_index_url),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}
