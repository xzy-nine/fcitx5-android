/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 手写模型市场页。
 *
 * 与 [VoiceModelMarketScreen] **同一份远程索引、同一套交互**，区别只有分类：
 * 语音取 `category: asr`，手写取 `category: handwriting`（见 HandwritingModelIndex）。
 * 下载状态由 [HandwritingModelRepository] 持有，离开本页不会中断下载。
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
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.handwriting.HandwritingModelCatalog
import org.fcitx.fcitx5.android.data.handwriting.HandwritingModelInfo
import org.fcitx.fcitx5.android.data.handwriting.HandwritingModelRepository
import org.fcitx.fcitx5.android.data.handwriting.HandwritingModelState
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
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

@Composable
fun HandwritingModelMarketScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = AppPrefs.getInstance().handwriting

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

    val models by HandwritingModelRepository.models.collectAsState()
    val loading by HandwritingModelRepository.loadingIndex.collectAsState()
    val indexError by HandwritingModelRepository.indexError.collectAsState()
    val downloadState by HandwritingModelRepository.downloadState.collectAsState()
    val downloadingId by HandwritingModelRepository.downloadingId.collectAsState()
    val lastError by HandwritingModelRepository.lastError.collectAsState()
    val selectedModelId = remember(version) {
        prefs.handwritingModelId.getValue().ifBlank { HandwritingModelCatalog.DEFAULT_ID }
    }

    LaunchedEffect(Unit) {
        HandwritingModelRepository.ensureIndexLoaded(context)
    }

    val displayed: List<HandwritingModelInfo> =
        models.ifEmpty { HandwritingModelCatalog.builtin }

    PageScaffold(
        title = stringResource(R.string.handwriting_model_market),
        onBack = onBack,
        actions = {
            IconButton(onClick = { HandwritingModelRepository.refreshIndex(context) }) {
                Icon(
                    imageVector = MiuixIcons.Refresh,
                    contentDescription = stringResource(R.string.handwriting_index_refresh),
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
                    text = stringResource(R.string.handwriting_index_failed) +
                            ": " + stringResource(R.string.handwriting_index_using_builtin),
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        }
        lastError?.let { message ->
            item {
                Text(
                    text = stringResource(R.string.handwriting_download_failed) + "：" + message,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        if (models.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.handwriting_index_using_builtin),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        // 索引地址覆盖（留空 = 沿用语音输入的索引来源，其次内置默认端点）
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

        item { SmallTitle(stringResource(R.string.handwriting_model)) }

        items(displayed, key = { it.id }) { model ->
            val downloaded = HandwritingModelRepository.isDownloaded(context, model)
            val isTarget = downloadingId == model.id
            val inUse = model.id == selectedModelId
            val failed = isTarget && downloadState is HandwritingModelState.Error
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
                        Text(
                            text = listOfNotNull(
                                model.description.takeIf { it.isNotBlank() },
                                model.size.takeIf { it.isNotBlank() },
                                stringResource(
                                    if (downloaded) R.string.handwriting_model_state_ready
                                    else R.string.handwriting_model_state_missing
                                ),
                            ).joinToString(" · "),
                            color = if (downloaded) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )

                        if (isTarget) {
                            Spacer(Modifier.height(8.dp))
                            val progress = (downloadState as? HandwritingModelState.Downloading)?.progress
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                progress = progress,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = when (val s = downloadState) {
                                    is HandwritingModelState.Downloading ->
                                        stringResource(R.string.handwriting_downloading) +
                                                " ${(s.progress * 100).toInt()}%"

                                    is HandwritingModelState.Error ->
                                        stringResource(R.string.handwriting_download_failed)

                                    HandwritingModelState.Complete ->
                                        stringResource(R.string.handwriting_model_download_ok)

                                    HandwritingModelState.Idle ->
                                        stringResource(R.string.handwriting_downloading)
                                },
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }

                    when {
                        !downloaded -> TextButton(
                            text = stringResource(
                                when {
                                    failed -> R.string.handwriting_model_retry_download
                                    isTarget -> R.string.handwriting_model_cancel_download
                                    else -> R.string.handwriting_model_download
                                }
                            ),
                            onClick = {
                                when {
                                    failed -> HandwritingModelRepository.downloadModel(context, model)
                                    isTarget -> HandwritingModelRepository.cancelDownload()
                                    else -> HandwritingModelRepository.downloadModel(context, model)
                                }
                            },
                        )

                        inUse -> Text(
                            text = stringResource(R.string.handwriting_model_in_use),
                            color = MiuixTheme.colorScheme.primary,
                        )

                        else -> {
                            IconButton(
                                onClick = {
                                    HandwritingModelRepository.deleteModel(context, model)
                                },
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Delete,
                                    contentDescription = stringResource(R.string.handwriting_model_delete),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            TextButton(
                                text = stringResource(R.string.handwriting_model_select),
                                onClick = { prefs.handwritingModelId.setValue(model.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}
