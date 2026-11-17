/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.screens

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.ui.main.compose.dialog.SimpleConfirmDialog
import org.fcitx.fcitx5.android.ui.main.wanxiang.WanxiangExtraDictionaries
import org.fcitx.fcitx5.android.ui.main.wanxiang.WanxiangGithub
import org.fcitx.fcitx5.android.ui.main.wanxiang.WanxiangDownloadProgress
import org.fcitx.fcitx5.android.ui.main.wanxiang.WanxiangSelection
import org.fcitx.fcitx5.android.ui.main.wanxiang.WanxiangTarget
import org.fcitx.fcitx5.android.ui.main.wanxiang.WanxiangUpdateResult
import org.fcitx.fcitx5.android.ui.main.wanxiang.WanxiangUpdateWorker
import org.fcitx.fcitx5.android.ui.main.wanxiang.WanxiangUpdates
import timber.log.Timber
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.UUID

/**
 * 万象更新页。
 *
 * 选择更新范围与方案、辅助方案、发布通道、下载源，再附带词库与网络选项；
 * 提交后由后台任务下载并部署，页面只订阅其进度。
 */
@Composable
fun WanxiangUpdateScreen(
    onBack: () -> Unit,
    onOpenAdvanced: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connectionName = remember { "compose-wanxiang-${UUID.randomUUID()}" }
    val fcitx = remember { FcitxDaemon.connect(connectionName) }
    DisposableEffect(Unit) {
        onDispose { FcitxDaemon.disconnect(connectionName) }
    }

    val scopes = stringArrayResource(R.array.wanxiang_scopes)
    val schemes = stringArrayResource(R.array.wanxiang_schemes)
    val auxiliaries = stringArrayResource(R.array.wanxiang_auxiliaries)
    val channels = stringArrayResource(R.array.wanxiang_channels)
    val sources = stringArrayResource(R.array.wanxiang_sources)

    var scopeIndex by remember { mutableStateOf(WanxiangSelection.Scope.ALL.ordinal) }
    var schemeIndex by remember { mutableStateOf(WanxiangSelection.Scheme.BASE.ordinal) }
    var auxiliaryIndex by remember { mutableStateOf(0) }
    var channelIndex by remember { mutableStateOf(WanxiangSelection.Channel.STABLE.ordinal) }
    var sourceIndex by remember { mutableStateOf(WanxiangSelection.Source.CNB.ordinal) }
    var protection by remember { mutableStateOf(org.fcitx.fcitx5.android.data.WanxiangProtection.DEFAULT) }
    var extras by remember { mutableStateOf<List<String>>(emptyList()) }
    var tokenConfigured by remember { mutableStateOf(false) }

    var target by remember { mutableStateOf<WanxiangTarget?>(null) }
    var work by remember { mutableStateOf<WorkInfo?>(null) }
    var fullResult by remember { mutableStateOf<WanxiangUpdateResult?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var readingTarget by remember { mutableStateOf(true) }
    var localError by remember { mutableStateOf<String?>(null) }
    var localMessage by remember { mutableStateOf<String?>(null) }
    var confirmStart by remember { mutableStateOf(false) }
    var editingProtection by remember { mutableStateOf(false) }
    var protectionDraft by remember { mutableStateOf("") }
    var editingToken by remember { mutableStateOf(false) }
    var tokenDraft by remember { mutableStateOf("") }
    var choosingExtras by remember { mutableStateOf(false) }
    var refreshExtras by remember { mutableStateOf(false) }
    var extraCatalog by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var extrasDraft by remember { mutableStateOf<Set<String>>(emptySet()) }
    var extrasLoading by remember { mutableStateOf(false) }
    var extrasError by remember { mutableStateOf<String?>(null) }
    var openingAdvanced by remember { mutableStateOf(false) }

    // 附加词库只在选中相应范围时才有意义
    val scopeForExtras = WanxiangSelection.Scope.entries[scopeIndex] in
        setOf(WanxiangSelection.Scope.ALL, WanxiangSelection.Scope.EXTRA)

    fun selection() = WanxiangSelection(
        WanxiangSelection.Scheme.entries[schemeIndex],
        WanxiangSelection.auxiliaryCodes[auxiliaryIndex],
        WanxiangSelection.Channel.entries[channelIndex],
        WanxiangSelection.Source.entries[sourceIndex],
        WanxiangSelection.Scope.entries[scopeIndex],
        protection,
        extras,
    )

    suspend fun readTarget(): WanxiangTarget {
        val raw = withContext(Dispatchers.IO) {
            fcitx.runOnReady { getAddonSubConfig("rime", "profile-manager") }
        }
        return withContext(Dispatchers.IO) { WanxiangTarget.from(context, raw) }
    }

    fun refreshTarget() {
        readingTarget = true
        target = null
        localError = null
        localMessage = null
        scope.launch {
            try {
                target = readTarget()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                localError = e.message ?: e.javaClass.simpleName
            } finally {
                readingTarget = false
            }
        }
    }

    LaunchedEffect(Unit) { refreshTarget() }

    LaunchedEffect(Unit) {
        tokenConfigured = withContext(Dispatchers.IO) {
            runCatching { WanxiangGithub.configured(context) }.getOrDefault(false)
        }
    }

    LaunchedEffect(Unit) {
        WanxiangUpdates.observe(context).collect { list ->
            val next = list.firstOrNull { !it.state.isFinished } ?: list.lastOrNull()
            work = next
            val finished = next?.takeIf {
                it.state == WorkInfo.State.SUCCEEDED || it.state == WorkInfo.State.FAILED
            }
            fullResult = if (finished == null) null else withContext(Dispatchers.IO) {
                runCatching {
                    WanxiangUpdateResult.read(
                        WanxiangUpdates.directory(context, finished.id),
                        completed = finished.state == WorkInfo.State.SUCCEEDED,
                        legacyTarget = finished.outputData.getString(WanxiangUpdateWorker.TARGET),
                    )
                }.getOrNull()
            }
        }
    }

    val active = work?.takeIf { !it.state.isFinished }
    val running = active?.state == WorkInfo.State.RUNNING
    val downloading = running && active?.progress?.getString(WanxiangUpdateWorker.PHASE) == "download"
    val installing = running && active?.progress?.getString(WanxiangUpdateWorker.PHASE) == "install"
    val busy = submitting || readingTarget || active != null

    PageScaffold(title = stringResource(R.string.wanxiang_update), onBack = onBack) {
        item {
            SmallTitle(text = stringResource(R.string.wanxiang_scope))
        }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                // 每个选项点一次切到下一个取值，避免为单一枚举引入额外的选择器页面。
                val fields = listOf(
                    Triple(stringResource(R.string.wanxiang_scope), scopes, scopeIndex) to { next: Int -> scopeIndex = next },
                    Triple(stringResource(R.string.wanxiang_scheme), schemes, schemeIndex) to { next: Int -> schemeIndex = next },
                    Triple(stringResource(R.string.wanxiang_auxiliary), auxiliaries, auxiliaryIndex) to { next: Int -> auxiliaryIndex = next },
                    Triple(stringResource(R.string.wanxiang_channel), channels, channelIndex) to { next: Int -> channelIndex = next },
                    Triple(stringResource(R.string.wanxiang_source), sources, sourceIndex) to { next: Int -> sourceIndex = next },
                )
                fields.forEach { (field, assign) ->
                    val (label, items, selected) = field
                    ArrowPreference(
                        title = label,
                        summary = items.getOrNull(selected) ?: "",
                        onClick = {
                            if (busy) return@ArrowPreference
                            assign((selected + 1) % items.size)
                        },
                    )
                }
            }
        }

        item {
            SmallTitle(text = stringResource(R.string.wanxiang_extra))
        }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                ArrowPreference(
                    title = stringResource(R.string.wanxiang_extra),
                    summary = stringResource(R.string.wanxiang_extra_selected, extras.size),
                    enabled = !busy && scopeForExtras,
                    onClick = { choosingExtras = true },
                )
                ArrowPreference(
                    title = stringResource(R.string.wanxiang_extra_refresh),
                    enabled = !busy,
                    onClick = { choosingExtras = true; refreshExtras = true },
                )
                Text(
                    text = stringResource(R.string.wanxiang_extra_hint),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        item {
            SmallTitle(text = stringResource(R.string.wanxiang_protection))
        }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                ArrowPreference(
                    title = stringResource(R.string.wanxiang_protection),
                    enabled = !busy,
                    onClick = { editingProtection = true },
                )
                Text(
                    text = stringResource(R.string.wanxiang_protection_hint),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        item {
            SmallTitle(text = stringResource(R.string.wanxiang_network_options))
        }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                ArrowPreference(
                    title = stringResource(R.string.wanxiang_token),
                    summary = stringResource(
                        if (tokenConfigured) R.string.wanxiang_token_set
                        else R.string.wanxiang_token_unset,
                    ),
                    enabled = !busy,
                    onClick = { editingToken = true },
                )
                Text(
                    text = stringResource(R.string.wanxiang_token_hint),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        item {
            SmallTitle(text = stringResource(R.string.ui_operation_status))
        }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                Text(
                    text = when {
                        localError != null -> context.getString(R.string.wanxiang_failed) +
                            "\n" + localError
                        submitting -> context.getString(R.string.wanxiang_preparing)
                        active != null -> {
                            val title = if (running) {
                                active.progress.getString(WanxiangUpdateWorker.TITLE)
                                    ?: context.getString(R.string.wanxiang_preparing)
                            } else context.getString(R.string.wanxiang_waiting)
                            val bytes = active.progress.getLong(WanxiangUpdateWorker.BYTES, 0)
                            val total = active.progress.getLong(WanxiangUpdateWorker.TOTAL, 0)
                            val percent = if (downloading) {
                                WanxiangDownloadProgress.percent(bytes, total)
                            } else null
                            buildString {
                                append(title)
                                val name = active.progress.getString(WanxiangUpdateWorker.NAME)
                                if (running && !name.isNullOrEmpty()) {
                                    append("\n"); append(name)
                                }
                                if (downloading) {
                                    append("\n")
                                    append(Formatter.formatFileSize(context, bytes.coerceAtLeast(0)))
                                    if (percent != null) {
                                        append(" / "); append(Formatter.formatFileSize(context, total))
                                    }
                                }
                            }
                        }
                        localMessage != null -> localMessage!!
                        readingTarget -> context.getString(R.string.wanxiang_loading_directory)
                        work?.state == WorkInfo.State.SUCCEEDED &&
                            work?.outputData?.getBoolean(WanxiangUpdateWorker.NO_CHANGES, false) == true ->
                            context.getString(R.string.wanxiang_nothing_changed)
                        work?.state == WorkInfo.State.SUCCEEDED ->
                            context.getString(R.string.wanxiang_finished)
                        work?.state == WorkInfo.State.FAILED ->
                            context.getString(R.string.wanxiang_failed) + "\n" +
                                (fullResult?.error
                                    ?: work?.outputData?.getString(WanxiangUpdateWorker.ERROR).orEmpty())
                        work?.state == WorkInfo.State.CANCELLED ->
                            context.getString(R.string.ui_operation_cancelled)
                        else -> context.getString(R.string.wanxiang_ready) + "\n" +
                            target?.let { context.getString(R.string.wanxiang_current_target, it.profile) }.orEmpty()
                    },
                    modifier = Modifier.padding(16.dp),
                    color = if (localError != null) MiuixTheme.colorScheme.error
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        item {
            Card(
                modifier = Modifier.padding(12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                ArrowPreference(
                    title = stringResource(R.string.wanxiang_update),
                    enabled = !busy && target != null,
                    onClick = { confirmStart = true },
                )
                if (active != null && !submitting && !installing) {
                    ArrowPreference(
                        title = stringResource(android.R.string.cancel),
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                WorkManager.getInstance(context)
                                    .cancelUniqueWork(WanxiangUpdates.WORK).result.get()
                            }
                        },
                    )
                }
                ArrowPreference(
                    title = stringResource(R.string.wanxiang_retry_directory),
                    enabled = !busy,
                    onClick = { refreshTarget() },
                )
            }
        }
    }

    if (confirmStart) {
        val expected = target
        SimpleConfirmDialog(
            title = stringResource(R.string.wanxiang_update),
            message = expected?.let {
                context.getString(R.string.wanxiang_current_target, it.profile) + "\n" + it.path
            }.orEmpty(),
            onConfirm = {
                confirmStart = false
                if (expected == null || submitting || active != null) return@SimpleConfirmDialog
                val chosen = selection()
                submitting = true
                localError = null
                localMessage = null
                scope.launch {
                    try {
                        val actual = readTarget()
                        target = actual
                        require(actual == expected) {
                            context.getString(R.string.wanxiang_target_changed)
                        }
                        WanxiangUpdates.enqueue(context, chosen, actual)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.w(e, "万象更新提交失败")
                        localError = e.message ?: e.javaClass.simpleName
                    } finally {
                        submitting = false
                    }
                }
            },
            onDismiss = { confirmStart = false },
        )
    }

    // 保护规则：保存前先按同一套校验解析，非法内容留在对话框内提示
    if (editingProtection) {
        WindowDialog(
            show = true,
            title = stringResource(R.string.wanxiang_protection),
            summary = stringResource(R.string.wanxiang_protection_rules),
            onDismissRequest = { editingProtection = false },
        ) {
            Column {
                TextField(
                    value = protectionDraft,
                    onValueChange = { protectionDraft = it },
                    label = stringResource(R.string.wanxiang_protection_rules),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(
                        text = stringResource(R.string.wanxiang_protection_default),
                        onClick = {
                            protectionDraft = org.fcitx.fcitx5.android.data.WanxiangProtection.DEFAULT
                        },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = stringResource(android.R.string.cancel),
                        onClick = { editingProtection = false },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = stringResource(android.R.string.ok),
                        onClick = {
                            runCatching { org.fcitx.fcitx5.android.data.WanxiangProtection(protectionDraft) }
                                .onSuccess {
                                    protection = protectionDraft
                                    editingProtection = false
                                }
                                .onFailure { localError = it.message }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }

    // 访问令牌：留空保存等同于清除
    if (editingToken) {
        WindowDialog(
            show = true,
            title = stringResource(R.string.wanxiang_token),
            summary = stringResource(R.string.wanxiang_token_hint),
            onDismissRequest = { editingToken = false },
        ) {
            Column {
                TextField(
                    value = tokenDraft,
                    onValueChange = { tokenDraft = it },
                    label = stringResource(R.string.wanxiang_token),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(
                        text = stringResource(R.string.wanxiang_token_clear),
                        onClick = { tokenDraft = "" },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = stringResource(android.R.string.cancel),
                        onClick = { editingToken = false },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = stringResource(android.R.string.ok),
                        onClick = {
                            scope.launch {
                                val saved = withContext(Dispatchers.IO) {
                                    runCatching {
                                        WanxiangGithub.save(context, WanxiangGithub.normalize(tokenDraft))
                                        WanxiangGithub.configured(context)
                                    }.getOrElse { failure ->
                                        localError = failure.message
                                        false
                                    }
                                }
                                tokenConfigured = saved
                                editingToken = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }

    // 附加词库：进入时按需拉取清单，勾选后整体替换选择
    if (choosingExtras) {
        LaunchedEffect(refreshExtras) {
            extrasLoading = true
            extrasError = null
            extrasDraft = extras.toSet()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val cached = WanxiangExtraDictionaries.cached(context)
                    if (refreshExtras || cached.isEmpty())
                        WanxiangExtraDictionaries.refresh(context) else cached
                }
            }
            result.onSuccess { catalog ->
                extraCatalog = catalog.map { it.id to it.name }
            }.onFailure { failure ->
                extrasError = failure.message ?: failure.javaClass.simpleName
            }
            extrasLoading = false
            refreshExtras = false
        }
        WindowDialog(
            show = true,
            title = stringResource(R.string.wanxiang_extra),
            onDismissRequest = { choosingExtras = false },
        ) {
            Column {
                when {
                    extrasLoading -> Text(
                        text = stringResource(R.string.wanxiang_extra_loading),
                        modifier = Modifier.padding(16.dp),
                    )
                    extrasError != null -> Text(
                        text = extrasError!!,
                        modifier = Modifier.padding(16.dp),
                        color = MiuixTheme.colorScheme.error,
                    )
                    extraCatalog.isEmpty() -> Text(
                        text = stringResource(R.string.scheme_update_empty),
                        modifier = Modifier.padding(16.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    else -> extraCatalog.forEach { (id, name) ->
                        SwitchPreference(
                            title = name,
                            checked = id in extrasDraft,
                            onCheckedChange = { checked ->
                                extrasDraft = if (checked) extrasDraft + id else extrasDraft - id
                            },
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(
                        text = stringResource(android.R.string.cancel),
                        onClick = { choosingExtras = false },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = stringResource(android.R.string.ok),
                        onClick = {
                            extras = extraCatalog.filter { it.first in extrasDraft }.map { it.first }
                            choosingExtras = false
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }

    // 高级设置子页仍由原有的 View 页面承载
    if (openingAdvanced) {
        LaunchedEffect(Unit) {
            onOpenAdvanced()
        }
    }}