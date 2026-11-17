/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.screens

import androidx.compose.foundation.layout.padding
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.engine.ImportedRimeEngine
import org.fcitx.fcitx5.android.core.engine.PluginRimeEngineAuthorization
import org.fcitx.fcitx5.android.core.engine.RimeEngineKind
import org.fcitx.fcitx5.android.core.engine.RimeEnginePlugins
import org.fcitx.fcitx5.android.core.engine.RimeEngineSelection
import org.fcitx.fcitx5.android.core.engine.RimeEngineState
import org.fcitx.fcitx5.android.core.engine.RimeEngineStore
import org.fcitx.fcitx5.android.data.RimeDataDirectoryState
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.ui.main.compose.dialog.SimpleConfirmDialog
import org.fcitx.fcitx5.android.ui.main.settings.im.RimeEngineActions
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.util.UUID

/**
 * Rime 引擎变体管理页。
 *
 * 列出内置引擎、已授权的插件引擎与手动导入的引擎，可切换当前使用、改 profile 与删除。
 * 切换后引擎需要重新部署，页面在操作完成后重新读取持久化状态。
 */
@Composable
fun RimeEngineManagerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connectionName = remember { "compose-rime-engine-" + UUID.randomUUID() }
    val fcitx = remember { FcitxDaemon.connect(connectionName) }
    val repository = remember { RimeEngineStore.repository(context) }

    var state by remember { mutableStateOf<RimeEngineState?>(null) }
    var directories by remember { mutableStateOf<RimeDataDirectoryState?>(null) }
    var plugins by remember { mutableStateOf<List<org.fcitx.fcitx5.android.core.engine.RimePluginEngineCandidate>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var pendingSwitch by remember { mutableStateOf<Pair<RimeEngineSelection, String>?>(null) }
    var pendingDelete by remember { mutableStateOf<ImportedRimeEngine?>(null) }
    var pendingRevoke by remember { mutableStateOf<PluginRimeEngineAuthorization?>(null) }
    // 导入管线：暂存 → 静态确认 → 探测 → 命名安装
    var stagedImport by remember { mutableStateOf<StagedEngine?>(null) }
    var probeTarget by remember { mutableStateOf<ProbeTarget?>(null) }
    var importing by remember { mutableStateOf(false) }
    val stagingToken = remember { UUID.randomUUID().toString() }
    var pendingProfileChoice by remember { mutableStateOf<ProfileChoice?>(null) }

    suspend fun reload() {
        try {
            val dirs = withContext(Dispatchers.IO) {
                fcitx.runOnReady {
                    RimeDataDirectoryState.fromConfig(
                        getAddonSubConfig("rime", "profile-manager"),
                        File(context.getExternalFilesDir(null), "data"),
                    )
                }
            }
            directories = dirs
            val loaded = withContext(Dispatchers.IO) {
                Triple(
                    repository.state(),
                    RimeEnginePlugins.discover(context),
                    Unit,
                )
            }
            state = loaded.first
            plugins = loaded.second
        } catch (e: Exception) {
            Timber.w(e, "读取 Rime 引擎状态失败")
            context.toast(R.string.ui_operation_failed)
        }
    }

    LaunchedEffect(Unit) {
        reload()
        // 回收不属于任何活跃会话的暂存目录
        withContext(Dispatchers.IO) {
            runCatching { RimeEngineActions.cleanupOrphaned(context, setOf(stagingToken)) }
        }
    }

    // 离开页面时清掉本次会话的暂存文件
    androidx.compose.runtime.DisposableEffect(stagingToken) {
        onDispose {
            runCatching {
                File(RimeEngineActions.importRoot(context), stagingToken).deleteRecursively()
                File(RimeEngineActions.probeRoot(context), stagingToken).deleteRecursively()
            }
        }
    }

    /** 执行一次仓库变更；需要时通知引擎重新部署。 */
    fun mutate(switchTarget: String?, block: () -> Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val applied = withContext(NonCancellable + Dispatchers.IO) {
                    block().also { shouldApply ->
                        if (shouldApply) {
                            fcitx.runOnReady { setAddonSubConfig("rime", "engine-variant-switch") }
                        }
                    }
                }
                if (applied && switchTarget != null) {
                    context.toast(context.getString(R.string.rime_engine_switched, switchTarget))
                }
            } catch (e: Exception) {
                Timber.w(e, "Rime 引擎操作失败")
                context.toast(e.message ?: context.getString(R.string.ui_operation_failed))
            } finally {
                busy = false
                reload()
            }
        }
    }

    // 选择 .so 文件后暂存并做静态检查
    val enginePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            try {
                val (file, info) = RimeEngineActions.stage(context, uri, stagingToken)
                stagedImport = StagedEngine(file, info, null)
            } catch (e: Exception) {
                Timber.w(e, "引擎文件暂存失败")
                context.toast(e.message ?: context.getString(R.string.ui_operation_failed))
            } finally {
                importing = false
            }
        }
    }

