/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: **模型市场**（父页）：只列出各分类的入口与摘要，点进去才是分类子页
 * （语音 `asr` / 谷歌数字墨水 `digitalink`）。
 *
 * 语音与手写设置页里的「模型」入口都指向分类子页，而不是各自再做一个市场页；
 * 本页是 HomeScreen「模型市场」入口的落点。
 */
package org.fcitx.fcitx5.android.ui.main.compose.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.market.MarketCategories
import org.fcitx.fcitx5.android.data.market.MarketCategory
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ModelMarketHomeScreen(
    onBack: () -> Unit,
    onOpenCategory: (MarketCategory) -> Unit,
) {
    PageScaffold(title = stringResource(R.string.voice_model_market), onBack = onBack) {
        item { SmallTitle(stringResource(R.string.market_categories)) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.surfaceContainerHighest,
                ),
            ) {
                MarketCategories.all.forEach { category ->
                    ModelCategoryEntry(category = category, onOpen = onOpenCategory)
                }
            }
        }
    }
}

/**
 * 一个分类的行：标题 + 「已下载 N / 共 M · 当前选中」摘要。
 *
 * 分类清单由各分类自己懒加载（这里读的是它当前的值，不主动发请求）；
 * 数字墨水清单是内置的，首次进父页就有数；语音首次为 0，进过子页后即有值。
 */
@Composable
private fun ModelCategoryEntry(
    category: MarketCategory,
    onOpen: (MarketCategory) -> Unit,
) {
    val context = LocalContext.current
    // 同步查询（downloadedCount / selectedModelId）不是 Compose State，靠 revision 驱动重组
    // 读 revision 即订阅重组（downloadedCount / selectedModelId 是同步查询、不是 State）
    val revision by category.revision.collectAsState()
    val models by category.models.collectAsState()
    val ready = remember(revision, models, category) { category.downloadedCount(context) }
    val selected = remember(revision, category) { category.selectedModelId(context) }
    val summary = buildList {
        add(stringResource(R.string.market_category_progress, ready, models.size))
        selected.takeIf { it.isNotBlank() }
            ?.let { add(stringResource(R.string.market_category_selected, it)) }
    }.joinToString(" · ")
    ArrowPreference(
        title = stringResource(category.titleRes),
        summary = summary,
        onClick = { onOpen(category) },
    )
}