/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.screens

import android.os.Debug
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.entity.License
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.data.DataManager
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.ui.main.LogActivity
import org.fcitx.fcitx5.android.ui.main.compose.AppRoute
import org.fcitx.fcitx5.android.ui.main.compose.dialog.SimpleConfirmDialog
import org.fcitx.fcitx5.android.utils.Const
import org.fcitx.fcitx5.android.utils.formatDateTime
import org.fcitx.fcitx5.android.utils.iso8601UTCDateTime
import org.fcitx.fcitx5.android.utils.setupForest
import org.fcitx.fcitx5.android.utils.startActivity
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.io.File
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.padding

@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onNavigate: (AppRoute) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val context = LocalContext.current
    PageScaffold(title = stringResource(R.string.about), onBack = onBack) {
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.surfaceContainerHighest,
                ),
            ) {
                ArrowPreference(
                    title = stringResource(R.string.privacy_policy),
                    onClick = { onOpenUrl(Const.privacyPolicyUrl) },
                )
                ArrowPreference(
                    title = stringResource(R.string.open_source_licenses),
                    summary = stringResource(R.string.licenses_of_third_party_libraries),
                    onClick = { onNavigate(AppRoute.Licenses) },
                )
                ArrowPreference(
                    title = stringResource(R.string.source_code),
                    summary = Const.githubRepo,
                    onClick = { onOpenUrl(Const.githubRepo) },
                )
                ArrowPreference(
                    title = stringResource(R.string.license),
                    summary = Const.licenseSpdxId,
                    onClick = { onOpenUrl(Const.licenseUrl) },
                )
            }
        }
        item {
            SmallTitle(text = stringResource(R.string.version))
        }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.surfaceContainerHighest,
                ),
            ) {
                BasicComponent(
                    title = stringResource(R.string.current_version),
                    summary = Const.versionName,
                )
                ArrowPreference(
                    title = stringResource(R.string.build_git_hash),
                    summary = BuildConfig.BUILD_GIT_HASH,
                    onClick = {
                        val commit = BuildConfig.BUILD_GIT_HASH.substringBefore('-')
                        onOpenUrl("${Const.githubRepo}/commit/$commit")
                    },
                )
                BasicComponent(
                    title = stringResource(R.string.build_time),
                    summary = formatDateTime(BuildConfig.BUILD_TIME),
                )
            }
        }
    }
}

@Composable
fun LicensesScreen(
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val context = LocalContext.current
    var libs by remember { mutableStateOf<List<Library>?>(null) }
    var multiLicenseTarget by remember { mutableStateOf<Pair<String, List<License>>?>(null) }

    LaunchedEffect(Unit) {
        libs = withContext(Dispatchers.Default) {
            val jsonString = context.resources.openRawResource(R.raw.aboutlibraries)
                .bufferedReader().use { it.readText() }
            Libs.Builder()
                .withJson(jsonString)
                .build()
                .libraries
                .sortedBy {
                    if (it.tag == "native") it.uniqueId.uppercase() else it.uniqueId.lowercase()
                }
        }
    }

    fun licenseLabel(l: License) = l.spdxId ?: l.name

    multiLicenseTarget?.let { (uniqueId, licenses) ->
        WindowDialog(
            show = true,
            title = uniqueId,
            onDismissRequest = { multiLicenseTarget = null },
            onDismissFinished = { multiLicenseTarget = null },
        ) {
            ListPopupColumn {
                licenses.forEachIndexed { index, license ->
                    DropdownImpl(
                        text = licenseLabel(license),
                        optionSize = licenses.size,
                        isSelected = false,
                        index = index,
                        onSelectedIndexChange = {
                            multiLicenseTarget = null
                            license.url?.takeIf { it.isNotBlank() }?.let(onOpenUrl)
                        },
                    )
                }
            }
        }
    }

    PageScaffold(title = stringResource(R.string.open_source_licenses), onBack = onBack) {
        val items = libs.orEmpty()
        if (libs == null) {
            item {
                Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.surfaceContainerHighest,
                ),
            ) {
                    BasicComponent(title = "…")
                }
            }
        }
        items(items.size, key = { items[it].uniqueId }) { index ->
            val lib = items[index]
            Card(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.surfaceContainerHighest,
                ),
            ) {
                ArrowPreference(
                    title = "${lib.uniqueId}:${lib.artifactVersion}",
                    summary = lib.licenses.joinToString { l -> licenseLabel(l) },
                    onClick = {
                        when (lib.licenses.size) {
                            0 -> {}
                            1 -> lib.licenses.first().url
                                ?.takeIf { it.isNotBlank() }?.let(onOpenUrl)
                            else -> multiLicenseTarget = lib.uniqueId to lib.licenses.toList()
                        }
                    },
                )
            }
        }
    }
}

