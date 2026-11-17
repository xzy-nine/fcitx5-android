/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.AppDirectoryBrowser
import org.fcitx.fcitx5.android.ui.main.compose.dialog.SimpleConfirmDialog
import org.fcitx.fcitx5.android.utils.ExternalRimeStorage
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.io.File
import java.util.zip.ZipFile

/** 单个 ZIP 归档的大小上限。 */
private const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024

/** 导入状态。 */
private enum class ImportState { Idle, Working, Success, Error }

/**
 * 导入 Rime 方案 ZIP。
 *
 * 流程：选择归档 → 复制到缓存并校验根方案文件 → 选择目标目录（应用内或外置存储）
 * → 交给引擎写入。外置目录需要相应的存储访问权限，缺失时回退到应用内目录。
 */
@Composable
fun RimeImportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fcitx = remember { FcitxDaemon.connect("compose-rime-import") }

    var state by remember { mutableStateOf(ImportState.Idle) }
    var statusTitle by remember { mutableStateOf("") }
    var statusDetails by remember { mutableStateOf("") }
    var pendingArchive by remember { mutableStateOf<File?>(null) }
    var pendingRelative by remember { mutableStateOf<String?>(null) }
    var pendingExternal by remember { mutableStateOf<String?>(null) }
    var showDestinationChoice by remember { mutableStateOf(false) }
    var showDirectoryPicker by remember { mutableStateOf(false) }
    var fallbackMessage by remember { mutableStateOf<String?>(null) }
    var pickerCurrent by remember { mutableStateOf<File?>(null) }

    fun report(title: Int, newState: ImportState = ImportState.Idle, details: String = "") {
        statusTitle = context.getString(title)
        statusDetails = details
        state = newState
    }

    fun hasExternalAccess(): Boolean =
        when (ExternalRimeStorage.accessForSdk(Build.VERSION.SDK_INT)) {
            ExternalRimeStorage.Access.LegacyPermission ->
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            ExternalRimeStorage.Access.Unsupported -> false
            ExternalRimeStorage.Access.AllFilesPermission ->
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
        }

    fun importArchive(archive: File, destination: String, relative: String?) {
        val target = context.getString(
            R.string.ui_import_destination,
            destination.ifBlank { relative ?: context.getString(R.string.direct_rime_app_directory) }
        )
        report(R.string.direct_rime_importing, ImportState.Working, target)
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    fcitx.runOnReady {
                        setAddonSubConfig(
                            "rime",
                            "import-profile",
                            RawConfig(arrayOf(
                                RawConfig("Archive", archive.absolutePath),
                                RawConfig("Destination", destination),
                            )),
                        )
                    }
                }
                if (relative != null) {
                    org.fcitx.fcitx5.android.data.prefs.AppPrefs.getInstance()
                        .internal.lastShareReceiveDirectory.setValue(relative)
                    org.fcitx.fcitx5.android.data.prefs.AppPrefs.getInstance()
                        .internal.lastShareReceiveDirectoryRemembered.setValue(true)
                }
                report(R.string.direct_rime_imported, ImportState.Success, target)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Rime 方案导入失败")
                report(
                    R.string.ui_operation_failed,
                    ImportState.Error,
                    context.getString(R.string.direct_rime_import_failed, e.message.orEmpty()) + "\n" + target,
                )
            } finally {
                withContext(Dispatchers.IO) { archive.delete() }
                pendingArchive = null
            }
        }
    }

    fun cancelPending() {
        pendingArchive?.let { archive -> scope.launch(Dispatchers.IO) { archive.delete() } }
        pendingArchive = null
        pendingRelative = null
        pendingExternal = null
        report(R.string.direct_rime_import_cancelled)
    }

    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        report(R.string.direct_rime_reading_archive, ImportState.Working)
        scope.launch {
            var archive: File? = null
            try {
                val folder = File(context.cacheDir, "rime-import").apply {
                    check(isDirectory || mkdirs()) { "temp directory unavailable" }
                }
                val copied = File.createTempFile("profile-", ".zip", folder)
                archive = copied
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        copied.outputStream().use { output ->
                            val buffer = ByteArray(32 * 1024)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= MAX_ARCHIVE_BYTES) {
                                    context.getString(R.string.direct_rime_archive_too_large)
                                }
                                output.write(buffer, 0, count)
                            }
                        }
                    } ?: error("archive unreadable")
                }
                report(R.string.direct_rime_validating_archive, ImportState.Working)
                withContext(Dispatchers.IO) { validateRimeArchive(copied, context) }
                pendingArchive = copied
                showDestinationChoice = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                archive?.delete()
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.IO) { archive?.delete() }
                pendingArchive = null
                report(
                    R.string.ui_operation_failed,
                    ImportState.Error,
                    context.getString(R.string.direct_rime_import_failed, e.message.orEmpty()),
                )
            }
        }
    }

    val externalPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            cancelPending()
            return@rememberLauncherForActivityResult
        }
        val destination = runCatching {
            val documentId = DocumentsContract.getTreeDocumentId(uri)
            ExternalRimeStorage.absolutePath(
                uri.authority, documentId, Environment.getExternalStorageDirectory().absolutePath
            )?.let { File(it).canonicalPath }
        }.getOrNull()
        if (destination == null) {
            fallbackMessage = context.getString(R.string.addon_dir_profile_external_dir_invalid)
            return@rememberLauncherForActivityResult
        }
        pendingExternal = destination
        val archive = pendingArchive
        if (archive == null) {
            report(R.string.direct_rime_archive_missing, ImportState.Error)
            return@rememberLauncherForActivityResult
        }
        importArchive(archive, destination, null)
    }

    val allFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val destination = pendingExternal
        if (hasExternalAccess() && destination != null) {
            val archive = pendingArchive
            if (archive != null) importArchive(archive, destination, null)
        } else {
            fallbackMessage = context.getString(R.string.addon_dir_profile_all_files_permission_required)
        }
    }

    val legacyStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val destination = pendingExternal
        if (granted && destination != null) {
            val archive = pendingArchive
            if (archive != null) importArchive(archive, destination, null)
        } else if (!granted) {
            fallbackMessage = context.getString(R.string.addon_dir_profile_storage_permission_required)
        }
    }

    fun requestExternal() {
        when (ExternalRimeStorage.accessForSdk(Build.VERSION.SDK_INT)) {
            ExternalRimeStorage.Access.LegacyPermission -> {
                if (hasExternalAccess()) externalPicker.launch(null)
                else legacyStorageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            ExternalRimeStorage.Access.Unsupported ->
                fallbackMessage = context.getString(R.string.addon_dir_profile_android10_unsupported)
            ExternalRimeStorage.Access.AllFilesPermission -> {
                if (hasExternalAccess()) {
                    externalPicker.launch(null)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    )
                    runCatching { allFilesLauncher.launch(intent) }.onFailure {
                        runCatching {
                            allFilesLauncher.launch(
                                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                            )
                        }.onFailure { failure ->
                            fallbackMessage = failure.message
                                ?: context.getString(R.string.addon_dir_profile_all_files_permission_required)
                        }
                    }
                }
            }
        }
    }

    PageScaffold(title = stringResource(R.string.direct_rime_import), onBack = onBack) {
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                Text(
                    text = stringResource(R.string.direct_rime_import_description),
                    modifier = Modifier.padding(16.dp),
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
                    text = when (state) {
                        ImportState.Idle -> statusTitle.ifEmpty {
                            stringResource(R.string.direct_rime_choose_zip)
                        }
                        else -> statusTitle
                    },
                    modifier = Modifier.padding(16.dp),
                    color = if (state == ImportState.Error) MiuixTheme.colorScheme.error
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                if (statusDetails.isNotBlank()) {
                    Text(
                        text = statusDetails,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
        item {
            Card(
                modifier = Modifier.padding(12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                ArrowPreference(
                    title = stringResource(R.string.direct_rime_choose_zip),
                    enabled = pendingArchive == null && state != ImportState.Working,
                    onClick = {
                        zipPicker.launch(
                            arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed"),
                        )
                    },
                )
            }
        }
    }

    if (showDestinationChoice) {
        SimpleConfirmDialog(
            title = stringResource(R.string.direct_rime_choose_destination),
            message = stringResource(R.string.direct_rime_app_directory) + " / " +
                stringResource(R.string.direct_rime_external_directory),
            onConfirm = {
                showDestinationChoice = false
                pickerCurrent = AppDirectoryBrowser.root(context.getExternalFilesDir(null)!!)
                showDirectoryPicker = true
            },
            onDismiss = {
                showDestinationChoice = false
                cancelPending()
            },
        )
    }

    if (showDirectoryPicker) {
        val appRoot = remember {
            AppDirectoryBrowser.root(context.getExternalFilesDir(null)!!)
        }
        val current = pickerCurrent ?: appRoot
        val entries = remember(current) { AppDirectoryBrowser.entries(appRoot, current) }
        // 目录逐级浏览：点目录进入下一级，确认则把归档导入当前目录。
        WindowDialog(
            show = true,
            title = stringResource(R.string.share_receive_file_path_title),
            summary = AppDirectoryBrowser.displayPath(
                AppDirectoryBrowser.relativeOf(appRoot, current),
            ),
            onDismissRequest = {
                showDirectoryPicker = false
                cancelPending()
            },
        ) {
            Column {
                entries.forEach { entry ->
                    ArrowPreference(
                        title = entry.label,
                        onClick = { pickerCurrent = entry.directory },
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(
                        text = stringResource(android.R.string.cancel),
                        onClick = {
                            showDirectoryPicker = false
                            cancelPending()
                        },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = stringResource(R.string.import_),
                        onClick = {
                            showDirectoryPicker = false
                            val relative = AppDirectoryBrowser.relativeOf(appRoot, current)
                            val archive = pendingArchive
                            if (archive == null) {
                                report(R.string.direct_rime_archive_missing, ImportState.Error)
                            } else {
                                importArchive(archive, current.absolutePath, relative)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }

    fallbackMessage?.let { message ->
        SimpleConfirmDialog(
            title = stringResource(R.string.direct_rime_choose_destination),
            message = message,
            onConfirm = {
                fallbackMessage = null
                pickerCurrent = AppDirectoryBrowser.root(context.getExternalFilesDir(null)!!)
                showDirectoryPicker = true
            },
            onDismiss = {
                fallbackMessage = null
                cancelPending()
            },
        )
    }
}

/**
 * 校验归档是否为一份 Rime 方案：根层需含 `default.yaml` 或 `default.custom.yaml`。
 *
 * 同时拒绝绝对路径、反斜杠与 `..` 段，避免解压时越出目标目录。
 */
private fun validateRimeArchive(archive: File, context: android.content.Context) {
    val hasRootSchema = runCatching {
        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            var found = false
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val segments = entry.name.split('/').filter { it.isNotEmpty() && it != "." }
                val safe = !entry.name.startsWith('/') && '\\' !in entry.name &&
                    '\u0000' !in entry.name && segments.none { it == ".." || ':' in it }
                if (!entry.isDirectory && safe && segments.size == 1 &&
                    (segments.single() == "default.yaml" || segments.single() == "default.custom.yaml")
                ) {
                    found = true
                    break
                }
            }
            found
        }
    }.getOrDefault(false)
    require(hasRootSchema) { context.getString(R.string.direct_rime_archive_not_profile) }
}