/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.screens

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.table.TableBasedInputMethod
import org.fcitx.fcitx5.android.data.table.TableManager
import org.fcitx.fcitx5.android.data.table.dict.Dictionary
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.ui.main.compose.dialog.SimpleConfirmDialog
import org.fcitx.fcitx5.android.utils.importErrorDialog
import org.fcitx.fcitx5.android.utils.notificationManager
import org.fcitx.fcitx5.android.utils.queryFileName
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val CHANNEL_ID = "table_dict"
private var IMPORT_ID = 0

/**
 * Compose renderer for the table input method list (replaces the View-based
 * TableInputMethodFragment). Supports zip import, replace-dict via the row's settings button and
 * removal; every mutation restarts fcitx afterward.
 */
@Composable
fun TableInputMethodsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf(TableManager.inputMethods()) }
    var deleteTarget by remember { mutableStateOf<TableBasedInputMethod?>(null) }
    var replaceTarget by remember { mutableStateOf<TableBasedInputMethod?>(null) }

    // Create the import-progress notification channel once per screen lifetime.
    remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.table_im),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = CHANNEL_ID },
            )
        }
    }

    fun reload() {
        entries = TableManager.inputMethods()
    }

    fun notifyImporting(fileName: String, id: Int) {
        context.notificationManager.notify(
            id,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_baseline_library_books_24)
                .setContentTitle(context.getString(R.string.table_im))
                .setContentText("${context.getString(R.string.importing)} $fileName")
                .setOngoing(true)
                .setProgress(100, 0, true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build(),
        )
    }

    val zipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val fileName = context.contentResolver.queryFileName(uri) ?: return@launch
            if (!fileName.endsWith(".zip")) {
                context.importErrorDialog(R.string.exception_table_im_filename, fileName)
                return@launch
            }
            val importId = IMPORT_ID++
            notifyImporting(fileName, importId)
            try {
                withContext(Dispatchers.IO) {
                    val inputStream = context.contentResolver.openInputStream(uri)!!
                    TableManager.importFromZip(inputStream).getOrThrow()
                    FcitxDaemon.restartFcitx()
                }
                reload()
            } catch (e: Exception) {
                context.importErrorDialog(e)
            } finally {
                context.notificationManager.cancel(importId)
            }
        }
    }

    val replaceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val im = replaceTarget ?: return@rememberLauncherForActivityResult
        replaceTarget = null
        scope.launch {
            val dictName = context.contentResolver.queryFileName(uri) ?: return@launch
            if (Dictionary.Type.fromFileName(dictName) == null) {
                context.importErrorDialog(R.string.exception_table_dict_filename, dictName)
                return@launch
            }
            val importId = IMPORT_ID++
            notifyImporting(dictName, importId)
            try {
                val imported = withContext(Dispatchers.IO) {
                    val dictStream = context.contentResolver.openInputStream(uri)!!
                    val result =
                        TableManager.replaceTableDict(im, dictName, dictStream).getOrThrow()
                    FcitxDaemon.restartFcitx()
                    result
                }
                im.table = imported
                reload()
            } catch (e: Exception) {
                context.importErrorDialog(e)
            } finally {
                context.notificationManager.cancel(importId)
            }
        }
    }

    deleteTarget?.let { target ->
        SimpleConfirmDialog(
            title = stringResource(R.string.table_im),
            message = stringResource(R.string.table_im_delete_confirm, target.name),
            onConfirm = {
                deleteTarget = null
                scope.launch {
                    withContext(Dispatchers.IO) {
                        target.delete()
                        FcitxDaemon.restartFcitx()
                    }
                    reload()
                }
            },
            onDismiss = { deleteTarget = null },
        )
    }

    replaceTarget?.let { im ->
        SimpleConfirmDialog(
            title = stringResource(
                if (im.tableFileExists) R.string.update_table
                else R.string.table_file_does_not_exist_title,
            ),
            message = stringResource(
                if (im.tableFileExists) R.string.table_dict_replace_message
                else R.string.table_file_does_not_exist_message,
                im.tableFileName,
            ),
            onConfirm = {
                replaceTarget = null
                replaceLauncher.launch("*/*")
            },
            onDismiss = { replaceTarget = null },
        )
    }

    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxSize()) {
            LazyColumn(
                contentPadding = PaddingValues(
                    top = 64.dp + WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                    bottom = 88.dp,
                ),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(entries, key = { _, e -> e.file.absolutePath }) { _, entry ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                        colors = CardDefaults.defaultColors(
                            color = MiuixTheme.colorScheme.surfaceContainerHighest,
                        ),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                        ) {
                            Text(
                                text = entry.name,
                                modifier = Modifier.weight(1f).padding(vertical = 14.dp),
                            )
                            IconButton(
                                onClick = { replaceTarget = entry },
                            ) {
                                Icon(MiuixIcons.Tune, stringResource(R.string.edit), Modifier.size(20.dp))
                            }
                            IconButton(
                                onClick = { deleteTarget = entry },
                            ) {
                                Icon(MiuixIcons.Delete, stringResource(R.string.delete), Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { zipLauncher.launch("application/zip") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            Icon(MiuixIcons.Add, stringResource(R.string.add))
        }
        SmallTopAppBar(
            color = MiuixTheme.colorScheme.surfaceContainer,
            title = stringResource(R.string.table_im),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(MiuixIcons.Back, stringResource(R.string.back), Modifier.size(24.dp))
                }
            },
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
        )
    }
}
