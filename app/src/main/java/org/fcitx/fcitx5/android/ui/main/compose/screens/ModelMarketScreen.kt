/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 模型市场页（**唯一实现**，按 [MarketCategory] 泛化）。
 *
 * 通过 [MarketCategory] 拿到清单与下载状态，因此页面本身不认识任何具体模型：
 * 分类由 [MarketCategories] 注册。
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.market.MarketCategory
import org.fcitx.fcitx5.android.data.market.MarketDownloadState
import org.fcitx.fcitx5.android.data.market.MarketModel
import org.fcitx.fcitx5.android.data.market.MarketModelGroup
import org.fcitx.fcitx5.android.data.market.MarketModelGrouping
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/** 语言组收起时卡内预览的变体条数（超出部分在组标题里报数）。 */
private const val GROUP_COLLAPSED_PREVIEW = 3

@Composable
fun ModelMarketScreen(
    category: MarketCategory,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // 同步查询（isDownloaded / selectedModelId）不是 Compose State，靠 revision 驱动重组
    val revision by category.revision.collectAsState()
    val models by category.models.collectAsState()
    val loading by category.loadingIndex.collectAsState()
    val indexError by category.indexError.collectAsState()
    val downloadState by category.downloadState.collectAsState()
    val downloadingId by category.downloadingId.collectAsState()
    val lastError by category.lastError.collectAsState()
    val selectedModelId = remember(revision, category) { category.selectedModelId(context) }
    // 系统语言（或用户已选语言）对应的基础语言键：该语言及其全部地区变体置顶
    val preferredKeys = remember(revision, category) {
        category.preferredLanguageKeys(context)
    }
    // 清单里没有的系统语言：置顶一条「直接下载」（数字墨水会直接向 Google 请求）
    val directModels = remember(revision, category, models) {
        category.directDownloadModels(context)
    }
    // 已下载的排前面（用中的永远第一；组内保持清单原顺序，避免每次重组跳位）
    val rank: (MarketModel) -> Int = { model ->
        MarketModelGrouping.rankOf(
            model = model,
            selectedModelId = selectedModelId,
            isDownloaded = category.isDownloaded(context, model),
            preferredKeys = preferredKeys,
        )
    }
    val orderedModels = remember(models, revision, category) {
        models.sortedByDescending(rank)
    }
    // 按语言变体折叠（数字墨水）：组内用中的/已下载的排前面，组本身也按同一权重排
    val grouped = remember(orderedModels, revision, category) {
        if (!category.groupsByLanguageVariant) null
        else MarketModelGrouping.group(
            models = orderedModels,
            rank = rank,
            nameOf = { category.displayNameOf(it) ?: it },
        )
    }
    // 展开态：默认收起；首次拿到分组后**只自动展开一次**「正在使用」那一组，
// 之后完全听用户的（否则用户手动收起会被反复弹开）
    var expandedKeys by remember(category) { mutableStateOf(emptySet<String>()) }
    var autoExpanded by remember(category) { mutableStateOf(false) }
    LaunchedEffect(grouped) {
        if (autoExpanded || grouped == null) return@LaunchedEffect
        autoExpanded = true
        grouped.firstOrNull { g -> g.variants.any { it.id == selectedModelId } }
            ?.let { expandedKeys = setOf(it.key) }
    }

    LaunchedEffect(category) {
        category.ensureIndexLoaded(context)
        // 返回本页时重查「已下载」（数字墨水是异步状态；文件类分类只重算一次）
        category.refreshDownloadedState(context)
    }

    PageScaffold(
        title = stringResource(category.titleRes),
        onBack = onBack,
        actions = {
            IconButton(onClick = { category.refreshIndex(context) }) {
                Icon(
                    imageVector = MiuixIcons.Refresh,
                    contentDescription = stringResource(R.string.market_index_refresh),
                )
            }
        },
    ) {
        ModelMarketList(
            category = category,
            models = orderedModels,
            directModels = directModels,
            grouped = grouped,
            expandedKeys = expandedKeys,
            onToggleGroup = { key ->
                expandedKeys = if (key in expandedKeys) expandedKeys - key
                else expandedKeys + key
            },
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
    directModels: List<MarketModel>,
    grouped: List<MarketModelGroup>?,
    expandedKeys: Set<String>,
    onToggleGroup: (String) -> Unit,
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
                text = stringResource(R.string.market_index_failed) +
                        ": " + stringResource(R.string.market_index_using_builtin),
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
    }
    // 下载失败要显式留在页面上（否则用户只会看到「点了没反应」）
    lastError?.let { message ->
        item {
            Text(
                text = stringResource(R.string.market_download_failed) + "：" + message,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }

    // 只有拉远程索引的分类才需要索引地址覆盖输入框
    if (category.usesRemoteIndex) {
        item {
            ModelIndexField()
        }
    }

    item { SmallTitle(stringResource(R.string.market_models)) }

    // 清单外的系统语言：置顶一行提示 + 直接下载卡片（条目本身仍走分类的下载/删除/使用）
    if (directModels.isNotEmpty()) {
        item(key = "direct-title") {
            Text(
                text = stringResource(R.string.market_direct_hint),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        items(items = directModels, key = { "direct:${it.id}" }) { model ->
            ModelCard(
                category = category,
                model = model,
                downloadState = downloadState,
                downloadingId = downloadingId,
                selectedModelId = selectedModelId,
            )
        }
    }

    if (grouped != null) {
        // 折叠视图：**一个语言组 = 一张卡**（参考通知卡片的做法：整卡可点、卡内折叠预览）。
        // 单成员的组不做折叠，直接平铺成普通模型卡，省掉一次无意义点击。
        grouped.forEach { group ->
            if (!group.foldable) {
                val model = group.variants.first()
                item(key = "model:${model.id}") {
                    ModelCard(
                        category = category,
                        model = model,
                        downloadState = downloadState,
                        downloadingId = downloadingId,
                        selectedModelId = selectedModelId,
                    )
                }
                return@forEach
            }
            item(key = "group:${group.key}") {
                ModelGroupCard(
                    category = category,
                    group = group,
                    expanded = group.key in expandedKeys,
                    downloadState = downloadState,
                    downloadingId = downloadingId,
                    selectedModelId = selectedModelId,
                    onToggle = { onToggleGroup(group.key) },
                )
            }
        }
        return
    }

    // 平铺视图（语音等）
    // key 用 id：排序把「已下载」提前时，卡片状态（下载进度等）跟着条目一起移动
    items(items = models, key = { it.id }) { model ->
        ModelCard(
            category = category,
            model = model,
            downloadState = downloadState,
            downloadingId = downloadingId,
            selectedModelId = selectedModelId,
        )
    }
}

/**
 * 一个**语言变体分组卡**：标题行 + 卡内变体行（参考通知分组的做法）。
 *
 * 设计要点（上一版不好用的地方就在这里）：
 * - **整卡可点**，不再只点一个小箭头；展开时去掉按压反馈（同通知卡片 `showIndication`）；
 * - 收起时**卡内预览前几条变体**，用户不必展开就能看到有哪些变体、是否已下载；
 * - 展开后**就在同一张卡里**列全，不再散成一张张独立卡片；
 * - 变体行自带「使用 / 下载 / 删除」动作，点行内动作**不会**触发展开（事件被按钮消费）。
 */
@Composable
private fun ModelGroupCard(
    category: MarketCategory,
    group: MarketModelGroup,
    expanded: Boolean,
    downloadState: MarketDownloadState,
    downloadingId: String?,
    selectedModelId: String,
    onToggle: () -> Unit,
) {
    val context = LocalContext.current
    // 组内逐条 isDownloaded 都是磁盘遍历：随 revision 缓存，进度重组不反复扫盘
    val revision by category.revision.collectAsState()
    val downloaded = remember(revision, downloadingId) {
        group.variants.count { category.isDownloaded(context, it) }
    }
    val inUse = group.variants.any { it.id == selectedModelId }
    // 收起时预览的条数：正好让用户看清「最多 3 行」，多出来的在标题里报总数
    val preview = if (expanded) group.variants
    else group.variants.take(GROUP_COLLAPSED_PREVIEW)

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.surfaceContainerHighest,
        ),
        onClick = onToggle,
        showIndication = !expanded,
        pressFeedbackType = if (expanded) PressFeedbackType.None else PressFeedbackType.Sink,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(vertical = 12.dp)) {
                Text(
                    text = group.title,
                    color = if (inUse) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = buildList {
                        add(stringResource(R.string.market_group_variants, group.variants.size))
                        if (downloaded > 0) {
                            add(stringResource(R.string.market_group_downloaded, downloaded))
                        }
                    }.joinToString(" · "),
                    color = if (downloaded > 0) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Text(
                text = stringResource(
                    if (expanded) R.string.market_group_collapse else R.string.market_group_expand
                ),
                color = MiuixTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = if (expanded) MiuixIcons.ExpandLess else MiuixIcons.ExpandMore,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        // 卡内变体行：收起时预览，展开时全列；行间细分隔线（卡内分组分隔）
        preview.forEachIndexed { index, model ->
            if (index > 0) {
                HorizontalDivider(
                    modifier = Modifier.fillMaxWidth(),
                    thickness = 1.dp,
                    color = MiuixTheme.colorScheme.outline,
                )
            }
            ModelRow(
                category = category,
                model = model,
                downloadState = downloadState,
                downloadingId = downloadingId,
                selectedModelId = selectedModelId,
            )
        }
        if (!expanded && group.variants.size > preview.size) {
            Text(
                text = stringResource(
                    R.string.market_group_more,
                    group.variants.size - preview.size,
                ),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
        }
    }
}

/** 索引地址覆盖输入框（留空 = 用默认端点 index.ximei.me）。 */
@Composable
private fun ModelIndexField() {
    val prefs = AppPrefs.getInstance()
    var indexUrl by remember { mutableStateOf(prefs.voice.voiceIndexUrl.getValue()) }
    TextField(
        value = indexUrl,
        onValueChange = {
            indexUrl = it
            prefs.voice.voiceIndexUrl.setValue(it.trim())
        },
        label = stringResource(R.string.market_index_url),
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/**
 * 卡内**紧凑模型行**（分组卡内部用；不再自带 Card 外壳与左右外边距）。
 *
 * 动作与 [ModelCard] 完全一致（下载/取消/重试、使用、删除），只是排版更紧：
 * 名称与状态同一行、进度条压到最窄的两行里，避免展开一组后一屏放不下几条。
 */
@Composable
private fun ModelRow(
    category: MarketCategory,
    model: MarketModel,
    downloadState: MarketDownloadState,
    downloadingId: String?,
    selectedModelId: String,
) {
    val context = LocalContext.current
    // isDownloaded 是同步磁盘遍历：用 revision 缓存，下载进度触发的频繁重组不再反复扫盘
    val revision by category.revision.collectAsState()
    val downloaded = remember(revision, downloadingId, model.id) {
        category.isDownloaded(context, model)
    }
    val isTarget = downloadingId == model.id
    val inUse = model.id == selectedModelId
    val failed = isTarget && downloadState is MarketDownloadState.Error

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(text = model.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = listOfNotNull(
                    model.description.takeIf { it.isNotBlank() },
                    stringResource(
                        if (downloaded) R.string.market_model_state_ready
                        else R.string.market_model_state_missing
                    ),
                ).joinToString(" · "),
                color = if (downloaded) MiuixTheme.colorScheme.primary
                else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isTarget) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    progress = when (downloadState) {
                        is MarketDownloadState.Downloading -> downloadState.progress
                        is MarketDownloadState.Extracting -> downloadState.progress
                        else -> null
                    },
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = downloadStateLabel(downloadState),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                )
            }
        }
        when {
            // 未下载：只有下载/取消/重试，不能「使用」
            !downloaded -> TextButton(
                text = stringResource(
                    when {
                        failed -> R.string.market_model_retry_download
                        isTarget -> R.string.market_model_cancel_download
                        else -> R.string.market_model_download
                    }
                ),
                onClick = {
                    if (isTarget && !failed) category.cancelDownload()
                    else category.downloadModel(context, model)
                },
            )
            inUse -> Text(
                text = stringResource(R.string.market_model_in_use),
                color = MiuixTheme.colorScheme.primary,
            )
            else -> {
                IconButton(onClick = { category.deleteModel(context, model) }) {
                    Icon(
                        imageVector = MiuixIcons.Delete,
                        contentDescription = stringResource(R.string.market_model_delete),
                        modifier = Modifier.size(20.dp),
                    )
                }
                TextButton(
                    text = stringResource(R.string.market_model_select),
                    onClick = { category.selectModel(context, model) },
                )
            }
        }
    }
}

/** 下载进度文案（两种卡片共用，避免两处文案漂移）。 */
@Composable
private fun downloadStateLabel(state: MarketDownloadState): String = when (state) {
    is MarketDownloadState.Downloading ->
        if (state.totalBytes > 0) {
            "${state.bytesDownloaded} / ${state.totalBytes} (${(state.progress * 100).toInt()}%)"
        } else {
            "${(state.progress * 100).toInt()}%"
        }

    // bzip2 解压耗时且无下载字节可显示，单独给文案与百分比
    is MarketDownloadState.Extracting ->
        stringResource(R.string.voice_extracting) + " ${(state.progress * 100).toInt()}%"

    is MarketDownloadState.Error -> stringResource(R.string.market_download_failed)
    MarketDownloadState.Complete -> stringResource(R.string.market_model_download_ok)
    MarketDownloadState.Idle -> stringResource(R.string.market_downloading)
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
    // isDownloaded 是同步磁盘遍历：用 revision 缓存，进度条触发的频繁重组不反复扫盘
    val revision by category.revision.collectAsState()
    val downloaded = remember(revision, downloadingId, model.id) {
        category.isDownloaded(context, model)
    }
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
                            if (downloaded) R.string.market_model_state_ready
                            else R.string.market_model_state_missing
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
                        text = downloadStateLabel(downloadState),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            when {
                // 未下载：只有下载/取消/重试，不能「使用」
                !downloaded -> TextButton(
                    text = stringResource(
                        when {
                            failed -> R.string.market_model_retry_download
                            isTarget -> R.string.market_model_cancel_download
                            else -> R.string.market_model_download
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
                    text = stringResource(R.string.market_model_in_use),
                    color = MiuixTheme.colorScheme.primary,
                )
                // 已下载且未在用：删除 + 使用
                else -> {
                    IconButton(onClick = { category.deleteModel(context, model) }) {
                        Icon(
                            imageVector = MiuixIcons.Delete,
                            contentDescription = stringResource(R.string.market_model_delete),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    TextButton(
                        text = stringResource(R.string.market_model_select),
                        onClick = { category.selectModel(context, model) },
                    )
                }
            }
        }
    }
}
