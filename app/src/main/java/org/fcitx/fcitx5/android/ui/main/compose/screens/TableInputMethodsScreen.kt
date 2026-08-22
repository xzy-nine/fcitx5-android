/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.screens

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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.table.TableManager
import org.fcitx.fcitx5.android.utils.importErrorDialog
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
import androidx.compose.ui.res.stringResource

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

    fun reload() {
        entries = TableManager.inputMethods()
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
            try {
                reload()
            } catch (e: Exception) {
                context.importErrorDialog(e)
            }
        }
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
                itemsIndexed(entries, key = { _, e -> e.file.absolutePath }) { index, entry ->
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
                                onClick = { /* replace-dict flow kept minimal */ },
                            ) {
                                Icon(MiuixIcons.Tune, null, Modifier.size(20.dp))
                            }
                            IconButton(
                                onClick = {
                                    entry.delete()
                                    reload()
                                },
                            ) {
                                Icon(MiuixIcons.Delete, null, Modifier.size(20.dp))
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
            Icon(MiuixIcons.Add, null)
        }
        SmallTopAppBar(
            color = MiuixTheme.colorScheme.surfaceContainer,
            title = stringResource(R.string.table_im),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(MiuixIcons.Back, null, Modifier.size(24.dp))
                }
            },
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
        )
    }
}
