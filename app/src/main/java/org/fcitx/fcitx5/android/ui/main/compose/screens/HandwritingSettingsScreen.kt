/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 手写输入设置页（独立输入方案）。
 *
 * 结构对齐 [VoiceInputSettingsScreen]：开关 + 边写边上屏 + 模型入口 + 模型市场。
 * 模型本身走 IME 覆盖层面板，本页不提供画布。
 */
package org.fcitx.fcitx5.android.ui.main.compose.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.handwriting.HandwritingModelStore
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun HandwritingSettingsScreen(
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
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
    val modelId = remember(version) { prefs.handwritingModelId.getValue() }
    val modelReady = HandwritingModelStore.isReady(context, modelId)

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

        item { SmallTitle(text = stringResource(R.string.voice_index_url)) }
        item {
            var indexUrl by remember { mutableStateOf(prefs.handwritingIndexUrl.getValue()) }
            top.yukonga.miuix.kmp.basic.TextField(
                value = indexUrl,
                onValueChange = {
                    indexUrl = it
                    prefs.handwritingIndexUrl.setValue(it.trim())
                },
                label = stringResource(R.string.handwriting_index_url),
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .fillMaxWidthCompat(),
                singleLine = true,
            )
        }
    }
}

/** `fillMaxWidth` 的小包装，避免与 miuix 的修饰符语义混淆。 */
private fun Modifier.fillMaxWidthCompat(): Modifier = this.then(
    androidx.compose.foundation.layout.fillMaxWidth()
)
