/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.stats.InputStats
import org.fcitx.fcitx5.android.data.stats.StatsRange
import org.fcitx.fcitx5.android.data.stats.StatsSnapshot
import org.fcitx.fcitx5.android.data.stats.StatsTotals
import org.fcitx.fcitx5.android.ui.main.stats.StatsChartView
import org.fcitx.fcitx5.android.ui.main.stats.WordCloudView
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.NumberFormat

/** 统计周期。 */
private enum class StatsPeriod(val titleRes: Int) {
    Today(R.string.stats_today),
    Week(R.string.stats_week),
    Month(R.string.stats_month),
    Year(R.string.stats_year),
    All(R.string.stats_all);

    fun range(): StatsRange = when (this) {
        Today -> StatsRange.today()
        Week -> StatsRange.week()
        Month -> StatsRange.month()
        Year -> StatsRange.year()
        All -> StatsRange.all()
    }
}

/**
 * 输入统计页。
 *
 * 汇总打字量、速度、按键与词频；可切换周期与输入方案，并可开关录制。
 * 词云与分布图沿用已有的 View 实现。
 */
@Composable
fun InputStatsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { InputStats.get(context) }

    var period by remember { mutableStateOf(StatsPeriod.All) }
    var schemaId by remember { mutableStateOf<String?>(null) }
    var snapshot by remember { mutableStateOf<StatsSnapshot?>(null) }
    var settings by remember { mutableStateOf(store.settings.value) }
    var loading by remember { mutableStateOf(false) }

    suspend fun reload() {
        loading = true
        try {
            snapshot = store.snapshot(period.range(), schemaId)
        } catch (e: Exception) {
            Timber.w(e, "读取输入统计失败")
            context.toast(R.string.stats_failed)
        } finally {
            loading = false
        }
    }

    LaunchedEffect(period, schemaId) { reload() }

    // 录制状态由数据层推送，页面跟随
    LaunchedEffect(Unit) {
        store.settings.collect { settings = it }
    }

    val current = snapshot
    val totals = current?.totals ?: StatsTotals()

    PageScaffold(title = stringResource(R.string.stats_title), onBack = onBack) {
        item { SmallTitle(text = stringResource(R.string.stats_overview)) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                // 周期与方案各选一个，点一次切到下一个取值
                ArrowPreference(
                    title = stringResource(R.string.stats_career),
                    summary = stringResource(period.titleRes),
                    onClick = {
                        val values = StatsPeriod.entries
                        period = values[(values.indexOf(period) + 1) % values.size]
                    },
                )
                val schemas = current?.schemas.orEmpty()
                if (schemas.isNotEmpty()) {
                    ArrowPreference(
                        title = stringResource(R.string.stats_all_schemas),
                        summary = schemas.firstOrNull { it.id == schemaId }?.name
                            ?: stringResource(R.string.stats_all_schemas),
                        onClick = {
                            val ids = listOf<String?>(null) + schemas.map { it.id }
                            schemaId = ids[(ids.indexOf(schemaId) + 1).coerceAtLeast(0) % ids.size]
                        },
                    )
                }
            }
        }

        item { SmallTitle(text = stringResource(R.string.stats_recording_settings)) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                SwitchPreference(
                    title = stringResource(R.string.stats_record),
                    checked = settings.enabled,
                    enabled = settings.loaded && !loading,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            store.setEnabled(enabled)
                            reload()
                        }
                    },
                )
                SwitchPreference(
                    title = stringResource(R.string.stats_words_record),
                    checked = settings.wordFrequencyEnabled,
                    enabled = settings.loaded && !loading,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            store.setWordFrequencyEnabled(enabled)
                            reload()
                        }
                    },
                )
                Text(
                    text = stringResource(R.string.stats_privacy),
                    modifier = Modifier.padding(16.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        if (settings.error != null || !settings.enabled || totals == StatsTotals()) {
            item {
                Card(
                    modifier = Modifier.padding(12.dp),
                    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
                ) {
                    Text(
                        text = when {
                            settings.error != null -> stringResource(R.string.stats_failed)
                            !settings.enabled -> stringResource(R.string.stats_disabled)
                            else -> stringResource(R.string.stats_empty)
                        },
                        modifier = Modifier.padding(16.dp),
                        color = if (settings.error != null) MiuixTheme.colorScheme.error
                        else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        item { SmallTitle(text = stringResource(R.string.stats_overview)) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                StatRow(stringResource(R.string.stats_commits), count(totals.commits))
                StatRow(stringResource(R.string.stats_characters), count(totals.characters))
                StatRow(stringResource(R.string.stats_keys), count(totals.keys))
                StatRow(stringResource(R.string.stats_corrections), percent(totals.deletionRatio))
                StatRow(stringResource(R.string.stats_peak), decimal(totals.peakCpm) + " CPM")
                StatRow(stringResource(R.string.stats_average), decimal(totals.averageCpm) + " CPM")
            }
        }

        if (totals.speedCharacters > 0) {
            item { SmallTitle(text = stringResource(R.string.stats_word_lengths)) }
            item {
                Card(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
                ) {
                    AndroidView(
                        factory = { ctx ->
                            StatsChartView(ctx).also { chart ->
                                chart.emptyText = ctx.getString(R.string.stats_empty)
                            }
                        },
                        update = { chart ->
                            current?.let { snap ->
                                chart.setDistribution(
                                    snap.wordLengths.toSortedMap().mapKeys { it.key.toString() },
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(180.dp).padding(8.dp),
                    )
                }
            }
        }

        if (current != null && current.words.isNotEmpty()) {
            item { SmallTitle(text = stringResource(R.string.stats_cloud_mixed)) }
            item {
                Card(
                    modifier = Modifier.padding(12.dp),
                    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
                ) {
                    AndroidView(
                        factory = { ctx ->
                            WordCloudView(ctx).also { cloud ->
                                cloud.emptyText = ctx.getString(R.string.stats_cloud_empty)
                                cloud.accessibilityTitle = ctx.getString(R.string.stats_cloud_mixed)
                            }
                        },
                        update = { cloud -> cloud.setWords(current.words) },
                        modifier = Modifier.fillMaxWidth().height(200.dp).padding(8.dp),
                    )
                }
            }
        }

        item {
            Text(
                text = stringResource(R.string.stats_scope_hint),
                modifier = Modifier.padding(16.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/** 一行指标：左侧名称、右侧数值。 */
@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(text = value)
    }
}

private fun count(value: Long): String = NumberFormat.getNumberInstance().format(value)

private fun decimal(value: Double): String =
    String.format(java.util.Locale.getDefault(), "%.1f", value)

private fun percent(value: Double): String =
    NumberFormat.getPercentInstance().apply { maximumFractionDigits = 1 }.format(value)
