/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.compose.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.fcitx.fcitx5.android.data.voice.VoiceModelCatalog
import org.fcitx.fcitx5.android.data.voice.VoiceModelDownloadState
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.voice.VoiceModelInfo
import org.fcitx.fcitx5.android.data.voice.VoiceModelRepository
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * custom: 语音模型市场页（索引来自 Xime 的远程模型市场；下载/删除/选择本地模型）。
 *
 * 结构与 whisperIME 的模型下载页一致（模型选择 + 进度 + 开始/更新按钮），组件换成 miuix。
 * 下载状态由 [VoiceModelRepository] 持有，离开本页不会中断下载。
 */
@Composable
fun VoiceModelMarketScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = AppPrefs.getInstance().voice

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

    val models by VoiceModelRepository.models.collectAsState()
    val loading by VoiceModelRepository.loadingIndex.collectAsState()
    val indexError by VoiceModelRepository.indexError.collectAsState()
    val downloadState by VoiceModelRepository.downloadState.collectAsState()
    val downloadingId by VoiceModelRepository.downloadingId.collectAsState()
    val lastError by VoiceModelRepository.lastError.collectAsState()
    // 与引擎同口径（空值回落内置默认模型）
    val selectedModelId = remember(version) {
        prefs.voiceAsrModelId.getValue().ifBlank { VoiceModelCatalog.DEFAULT_ID }
    }

    LaunchedEffect(Unit) {
        VoiceModelRepository.ensureIndexLoaded(context)
    }

    // 索引为空时回落到内置官方模型清单，保证页面永远有内容可选
    val displayed: List<VoiceModelInfo> = models.ifEmpty { VoiceModelCatalog.builtin }

    PageScaffold(
        title = stringResource(R.string.voice_model_market),
        onBack = onBack,
        actions = {
            IconButton(onClick = { VoiceModelRepository.refreshIndex(context) }) {
                Icon(
                    imageVector = MiuixIcons.Refresh,
                    contentDescription = stringResource(R.string.voice_index_refresh),
                )
            }
        },
    ) {
        if (loading) {
            item {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(16.dp))
            }
        }
        if (indexError != null) {
            item {
                Text(
                    text = stringResource(R.string.voice_index_failed) +
                            ": " + stringResource(R.string.voice_index_using_builtin),
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        }
        // 下载失败要显式留在页面上（否则用户只会看到「点了没反应」）
        lastError?.let { message ->
            item {
                Text(
                    text = stringResource(R.string.voice_download_failed) + "：" + message,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        if (models.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.voice_index_using_builtin),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        // 索引地址覆盖（留空 = 用内置默认索引 index.ximei.me）
        item {
            var indexUrl by remember { mutableStateOf(prefs.voiceIndexUrl.getValue()) }
            TextField(
                value = indexUrl,
                onValueChange = {
                    indexUrl = it
                    prefs.voiceIndexUrl.setValue(it.trim())
                },
                label = stringResource(R.string.voice_index_url),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        item { SmallTitle(stringResource(R.string.voice_models)) }

        items(displayed, key = { it.id }) { model ->
            val downloaded = VoiceModelRepository.isDownloaded(context, model)
            val isTarget = downloadingId == model.id
            val inUse = model.id == selectedModelId
            val failed = isTarget && downloadState is VoiceModelDownloadState.Error
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.surfaceContainerHighest,
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(vertical = 12.dp)) {
                        Text(text = model.name)
                        val size = model.size
                        Text(
                            text = listOfNotNull(
                                model.description.takeIf { it.isNotBlank() },
                                size.takeIf { it.isNotBlank() },
                                stringResource(
                                    if (downloaded) R.string.voice_model_state_ready
                                    else R.string.voice_model_state_missing
                                ),
                            ).joinToString(" · "),
                            color = if (downloaded) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )

                        if (isTarget) {
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                progress = (downloadState as? VoiceModelDownloadState.Downloading)?.progress,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = when (val s = downloadState) {
                                    is VoiceModelDownloadState.Downloading -> {
                                        if (s.totalBytes > 0) {
                                            "${s.bytesDownloaded / 1024 / 1024} / ${s.totalBytes / 1024 / 1024} MB"
                                        } else {
                                            stringResource(R.string.voice_downloading)
                                        }
                                    }

                                    is VoiceModelDownloadState.Error -> stringResource(R.string.voice_download_failed)
                                    VoiceModelDownloadState.Complete -> stringResource(R.string.voice_download_ok)
                                    VoiceModelDownloadState.Idle -> stringResource(R.string.voice_downloading)
                                },
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }

                    when {
                        // 未下载：只有下载/取消/重试，不能「使用」
                        !downloaded -> TextButton(
                            text = stringResource(
                                when {
                                    failed -> R.string.voice_download_retry
                                    isTarget -> R.string.voice_download_cancel
                                    else -> R.string.voice_download
                                }
                            ),
                            onClick = {
                                when {
                                    failed -> VoiceModelRepository.downloadModel(context, model)
                                    isTarget -> VoiceModelRepository.cancelDownload()
                                    else -> VoiceModelRepository.downloadModel(context, model)
                                }
                            },
                        )
                        // 已下载且在用：不再给动作，只显示状态
                        inUse -> Text(
                            text = stringResource(R.string.voice_in_use),
                            color = MiuixTheme.colorScheme.primary,
                        )
                        // 已下载且未在用：删除 + 使用
                        else -> {
                            IconButton(
                                onClick = { VoiceModelRepository.deleteModel(context, model) },
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Delete,
                                    contentDescription = stringResource(R.string.voice_model_delete),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            TextButton(
                                text = stringResource(R.string.voice_use_this),
                                onClick = { prefs.voiceAsrModelId.setValue(model.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}