/** 就静态检查结果向用户请求探测授权。 */
    fun requestProbe(target: StagedEngine) {
        probeTarget = ProbeTarget(target.file, target.info, target.plugin)
    }

    /** 在隔离进程里探测引擎，成功后进入命名或授权确认。 */
    fun runProbe(target: ProbeTarget) {
        importing = true
        scope.launch {
            var snapshot: File? = null
            try {
                val executable = if (target.plugin == null) {
                    target.file
                } else {
                    RimeEngineActions.stageProbeCopy(
                        context, target.file, target.info, stagingToken,
                    ).also { snapshot = it }
                }
                val runtime = org.fcitx.fcitx5.android.core.engine.RimeEngineProbeService
                    .probe(context, executable, target.info)
                val profile = directories?.current.orEmpty()
                pendingProfileChoice = ProfileChoice(
                    StagedEngine(target.file, target.info, target.plugin),
                    runtime,
                    profile,
                )
            } catch (e: Exception) {
                Timber.w(e, "引擎探测失败")
                context.toast(e.message ?: context.getString(R.string.ui_operation_failed))
            } finally {
                snapshot?.delete()
                importing = false
            }
        }
    }

    val current = state

    PageScaffold(title = stringResource(R.string.rime_engine_variants_title), onBack = onBack) {
        item {
            Text(
                text = stringResource(R.string.rime_engine_variants_description),
                modifier = Modifier.padding(16.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }

        if (current == null) {
            item {
                Text(
                    text = stringResource(R.string.rime_engine_loading),
                    modifier = Modifier.padding(16.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        } else {
            item { SmallTitle(text = stringResource(R.string.rime_engine_builtin_section)) }
            item {
                val builtinActive = current.active == RimeEngineSelection.Builtin
                val builtinProfile = if (builtinActive) {
                    directories?.current.orEmpty()
                } else current.builtinProfile ?: directories?.current.orEmpty()
                EngineCard(
                    title = stringResource(R.string.rime_engine_builtin_name),
                    details = stringResource(R.string.rime_engine_builtin_details, builtinProfile),
                    active = builtinActive,
                ) {
                    if (!builtinActive) {
                        ArrowPreference(
                            title = stringResource(R.string.rime_engine_switch),
                            enabled = !busy,
                            onClick = {
                                pendingSwitch = RimeEngineSelection.Builtin to
                                    context.getString(R.string.rime_engine_builtin_name)
                            },
                        )
                    }
                }
            }

            item { SmallTitle(text = stringResource(R.string.rime_engine_plugin_section)) }
            if (plugins.isEmpty() && current.plugins.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.rime_engine_no_plugins),
                        modifier = Modifier.padding(16.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            plugins.forEach { candidate ->
                val authorization = current.plugins.firstOrNull {
                    it.packageName == candidate.packageName
                }?.takeIf {
                    it.certificateSha256 == candidate.certificateSha256 &&
                        it.payloadSha256 == candidate.staticInfo.sha256 &&
                        it.soname == candidate.soname
                }
                val selection = RimeEngineSelection(RimeEngineKind.PLUGIN, candidate.packageName)
                val active = current.active == selection
                val details = buildString {
                    append(candidate.declaredLibrimeVersion ?: authorization?.runtimeInfo?.librimeVersion ?: "librime")
                    candidate.channel?.let { append(" · ").append(it) }
                    append(" · ").append(candidate.staticInfo.abi)
                    append("\n").append(candidate.packageName)
                    append("\n").append(
                        if (authorization != null) {
                            context.getString(R.string.rime_engine_plugin_authorized, authorization.profile)
                        } else {
                            context.getString(R.string.rime_engine_plugin_needs_authorization)
                        },
                    )
                }
                item {
                    EngineCard(candidate.displayName, details, active) {
                        if (authorization == null) {
                            ArrowPreference(
                                title = stringResource(R.string.rime_engine_authorize),
                                enabled = !busy && !importing,
                                onClick = {
                                    requestProbe(
                                        StagedEngine(File(candidate.libraryPath), candidate.staticInfo, candidate),
                                    )
                                },
                            )
                        }
                        if (authorization != null && !active) {
                            ArrowPreference(
                                title = stringResource(R.string.rime_engine_switch),
                                enabled = !busy,
                                onClick = { pendingSwitch = selection to candidate.displayName },
                            )
                        }
                        if (authorization != null && !active) {
                            ArrowPreference(
                                title = stringResource(R.string.rime_engine_revoke),
                                enabled = !busy,
                                onClick = { pendingRevoke = authorization },
                            )
                        }
                    }
                }
            }
            current.plugins.filter { saved -> plugins.none { it.packageName == saved.packageName } }
                .forEach { missing ->
                    val selection = RimeEngineSelection(RimeEngineKind.PLUGIN, missing.packageName)
                    val active = current.active == selection
                    item {
                        EngineCard(
                            missing.displayName,
                            context.getString(R.string.rime_engine_plugin_missing, missing.packageName),
                            active,
                        ) {
                            if (!active) {
                                ArrowPreference(
                                    title = stringResource(R.string.rime_engine_revoke),
                                    enabled = !busy,
                                    onClick = { pendingRevoke = missing },
                                )
                            }
                        }
                    }
                }

            item { SmallTitle(text = stringResource(R.string.rime_engine_imported_section)) }
            item {
                Card(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
                ) {
                    ArrowPreference(
                        title = stringResource(R.string.rime_engine_import_so),
                        enabled = !importing && !busy,
                        onClick = {
                            enginePicker.launch(
                                arrayOf("application/octet-stream", "application/x-sharedlib", "*/*"),
                            )
                        },
                    )
                    if (importing) {
                        Text(
                            text = stringResource(R.string.rime_engine_reading),
                            modifier = Modifier.padding(16.dp),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
            if (current.imported.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.rime_engine_no_imported),
                        modifier = Modifier.padding(16.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            current.imported.forEach { record ->
                val selection = RimeEngineSelection(RimeEngineKind.IMPORTED, record.id)
                val active = current.active == selection
                val available = runCatching { repository.importedFile(record).isFile }.getOrDefault(false)
                val details = buildString {
                    append(record.runtimeInfo.librimeVersion).append(" · ").append(record.staticInfo.abi)
                    append("\n").append(context.getString(R.string.rime_engine_profile_value, record.profile))
                    append("\nSHA-256 ").append(record.staticInfo.sha256.take(16)).append('…')
                    if (!available) append("\n").append(context.getString(R.string.rime_engine_payload_missing))
                }
                item {
                    EngineCard(record.displayName, details, active) {
                        if (available && !active) {
                            ArrowPreference(
                                title = stringResource(R.string.rime_engine_switch),
                                enabled = !busy,
                                onClick = { pendingSwitch = selection to record.displayName },
                            )
                        }
                        if (!active) {
                            ArrowPreference(
                                title = stringResource(R.string.delete),
                                enabled = !busy,
                                onClick = { pendingDelete = record },
                            )
                        }
                    }
                }
            }
        }
    }

    // 一级授权：静态信息 + 探测风险提示
    stagedImport?.let { staged ->
        val detail = RimeEngineActions.staticDetails(context, staged.info, staged.plugin)
        SimpleConfirmDialog(
            title = stringResource(R.string.rime_engine_static_valid),
            message = detail + "\n\n" + stringResource(R.string.rime_engine_probe_warning),
            onConfirm = {
                stagedImport = null
                requestProbe(staged)
            },
            onDismiss = {
                stagedImport = null
                staged.file.delete()
            },
        )
    }

    probeTarget?.let { target ->
        runProbe(target)
        probeTarget = null
    }

    // 二级确认：探测结果与最终信任提示
    pendingProfileChoice?.let { choice ->
        val runtime = RimeEngineActions.runtimeDetails(context, choice.runtime)
        val profileLine = stringResource(R.string.rime_engine_profile_value, choice.profile)
        val plugin = choice.staged.plugin
        SimpleConfirmDialog(
            title = if (plugin == null) {
                stringResource(R.string.rime_engine_import_confirm_title)
            } else {
                stringResource(R.string.rime_engine_authorize_title, plugin.displayName)
            },
            message = buildString {
                append(runtime)
                append("\n").append(profileLine)
                if (plugin != null) {
                    append("\n\n").append(
                        context.getString(R.string.rime_engine_plugin_certificate, plugin.certificateSha256),
                    )
                }
                append("\n\n").append(context.getString(R.string.rime_engine_full_trust_warning))
            },
            onConfirm = {
                pendingProfileChoice = null
                busy = true
                scope.launch {
                    try {
                        if (plugin == null) {
                            RimeEngineActions.install(
                                context,
                                repository,
                                choice.staged.file,
                                choice.staged.info.sourceName.substringBeforeLast('.')
                                    .ifBlank { "librime " + choice.runtime.librimeVersion },
                                choice.staged.info,
                                choice.runtime,
                                choice.profile,
                            )
                            choice.staged.file.delete()
                            context.toast(context.getString(R.string.rime_engine_imported, choice.staged.info.sourceName))
                        } else {
                            RimeEngineActions.authorize(
                                context, repository, plugin, choice.runtime, choice.profile,
                            )
                            context.toast(
                                context.getString(R.string.rime_engine_authorized, plugin.displayName),
                            )
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "引擎导入或授权失败")
                        context.toast(e.message ?: context.getString(R.string.ui_operation_failed))
                    } finally {
                        busy = false
                        reload()
                    }
                }
            },
            onDismiss = {
                pendingProfileChoice = null
                if (plugin == null) choice.staged.file.delete()
            },
        )
    }

    pendingSwitch?.let { (selection, name) ->
        SimpleConfirmDialog(
            title = stringResource(R.string.rime_engine_switch_title, name),
            message = stringResource(R.string.rime_engine_switch_message),
            onConfirm = {
                pendingSwitch = null
                val currentProfile = directories?.current
                mutate(name) {
                    repository.select(selection, currentProfile)
                    true
                }
            },
            onDismiss = { pendingSwitch = null },
        )
    }

    pendingDelete?.let { record ->
        SimpleConfirmDialog(
            title = stringResource(R.string.rime_engine_delete_title),
            message = stringResource(
                R.string.rime_engine_delete_message,
                record.displayName,
                record.profile,
            ),
            onConfirm = {
                pendingDelete = null
                mutate(null) {
                    repository.deleteImported(record.id)
                    false
                }
            },
            onDismiss = { pendingDelete = null },
        )
    }

    pendingRevoke?.let { record ->
        SimpleConfirmDialog(
            title = stringResource(R.string.rime_engine_revoke_title),
            message = stringResource(R.string.rime_engine_revoke_message, record.displayName),
            onConfirm = {
                pendingRevoke = null
                mutate(null) {
                    repository.revokePlugin(record.packageName)
                    false
                }
            },
            onDismiss = { pendingRevoke = null },
        )
    }
}

/** 已暂存、待确认的引擎文件。 */
private data class StagedEngine(
    val file: File,
    val info: org.fcitx.fcitx5.android.core.engine.RimeEngineStaticInfo,
    val plugin: org.fcitx.fcitx5.android.core.engine.RimePluginEngineCandidate?,
)

/** 已通过静态检查、待用户授权探测的目标。 */
private data class ProbeTarget(
    val file: File,
    val info: org.fcitx.fcitx5.android.core.engine.RimeEngineStaticInfo,
    val plugin: org.fcitx.fcitx5.android.core.engine.RimePluginEngineCandidate?,
)

/** 探测成功后的落点：导入安装或插件授权。 */
private data class ProfileChoice(
    val staged: StagedEngine,
    val runtime: org.fcitx.fcitx5.android.core.engine.RimeEngineRuntimeInfo,
    val profile: String,
)

/** 单个引擎条目：标题、明细与操作区；当前使用的引擎用底色标出。 */
@Composable
private fun EngineCard(
    title: String,
    details: String,
    active: Boolean,
    actions: @Composable () -> Unit = {},
) {
    Card(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.defaultColors(
            color = if (active) MiuixTheme.colorScheme.secondaryContainer
            else MiuixTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        Text(text = title, modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp))
        Text(
            text = details,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 8.dp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        actions()
    }
}
