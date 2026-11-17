/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.screens

import android.content.Intent
import android.content.Context
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.RimeSchemeTask
import org.fcitx.fcitx5.android.ui.main.compose.dialog.SimpleConfirmDialog
import org.fcitx.fcitx5.android.ui.main.rimeupdate.RimeSchemeRequest
import org.fcitx.fcitx5.android.ui.main.rimeupdate.RimeSchemeUpdateWorker
import org.fcitx.fcitx5.android.ui.main.rimeupdate.RimeSchemeUpdates
import org.fcitx.fcitx5.android.ui.main.wanxiang.WanxiangDownloadProgress
import org.fcitx.fcitx5.android.ui.main.wanxiang.WanxiangTarget
import timber.log.Timber
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.UUID

/**
 * Rime 方案更新页。
 *
 * 维护一组更新任务（远程链接或本地文件），勾选后下发一次批量更新。
 * 任务列表与执行进度分开：进度取自后台任务，重建页面不会重启下载。
 */
@Composable
fun RimeSchemeUpdateScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connectionName = remember { "compose-scheme-update-${UUID.randomUUID()}" }
    val fcitx = remember { FcitxDaemon.connect(connectionName) }
    DisposableEffect(Unit) {
        onDispose { FcitxDaemon.disconnect(connectionName) }
    }

    var tasks by remember { mutableStateOf<List<RimeSchemeTask>>(emptyList()) }
    var target by remember { mutableStateOf<WanxiangTarget?>(null) }
    var work by remember { mutableStateOf<WorkInfo?>(null) }
    var workTarget by remember { mutableStateOf<WanxiangTarget?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var readingTarget by remember { mutableStateOf(true) }
    var localError by remember { mutableStateOf<String?>(null) }
    var configError by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmRun by remember { mutableStateOf<List<RimeSchemeTask>?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<RimeSchemeTask?>(null) }
    var draftName by remember { mutableStateOf("") }
    var draftSource by remember { mutableStateOf("") }
    var draftFileName by remember { mutableStateOf("") }
    var draftUnpack by remember { mutableStateOf(true) }
    var draftStrip by remember { mutableStateOf(true) }
    var draftDestinations by remember { mutableStateOf("") }
    var draftExcludes by remember { mutableStateOf("") }
    var draftError by remember { mutableStateOf<String?>(null) }
    var pendingFileTask by remember { mutableStateOf<String?>(null) }

    fun loadTasks() {
        try {
            tasks = RimeSchemeUpdates.tasks(context)
            configError = null
        } catch (e: Exception) {
            configError = e.message ?: e.javaClass.simpleName
            tasks = emptyList()
        }
    }

    fun refreshTarget() {
        readingTarget = true
        target = null
        localError = null
        scope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    fcitx.runOnReady { getAddonSubConfig("rime", "profile-manager") }
                }
                target = withContext(Dispatchers.IO) { WanxiangTarget.from(context, raw) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                localError = e.message ?: e.javaClass.simpleName
            } finally {
                readingTarget = false
            }
        }
    }

    fun command(block: suspend () -> Unit) {
        if (submitting) return
        submitting = true
        localError = null
        message = null
        scope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "方案更新操作失败")
                localError = e.message ?: e.javaClass.simpleName
            } finally {
                submitting = false
            }
        }
    }

    fun save(next: List<RimeSchemeTask>) = command {
        try {
            withContext(Dispatchers.IO) { RimeSchemeUpdates.save(context, next) }
        } catch (e: Exception) {
            throw e
        }
        tasks = next
        configError = null
        message = context.getString(R.string.scheme_update_saved)
    }

    LaunchedEffect(Unit) {
        loadTasks()
        refreshTarget()
    }

    // 订阅后台任务：只跟随最新一条作业的进度
    LaunchedEffect(Unit) {
        RimeSchemeUpdates.observe(context).collect { list ->
            val next = list.firstOrNull { !it.state.isFinished } ?: list.lastOrNull()
            if (next?.id != work?.id) workTarget = null
            work = next
            if (next != null) {
                val encoded = next.progress.getString(RimeSchemeUpdateWorker.TARGET)
                    ?: next.outputData.getString(RimeSchemeUpdateWorker.TARGET)
                workTarget = encoded?.let {
                    runCatching {
                        kotlinx.serialization.json.Json.decodeFromString<WanxiangTarget>(it)
                    }.getOrNull()
                } ?: workTarget
            }
        }
    }

    val active = work?.state?.isFinished == false
    val canStart = target != null && configError == null && !readingTarget && !submitting && !active

    PageScaffold(title = stringResource(R.string.scheme_update_title), onBack = onBack) {
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                Text(
                    text = target?.profile ?: stringResource(R.string.wanxiang_loading_directory),
                    modifier = Modifier.padding(16.dp),
                )
                Text(
                    text = target?.path.orEmpty(),
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
                val error = configError ?: localError
                val statusText = when {
                    error != null -> context.getString(R.string.scheme_update_failed) + "\n" + error
                    submitting -> context.getString(R.string.scheme_update_preparing)
                    active && work != null -> buildString {
                        val phase = work!!.progress.getString(RimeSchemeUpdateWorker.PHASE)
                        append(
                            if (work!!.state == WorkInfo.State.ENQUEUED)
                                context.getString(R.string.scheme_update_waiting)
                            else context.getString(RimeSchemeUpdateWorker.phaseLabel(phase))
                        )
                        val name = work!!.progress.getString(RimeSchemeUpdateWorker.NAME).orEmpty()
                        val count = work!!.progress.getInt(RimeSchemeUpdateWorker.COUNT, 0)
                        val index = work!!.progress.getInt(RimeSchemeUpdateWorker.INDEX, 0)
                        if (name.isNotEmpty()) {
                            append("\n")
                            append(if (count > 0) "$index / $count · $name" else name)
                        }
                    }
                    readingTarget -> context.getString(R.string.wanxiang_loading_directory)
                    message != null -> message!!
                    work?.state == WorkInfo.State.SUCCEEDED ->
                        context.getString(R.string.scheme_update_done) + "\n" +
                            context.getString(
                                R.string.scheme_update_result,
                                work!!.outputData.getInt(RimeSchemeUpdateWorker.FILES, 0),
                            )
                    work?.state == WorkInfo.State.FAILED ->
                        context.getString(R.string.scheme_update_failed) + "\n" +
                            work!!.outputData.getString(RimeSchemeUpdateWorker.ERROR).orEmpty()
                    work?.state == WorkInfo.State.CANCELLED ->
                        context.getString(R.string.scheme_update_cancelled)
                    else -> context.getString(R.string.scheme_update_ready) + "\n" +
                        context.getString(R.string.scheme_update_scope)
                }
                Text(
                    text = statusText,
                    modifier = Modifier.padding(16.dp),
                    color = if (error != null) MiuixTheme.colorScheme.error
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        item {
            SmallTitle(text = stringResource(R.string.scheme_update_title))
        }
        if (tasks.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.scheme_update_empty),
                    modifier = Modifier.padding(16.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        } else {
            item {
                Card(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
                ) {
                    tasks.forEach { task ->
                        SwitchPreference(
                            title = task.name,
                            summary = task.source,
                            checked = task.selected,
                            enabled = canStart,
                            onCheckedChange = { checked ->
                                save(tasks.map {
                                    if (it.id == task.id) it.copy(selected = checked) else it
                                })
                            },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.scheme_update_edit),
                            enabled = canStart,
                            onClick = {
                                editingTask = task
                                draftName = task.name
                                draftSource = task.source
                                draftFileName = task.fileName
                                draftUnpack = task.unpack
                                draftStrip = task.stripRoot
                                draftDestinations = task.destinations.joinToString("\n") {
                                    it.ifEmpty { "." }
                                }
                                draftExcludes = task.excludes
                                draftError = null
                            },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.scheme_update_run_one),
                            enabled = canStart,
                            onClick = { confirmRun = listOf(task) },
                        )
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.padding(12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                ArrowPreference(
                    title = stringResource(R.string.scheme_update_add),
                    enabled = canStart,
                    onClick = {
                        val fresh = RimeSchemeTask()
                        editingTask = fresh
                        draftName = ""
                        draftSource = ""
                        draftFileName = ""
                        draftUnpack = true
                        draftStrip = true
                        draftDestinations = "."
                        draftExcludes = RimeSchemeTask.DEFAULT_EXCLUDES
                        draftError = null
                    },
                )
                ArrowPreference(
                    title = stringResource(R.string.scheme_update_run_selected),
                    enabled = canStart && tasks.any { it.selected },
                    onClick = { confirmRun = tasks.filter { it.selected } },
                )
                ArrowPreference(
                    title = stringResource(R.string.scheme_update_delete_selected),
                    enabled = canStart && tasks.any { it.selected },
                    onClick = { confirmDelete = true },
                )
                ArrowPreference(
                    title = stringResource(R.string.scheme_update_reset),
                    enabled = canStart,
                    onClick = { confirmReset = true },
                )
                if (active) {
                    ArrowPreference(
                        title = stringResource(android.R.string.cancel),
                        enabled = !submitting,
                        onClick = {
                            command {
                                withContext(Dispatchers.IO) {
                                    WorkManager.getInstance(context)
                                        .cancelUniqueWork(RimeSchemeUpdates.WORK).result.get()
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    confirmRun?.let { chosen ->
        val current = target
        if (current == null) {
            confirmRun = null
        } else {
            SimpleConfirmDialog(
                title = stringResource(R.string.scheme_update_title),
                message = context.getString(
                    R.string.scheme_update_confirm, chosen.size, current.profile, current.path,
                ),
                onConfirm = {
                    confirmRun = null
                    command {
                        RimeSchemeUpdates.enqueue(context, RimeSchemeRequest(current, chosen))
                    }
                },
                onDismiss = { confirmRun = null },
            )
        }
    }

    if (confirmDelete) {
        val ids = tasks.filter { it.selected }.map { it.id }.toSet()
        SimpleConfirmDialog(
            title = stringResource(R.string.scheme_update_delete_selected),
            message = context.getString(R.string.scheme_update_delete_confirm, ids.size),
            onConfirm = {
                confirmDelete = false
                save(tasks.filterNot { it.id in ids })
            },
            onDismiss = { confirmDelete = false },
        )
    }

    if (confirmReset) {
        SimpleConfirmDialog(
            title = stringResource(R.string.scheme_update_reset),
            message = stringResource(R.string.scheme_update_reset_hint),
            onConfirm = {
                confirmReset = false
                command {
                    withContext(Dispatchers.IO) { RimeSchemeUpdates.reset(context) }
                    loadTasks()
                }
            },
            onDismiss = { confirmReset = false },
        )
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                val name = withContext(Dispatchers.IO) {
                    context.contentResolver.query(
                        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
                    )?.use { if (it.moveToFirst()) it.getString(0) else null }
                }
                draftSource = uri.toString()
                draftFileName = name ?: RimeSchemeTask.suggestedFileName(uri.toString())
            }.onFailure { draftError = it.message }
        }
    }

    // 任务编辑：字段与保存时的校验顺序和引擎侧一致
    editingTask?.let { initial ->
        WindowDialog(
            show = true,
            title = stringResource(R.string.scheme_update_edit),
            onDismissRequest = { editingTask = null },
        ) {
            Column {
                TextField(
                    value = draftName,
                    onValueChange = { draftName = it },
                    label = stringResource(R.string.scheme_update_name),
                )
                TextField(
                    value = draftSource,
                    onValueChange = { v ->
                        draftSource = v
                        val next = RimeSchemeTask.suggestedFileName(v.trim())
                        if (draftFileName.isBlank()) draftFileName = next
                    },
                    label = stringResource(R.string.scheme_update_source),
                )
                TextButton(
                    text = stringResource(R.string.scheme_update_browse),
                    onClick = { filePicker.launch(arrayOf("*/*")) },
                )
                TextField(
                    value = draftFileName,
                    onValueChange = { draftFileName = it },
                    label = stringResource(R.string.scheme_update_filename),
                )
                SwitchPreference(
                    title = stringResource(R.string.scheme_update_unpack),
                    checked = draftUnpack,
                    onCheckedChange = { checked ->
                        draftUnpack = checked
                        if (!checked) draftStrip = false
                    },
                )
                SwitchPreference(
                    title = stringResource(R.string.scheme_update_strip),
                    checked = draftStrip,
                    enabled = draftUnpack,
                    onCheckedChange = { draftStrip = it },
                )
                TextField(
                    value = draftDestinations,
                    onValueChange = { draftDestinations = it },
                    label = stringResource(R.string.scheme_update_destinations),
                )
                TextField(
                    value = draftExcludes,
                    onValueChange = { draftExcludes = it },
                    label = stringResource(R.string.scheme_update_excludes),
                )
                draftError?.let { message ->
                    Text(
                        text = message,
                        modifier = Modifier.padding(8.dp),
                        color = MiuixTheme.colorScheme.error,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(
                        text = stringResource(android.R.string.cancel),
                        onClick = { editingTask = null },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = stringResource(R.string.scheme_update_save),
                        onClick = {
                            val next = initial.copy(
                                name = draftName.trim(),
                                source = draftSource.trim(),
                                fileName = draftFileName.trim(),
                                unpack = draftUnpack,
                                stripRoot = draftStrip,
                                destinations = draftDestinations.lines()
                                    .map(String::trim)
                                    .filter(String::isNotEmpty)
                                    .map { if (it == ".") "" else it.removeSuffix("/") }
                                    .ifEmpty { listOf("") },
                                excludes = draftExcludes,
                            )
                            val validated = runCatching { next.validated() }
                                .getOrElse { failure ->
                                    draftError = failure.message
                                    return@TextButton
                                }
                            val merged = if (tasks.any { it.id == validated.id }) {
                                tasks.map { if (it.id == validated.id) validated else it }
                            } else tasks + validated
                            editingTask = null
                            save(merged)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }}