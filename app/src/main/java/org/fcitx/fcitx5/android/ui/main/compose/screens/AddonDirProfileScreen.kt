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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import top.yukonga.miuix.kmp.basic.Text
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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.RimeDataDirectoryState
import org.fcitx.fcitx5.android.ui.main.compose.dialog.SimpleConfirmDialog
import org.fcitx.fcitx5.android.utils.ExternalRimeStorage
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 一个 Rime 数据目录（profile）。 */
data class RimeProfileItem(val name: String, val active: Boolean, val path: String?)

/** 数据目录管理的页面状态。 */
private data class ProfileManagerState(
    val current: String = "",
    val profiles: List<String> = emptyList(),
    val paths: Map<String, String> = emptyMap(),
    val error: String = "",
)

/**
 * Rime 数据目录管理页。
 *
 * 列出全部 profile，可切换当前使用的目录、新建（含指定外部目录）、重命名与删除。
 * 所有改动都通过子配置的 `Action` 交给引擎执行，页面只负责收集意图与刷新。
 */
@Composable
fun AddonDirProfileScreen(
    title: String,
    addon: String,
    path: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fcitx = remember { FcitxDaemon.connect("compose-dir-profile") }
    DisposableEffect(Unit) {
        onDispose { FcitxDaemon.disconnect("compose-dir-profile") }
    }

    var state by remember { mutableStateOf(ProfileManagerState()) }
    var loading by remember { mutableStateOf(true) }
    var pendingCreate by remember { mutableStateOf(false) }
    var pendingExternalPath by remember { mutableStateOf<String?>(null) }
    var pendingRename by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var pendingNewName by remember { mutableStateOf("") }

    suspend fun refresh() {
        val loaded = runCatching {
            withContext(Dispatchers.IO) {
                fcitx.runOnReady {
                    val raw = getAddonSubConfig(addon, path)
                    val cfg = raw.findByName("cfg") ?: raw
                    val dirState = RimeDataDirectoryState.fromConfig(
                        raw, context.getExternalFilesDir(null)!!.resolve("data")
                    )
                    ProfileManagerState(
                        current = dirState.current,
                        profiles = dirState.profiles,
                        paths = dirState.paths,
                        error = cfg.findByName("Error")?.value.orEmpty(),
                    )
                }
            }
        }
        loaded.onSuccess { state = it }
            .onFailure { Timber.w(it, "读取数据目录状态失败") }
        loading = false
    }

    LaunchedEffect(addon, path) { refresh() }

    suspend fun runAction(
        action: String,
        name: String = "",
        newName: String = "",
        externalPath: String? = null,
    ) {
        val entries = buildList {
            add(RawConfig("Action", action))
            if (name.isNotBlank()) add(RawConfig("Name", name))
            if (newName.isNotBlank()) add(RawConfig("NewName", newName))
            if (!externalPath.isNullOrBlank()) add(RawConfig("ExternalPath", externalPath))
        }
        runCatching {
            withContext(Dispatchers.IO) {
                fcitx.runOnReady { setAddonSubConfig(addon, path, RawConfig(entries.toTypedArray())) }
            }
        }.onFailure {
            Timber.w(it, "数据目录操作失败: $action")
            context.toast(it)
            return
        }
        refresh()
    }

    fun hasExternalStorageAccess(): Boolean =
        when (ExternalRimeStorage.accessForSdk(Build.VERSION.SDK_INT)) {
            ExternalRimeStorage.Access.LegacyPermission ->
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
            ExternalRimeStorage.Access.Unsupported -> false
            ExternalRimeStorage.Access.AllFilesPermission ->
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
        }

    fun treeUriToAbsolutePath(uri: Uri): String? {
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        return ExternalRimeStorage.absolutePath(
            uri.authority, documentId, Environment.getExternalStorageDirectory().absolutePath
        )
    }

    val pickDirectory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val absolute = treeUriToAbsolutePath(uri)
        if (absolute == null) {
            context.toast(R.string.addon_dir_profile_external_dir_invalid)
            return@rememberLauncherForActivityResult
        }
        pendingExternalPath = absolute
        pendingNewName = absolute.trimEnd('/').substringAfterLast('/')
        pendingCreate = true
    }

    val requestAllFiles = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (hasExternalStorageAccess()) pickDirectory.launch(null)
        else context.toast(R.string.addon_dir_profile_all_files_permission_required)
    }

    val requestLegacyStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pickDirectory.launch(null)
        else context.toast(R.string.addon_dir_profile_storage_permission_required)
    }

    fun pickExternalDirectory() {
        if (hasExternalStorageAccess()) {
            pickDirectory.launch(null)
            return
        }
        when (ExternalRimeStorage.accessForSdk(Build.VERSION.SDK_INT)) {
            ExternalRimeStorage.Access.LegacyPermission ->
                requestLegacyStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            ExternalRimeStorage.Access.Unsupported ->
                context.toast(R.string.addon_dir_profile_android10_unsupported)
            ExternalRimeStorage.Access.AllFilesPermission -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    runCatching { requestAllFiles.launch(intent) }.onFailure {
                        runCatching {
                            requestAllFiles.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        }.onFailure { failure -> context.toast(failure) }
                    }
                }
            }
        }
    }

    PageScaffold(
        title = title,
        onBack = onBack,
        floatingActionButton = {
            TextButton(
                text = stringResource(R.string.addon_dir_profile_create),
                onClick = {
                    pendingExternalPath = null
                    pendingNewName = ""
                    pendingCreate = true
                },
                colors = ButtonDefaults.textButtonColorsPrimary(),
                modifier = Modifier.padding(16.dp),
            )
        },
    ) {
        if (loading) {
            item {
                Text(
                    text = stringResource(R.string.loading),
                    modifier = Modifier.padding(16.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        } else {
            if (state.error.isNotBlank()) {
                item {
                    Text(
                        text = state.error,
                        modifier = Modifier.padding(16.dp),
                        color = MiuixTheme.colorScheme.error,
                    )
                }
            }
            items(state.profiles) { name ->
                val active = name == state.current
                SwitchPreference(
                    title = if (active) {
                        stringResource(R.string.addon_dir_profile_current_format, name)
                    } else {
                        name
                    },
                    summary = state.paths[name].orEmpty(),
                    checked = active,
                    onCheckedChange = { checked ->
                        if (!checked || active) return@SwitchPreference
                        scope.launch { runAction("switch", name = name) }
                    },
                )
                ArrowPreference(
                    title = stringResource(R.string.addon_dir_profile_rename),
                    onClick = {
                        pendingRename = name
                        pendingNewName = name
                    },
                )
                ArrowPreference(
                    title = stringResource(R.string.delete),
                    onClick = {
                        if (state.profiles.size <= 1) {
                            context.toast(R.string.addon_dir_profile_delete_last_forbidden)
                        } else {
                            pendingDelete = name
                        }
                    },
                )
            }
        }
    }

    if (pendingCreate) {
        SimpleConfirmDialog(
            title = stringResource(R.string.addon_dir_profile_create),
            message = pendingNewName.ifBlank { stringResource(R.string.name) },
            onConfirm = {
                val name = pendingNewName.trim()
                pendingCreate = false
                if (name.isNotEmpty()) {
                    scope.launch { runAction("create", name = name, externalPath = pendingExternalPath) }
                }
            },
            onDismiss = {
                pendingCreate = false
                pendingExternalPath = null
            },
        )
    }

    pendingRename?.let { oldName ->
        SimpleConfirmDialog(
            title = stringResource(R.string.addon_dir_profile_rename),
            message = oldName,
            onConfirm = {
                pendingRename = null
                if (pendingNewName.isNotBlank() && pendingNewName != oldName) {
                    scope.launch { runAction("rename", name = oldName, newName = pendingNewName) }
                }
            },
            onDismiss = { pendingRename = null },
        )
    }

    pendingDelete?.let { name ->
        SimpleConfirmDialog(
            title = stringResource(R.string.delete),
            message = stringResource(R.string.addon_dir_profile_delete_confirm, name),
            onConfirm = {
                pendingDelete = null
                scope.launch { runAction("delete", name = name) }
            },
            onDismiss = { pendingDelete = null },
        )
    }
}
