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
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.model.ModelDownloadState
import com.kingzcheung.xime.model.ModelInfo
import com.kingzcheung.xime.speech.AsrModelManager
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.voice.VoiceModelRepository
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
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

    val models by VoiceModelRepository.models.collectAsState()
    val loading by VoiceModelRepository.loadingIndex.collectAsState()
    val indexError by VoiceModelRepository.indexError.collectAsState()
    val downloadState by VoiceModelRepository.downloadState.collectAsState()
    val downloadingId by VoiceModelRepository.downloadingId.collectAsState()
    val selectedModelId = prefs.voiceAsrModelId.getValue()
    val mirror = AsrModelManager(context).getSelectedModelId()

    LaunchedEffect(Unit) {
        VoiceModelRepository.ensureIndexLoaded(context)
    }

    // 索引为空时回落到内置默认模型，保证页面永远有内容可选
    val displayed: List<ModelInfo> = models.ifEmpty {
        val default = AsrModelManager.DEFAULT_MODEL
        listOf(
            ModelInfo(
                id = default.id,
                name = default.name,
                description = default.description,
                category = com.kingzcheung.xime.model.ModelCategory.ASR,
                size = default.size,
                versions = listOf(
                    com.kingzcheung.xime.model.ModelVersion(
                        files = default.files.map {
                            com.kingzcheung.xime.model.ModelFile(it, "")
                        },
                        archiveUrl = default.downloadUrl,
                        size = default.size,
                    )
                ),
            )
        )
    }

    PageScaffold(
        title = stringResource(R.string.voice_model_market),
        onBack = onBack,
        actions = {
            TextButton(
                text = stringResource(R.string.voice_index_refresh),
                onClick = { VoiceModelRepository.refreshIndex(context) },
            )
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
                    text = stringResource(R.string.voice_index_failed),
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
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

        item { SmallTitle(stringResource(R.string.voice_models)) }

        items(displayed, key = { it.id }) { model ->
            val downloaded = VoiceModelRepository.isDownloaded(context, model)
            val isTarget = downloadingId == model.id
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.surfaceContainerHighest,
                ),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = model.name)
                            val size = model.size.ifBlank {
                                model.resolvedVersion()?.size.orEmpty()
                            }
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
                        }
                        if (model.id == selectedModelId || (selectedModelId.isBlank() && model.id == mirror)) {
                            Text(
                                text = stringResource(R.string.ok),
                                color = MiuixTheme.colorScheme.primary,
                            )
                        }
                    }

                    if (isTarget) {
                        Spacer(Modifier.height(8.dp))
                        val progress = (downloadState as? ModelDownloadState.Downloading)?.progress
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            progress = progress,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = when (val s = downloadState) {
                                is ModelDownloadState.Downloading -> {
                                    if (s.totalBytes > 0) {
                                        "${s.bytesDownloaded / 1024 / 1024} / ${s.totalBytes / 1024 / 1024} MB"
                                    } else {
                                        stringResource(R.string.voice_download)
                                    }
                                }

                                is ModelDownloadState.Error -> stringResource(R.string.voice_download_failed)
                                ModelDownloadState.Complete -> stringResource(R.string.voice_download_extracting)
                                ModelDownloadState.Idle -> stringResource(R.string.voice_state_processing)
                            },
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!downloaded) {
                            TextButton(
                                text = stringResource(
                                    if (isTarget) R.string.voice_download_cancel else R.string.voice_download
                                ),
                                onClick = {
                                    if (isTarget) VoiceModelRepository.cancelDownload()
                                    else VoiceModelRepository.downloadModel(context, model)
                                },
                            )
                        } else {
                            TextButton(
                                text = stringResource(R.string.voice_model_delete),
                                onClick = { VoiceModelRepository.deleteModel(context, model) },
                            )
                        }
                        TextButton(
                            text = stringResource(R.string.ok),
                            onClick = { prefs.voiceAsrModelId.setValue(model.id) },
                        )
                    }
                }
            }
        }
    }
}
