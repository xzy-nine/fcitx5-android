/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
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
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.wanxiang.WanxiangAdvancedViewModel
import org.fcitx.fcitx5.android.ui.main.wanxiang.WanxiangValueEditor
import org.fcitx.fcitx5.android.data.WanxiangYaml
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 万象高级设置。
 *
 * 按文件与分组浏览配置项，逐项编辑后统一保存；也可在直连与补丁两种模式间切换。
 * 值编辑沿用多层级编辑器，主列表由 Compose 渲染。
 */
@Composable
fun WanxiangAdvancedScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember {
        ViewModelProvider(context as ViewModelStoreOwner)[WanxiangAdvancedViewModel::class.java]
    }
    val state by model.state.collectAsState()
    val yaml = remember { WanxiangYaml() }
    var editing by remember { mutableStateOf<WanxiangAdvancedViewModel.Row?>(null) }
    var fileExpanded by remember { mutableStateOf(false) }
    var groupExpanded by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        model.initialize(null)
        onDispose { model.pending = null }
    }

    val common = WanxiangAdvancedViewModel.COMMON

    PageScaffold(
        title = stringResource(R.string.wanxiang_advanced),
        onBack = onBack,
        floatingActionButton = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ArrowPreference(
                    title = stringResource(R.string.save),
                    enabled = !state.busy && !state.stale && state.dirty > 0,
                    modifier = Modifier.weight(1f),
                    onClick = { scope.launch { model.save() } },
                )
                ArrowPreference(
                    title = stringResource(R.string.wanxiang_advanced_reload),
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                    onClick = { model.reload() },
                )
            }
        },
    ) {
        item {
            Text(
                text = stringResource(R.string.wanxiang_advanced_scope_hint),
                modifier = Modifier.padding(16.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }

        // 当前目录
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                val target = state.target
                Text(
                    text = if (target == null) {
                        stringResource(R.string.wanxiang_loading_directory)
                    } else {
                        stringResource(R.string.wanxiang_directory) + "\n" + target.profile + "\n" + target.path
                    },
                    modifier = Modifier.padding(16.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        // 文件与分组选择
        item { SmallTitle(text = stringResource(R.string.wanxiang_advanced_section)) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                val files = (if ("default.yaml" in state.files &&
                    state.files.any { it in WanxiangAdvancedViewModel.MAIN_FILES }
                ) listOf(common) else emptyList()) + state.files
                val fileLabel = if (state.file == common) {
                    stringResource(R.string.wanxiang_advanced_common)
                } else state.file
                ArrowPreference(
                    title = fileLabel,
                    enabled = !state.busy,
                    onClick = { fileExpanded = !fileExpanded },
                )
                if (fileExpanded) {
                    files.forEach { name ->
                        ArrowPreference(
                            title = if (name == common) stringResource(R.string.wanxiang_advanced_common) else name,
                            onClick = {
                                fileExpanded = false
                                model.select(name)
                            },
                        )
                    }
                }
                if (state.groups.isNotEmpty()) {
                    Text(
                        text = state.group.ifBlank { stringResource(R.string.wanxiang_advanced_section) },
                        modifier = Modifier.padding(16.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    if (groupExpanded) {
                        state.groups.forEach { group ->
                            ArrowPreference(
                                title = group,
                                onClick = {
                                    groupExpanded = false
                                    model.select(state.file, group)
                                },
                            )
                        }
                    }
                }
            }
        }

        // 模式切换
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                SwitchPreference(
                    title = stringResource(R.string.wanxiang_advanced_mode),
                    summary = stringResource(
                        if (state.direct || state.forcedDirect) R.string.wanxiang_advanced_direct_summary
                        else R.string.wanxiang_advanced_patch_summary,
                    ),
                    checked = state.direct || state.forcedDirect,
                    enabled = !state.busy && state.dirty == 0 && !state.forcedDirect,
                    onCheckedChange = { model.mode(it) },
                )
            }
        }

        // 配置项
        item { SmallTitle(text = stringResource(R.string.wanxiang_advanced_options)) }
        state.rows.forEach { row ->
            item {
                val readOnly = row.path == "schema/schema_id"
                val enabled = !state.busy && !state.stale && !readOnly
                Card(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
                ) {
                    if (row.type == WanxiangYaml.Type.BOOLEAN) {
                        SwitchPreference(
                            title = row.key,
                            summary = row.help.ifBlank { null },
                            checked = runCatching { yaml.boolean(model.node(row)) }.getOrDefault(false),
                            enabled = enabled,
                            onCheckedChange = { checked ->
                                runCatching { model.edit(row, yaml.dump(yaml.scalar(checked))) }
                            },
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = enabled) { editing = row }
                                .padding(16.dp),
                        ) {
                            Text(text = row.key)
                            Text(
                                text = if (row.reset) {
                                    stringResource(R.string.wanxiang_advanced_pending_reset)
                                } else {
                                    runCatching { yaml.text(model.node(row)) }.getOrElse { "" }
                                },
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            if (row.help.isNotBlank()) {
                                Text(
                                    text = row.help,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                }
            }
        }

        // 导入开关
        if (state.file == common && state.rows.isNotEmpty()) {
            item {
                ArrowPreference(
                    title = stringResource(R.string.wanxiang_advanced_import_switches),
                    enabled = !state.stale,
                    modifier = Modifier.padding(12.dp),
                    onClick = { model.importSwitches() },
                )
            }
        }

        // 维护
        item { SmallTitle(text = stringResource(R.string.wanxiang_advanced_maintenance)) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                Text(
                    text = stringResource(R.string.wanxiang_advanced_repair_hint),
                    modifier = Modifier.padding(16.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                ArrowPreference(
                    title = stringResource(R.string.wanxiang_advanced_repair),
                    enabled = !state.busy && state.target != null,
                    onClick = { scope.launch { model.repairDeployment() } },
                )
            }
        }

        // 状态
        item {
            val status = when {
                state.error != null -> stringResource(R.string.ui_operation_failed) + "\n" + state.error.orEmpty()
                state.busy -> stringResource(
                    if (state.saving) R.string.wanxiang_advanced_saving else R.string.wanxiang_advanced_loading,
                )
                state.saved -> stringResource(R.string.wanxiang_advanced_saved)
                state.dirty > 0 -> stringResource(R.string.wanxiang_advanced_dirty, state.dirty)
                !state.compiled -> stringResource(R.string.wanxiang_advanced_compile_hint)
                else -> stringResource(R.string.wanxiang_advanced_ready)
            }
            Text(
                text = status,
                modifier = Modifier.padding(16.dp),
                color = if (state.error != null) MiuixTheme.colorScheme.error
                else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }

    // 值编辑
    editing?.let { row ->
        WanxiangValueEditorDialog(
            model = model,
            yaml = yaml,
            row = row,
            onDismiss = { editing = null },
        )
    }
}

/** 单行值编辑器：值本体由多层级编辑器承载，确认后写回。 */
@Composable
private fun WanxiangValueEditorDialog(
    model: WanxiangAdvancedViewModel,
    yaml: WanxiangYaml,
    row: WanxiangAdvancedViewModel.Row,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val editor = remember(row) {
        WanxiangValueEditor(context, yaml, model.node(row), null)
    }
    top.yukonga.miuix.kmp.window.WindowDialog(
        show = true,
        title = row.key,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { editor },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ArrowPreference(
                    title = stringResource(android.R.string.cancel),
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss,
                )
                ArrowPreference(
                    title = stringResource(android.R.string.ok),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        runCatching { model.edit(row, yaml.dump(editor.result())) }
                        onDismiss()
                    },
                )
            }
        }
    }
}
