/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 模型市场页（**唯一实现**，按 [MarketCategory] 泛化）。
 *
 * 通过 [MarketCategory] 拿到清单与下载状态，因此页面本身不认识任何具体模型：
 * 目前唯一分类是语音「语音模型市场」= `ModelMarketScreen(VoiceMarketCategory)`。
 * 分类差异只体现在索引 category / 内置清单 / 就绪判定（见分类实现）。
 */
package org.fcitx.fcitx5.android.ui.main.compose.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.market.MarketCategory
import org.fcitx.fcitx5.android.data.market.MarketDownloadState
import org.fcitx.fcitx5.android.data.market.MarketModel
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
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
fun ModelMarketScreen(
    category: MarketCategory,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val models by category.models.collectAsState()
    val loading by category.loadingIndex.collectAsState()
    val indexError by category.indexError.collectAsState()
    val downloadState by category.downloadState.collectAsState()
    val downloadingId by category.downloadingId.collectAsState()
    val lastError by category.lastError.collectAsState()
    val selectedModelId = category.selectedModelId(context)

    LaunchedEffect(category) {
        category.ensureIndexLoaded(context)
    }

    PageScaffold(
        title = stringResource(category.titleRes),
        onBack = onBack,
        actions = {
            IconButton(onClick = { category.refreshIndex(context) }) {
                Icon(
                    imageVector = MiuixIcons.Refresh,
                    contentDescription = stringResource(R.string.handwriting_index_refresh),
                )
            }
        },
    ) {
        ModelMarketList(
            category = category,
            models = models,
            loading = loading,
            indexError = indexError,
            downloadState = downloadState,
            downloadingId = downloadingId,
            lastError = lastError,
            selectedModelId = selectedModelId,
        )
    }
}

/** 列表内容单独抽出，便于后续别的宿主（如设置页内嵌）复用。 */
private fun LazyListScope.ModelMarketList(
    category: MarketCategory,
    models: List<MarketModel>,
    loading: Boolean,
    indexError: String?,
    downloadState: MarketDownloadState,
    downloadingId: String?,
    lastError: String?,
    selectedModelId: String,
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
    // 下载失败要显式留在页面上（否则用户只会看到「点了没反应」）
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

    item {
        ModelIndexField(category)
    }

    item { SmallTitle(stringResource(R.string.voice_models)) }

    items(models, key = { it.id }) { model ->
        ModelCard(
            category = category,
            model = model,
            downloadState = downloadState,
            downloadingId = downloadingId,
            selectedModelId = selectedModelId,
        )
    }
}

/** 索引地址覆盖输入框（留空 = 用默认端点 index.ximei.me）。 */
@Composable
private fun ModelIndexField(category: MarketCategory) {
    val prefs = AppPrefs.getInstance()
    var indexUrl by remember(category.id) {
        mutableStateOf(prefs.voice.voiceIndexUrl.getValue())
    }
    TextField(
        value = indexUrl,
        onValueChange = {
            indexUrl = it
            prefs.voice.voiceIndexUrl.setValue(it.trim())
        },
        label = stringResource(R.string.voice_index_url),
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@Composable
private fun ModelCard(
    category: MarketCategory,
    model: MarketModel,
    downloadState: MarketDownloadState,
    downloadingId: String?,
    selectedModelId: String,
) {
    val context = LocalContext.current
    val downloaded = category.isDownloaded(context, model)
    val isTarget = downloadingId == model.id
    val inUse = model.id == selectedModelId
    val failed = isTarget && downloadState is MarketDownloadState.Error

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
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        progress = when (downloadState) {
                            is MarketDownloadState.Downloading -> downloadState.progress
                            is MarketDownloadState.Extracting -> downloadState.progress
                            else -> null
                        },
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = when (val state = downloadState) {
                            is MarketDownloadState.Downloading -> {
                                if (state.totalBytes > 0) {
                                    "${state.bytesDownloaded} / ${state.totalBytes}" +
                                            " (${(state.progress * 100).toInt()}%)"
                                } else {
                                    "${(state.progress * 100).toInt()}%"
                                }
                            }

                            // bzip2 解压耗时且无下载字节可显示，单独给文案与百分比
                            is MarketDownloadState.Extracting ->
                                stringResource(R.string.voice_extracting) +
                                        " ${(state.progress * 100).toInt()}%"

                            is MarketDownloadState.Error ->
                                stringResource(R.string.handwriting_download_failed)

                            MarketDownloadState.Complete ->
                                stringResource(R.string.handwriting_model_download_ok)

                            MarketDownloadState.Idle ->
                                stringResource(R.string.handwriting_downloading)
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
                            failed -> R.string.handwriting_model_retry_download
                            isTarget -> R.string.handwriting_model_cancel_download
                            else -> R.string.handwriting_model_download
                        }
                    ),
                    onClick = {
                        when {
                            failed -> category.downloadModel(context, model)
                            isTarget -> category.cancelDownload()
                            else -> category.downloadModel(context, model)
                        }
                    },
                )
                // 已下载且在用：不再给动作，只显示状态
                inUse -> Text(
                    text = stringResource(R.string.handwriting_model_in_use),
                    color = MiuixTheme.colorScheme.primary,
                )
                // 已下载且未在用：删除 + 使用
                else -> {
                    IconButton(onClick = { category.deleteModel(context, model) }) {
                        Icon(
                            imageVector = MiuixIcons.Delete,
                            contentDescription = stringResource(R.string.handwriting_model_delete),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    TextButton(
                        text = stringResource(R.string.handwriting_model_select),
                        onClick = { category.selectModel(context, model) },
                    )
                }
            }
        }
    }
}