@Composable
fun DeveloperScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var restartConfirm by remember { mutableStateOf(false) }
    var syncConfirm by remember { mutableStateOf(false) }
    var nukeConfirm by remember { mutableStateOf(false) }
    val internalPrefs = remember { AppPrefs.getInstance().internal }
    var verboseLog by remember { mutableStateOf(internalPrefs.verboseLog.getValue()) }
    var editorInfoInspector by remember {
        mutableStateOf(internalPrefs.editorInfoInspector.getValue())
    }
    var hprofFile by remember { mutableStateOf<File?>(null) }
    val heapDumpLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val file = hprofFile ?: return@rememberLauncherForActivityResult
        if (uri == null) {
            file.delete()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)!!.use { o ->
                        file.inputStream().use { i -> i.copyTo(o) }
                    }
                }
            } catch (e: Exception) {
                context.toast(e)
            } finally {
                withContext(NonCancellable) {
                    withContext(Dispatchers.IO) { file.delete() }
                }
            }
        }
    }

    if (restartConfirm) {
        SimpleConfirmDialog(
            title = stringResource(R.string.restart_fcitx_instance),
            message = stringResource(R.string.restart_fcitx_instance_confirm),
            onConfirm = {
                restartConfirm = false
                scope.launch {
                    FcitxDaemon.restartFcitx()
                    context.toast(R.string.done)
                }
            },
            onDismiss = { restartConfirm = false },
        )
    }
    if (syncConfirm) {
        SimpleConfirmDialog(
            title = stringResource(R.string.delete_and_sync_data),
            message = stringResource(R.string.delete_and_sync_data_message),
            onConfirm = {
                syncConfirm = false
                scope.launch {
                    withContext(Dispatchers.IO) { DataManager.deleteAndSync() }
                    context.toast(R.string.synced)
                }
            },
            onDismiss = { syncConfirm = false },
        )
    }
    if (nukeConfirm) {
        SimpleConfirmDialog(
            title = stringResource(R.string.clear_clb_db),
            message = stringResource(R.string.clear_clp_db_confirm),
            onConfirm = {
                nukeConfirm = false
                scope.launch {
                    withContext(Dispatchers.IO) { ClipboardManager.nukeTable() }
                    context.toast(R.string.done)
                }
            },
            onDismiss = { nukeConfirm = false },
        )
    }

    PageScaffold(title = stringResource(R.string.developer), onBack = onBack) {
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.surfaceContainerHighest,
                ),
            ) {
                ArrowPreference(
                    title = stringResource(R.string.real_time_logs),
                    onClick = { context.startActivity<LogActivity>() },
                )
                SwitchPreference(
                    title = stringResource(R.string.verbose_log),
                    checked = verboseLog,
                    onCheckedChange = { newValue ->
                        verboseLog = newValue
                        internalPrefs.verboseLog.setValue(newValue)
                        Timber.setupForest(newValue)
                        FcitxDaemon.getFirstConnectionOrNull()?.runIfReady {
                            setLogRule(newValue)
                        }
                    },
                )
                SwitchPreference(
                    title = stringResource(R.string.editor_info_inspector),
                    checked = editorInfoInspector,
                    onCheckedChange = { newValue ->
                        editorInfoInspector = newValue
                        internalPrefs.editorInfoInspector.setValue(newValue)
                    },
                )
            }
        }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.surfaceContainerHighest,
                ),
            ) {
                ArrowPreference(
                    title = stringResource(R.string.restart_fcitx_instance),
                    onClick = { restartConfirm = true },
                )
                ArrowPreference(
                    title = stringResource(R.string.delete_and_sync_data),
                    onClick = { syncConfirm = true },
                )
                ArrowPreference(
                    title = stringResource(R.string.clear_clb_db),
                    onClick = { nukeConfirm = true },
                )
                ArrowPreference(
                    title = stringResource(R.string.capture_heap_dump),
                    onClick = {
                        val fileName = "${context.packageName}_${iso8601UTCDateTime()}.hprof"
                        val file = context.cacheDir.resolve(fileName)
                        System.gc()
                        try {
                            Debug.dumpHprofData(file.absolutePath)
                            hprofFile = file
                            heapDumpLauncher.launch(fileName)
                        } catch (e: Exception) {
                            context.toast(e)
                        }
                    },
                )
            }
        }
    }
}
