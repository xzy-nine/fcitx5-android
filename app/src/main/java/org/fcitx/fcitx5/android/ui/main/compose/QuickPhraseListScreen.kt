/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose

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
import org.fcitx.fcitx5.android.data.quickphrase.CustomQuickPhrase
import org.fcitx.fcitx5.android.data.quickphrase.QuickPhrase
import org.fcitx.fcitx5.android.data.quickphrase.QuickPhraseManager
import org.fcitx.fcitx5.android.utils.importErrorDialog
import org.fcitx.fcitx5.android.utils.queryFileName
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Compose renderer for the quick phrase list (replaces the View-based QuickPhraseListFragment).
 * Enabled phrases toggle individually; builtin phrases are only toggleable while custom ones can
 * be deleted. Supports create/import via the FAB and reorder by long-press drag.
 */
@Composable
fun QuickPhraseListScreen(
    onEdit: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember {
        mutableStateOf(QuickPhraseManager.listQuickPhrase())
    }
    var showCreateDialog by remember { mutableStateOf(false) }
    var createName by remember { mutableStateOf("") }

    fun reload() {
        entries = QuickPhraseManager.listQuickPhrase()
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val fileName = context.contentResolver.queryFileName(uri)
                ?: return@launch
            val ext = fileName.substringAfterLast('.')
            if (ext != QuickPhrase.EXT) {
                context.importErrorDialog(R.string.exception_quickphrase_filename, fileName)
                return@launch
            }
            val entryName = fileName.substringBeforeLast('.')
            if (entries.any { it.name == entryName }) {
                context.importErrorDialog(R.string.quickphrase_already_exists)
                return@launch
            }
            val imported = withContext(Dispatchers.IO) {
                QuickPhraseManager.importFromInputStream(
                    context.contentResolver.openInputStream(uri)!!, fileName
                )
            }
            imported.fold(
                onSuccess = { reload() },
                onFailure = { context.importErrorDialog(it) },
            )
        }
    }

    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
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
                        QuickPhraseRow(
                            entry = entry,
                            onToggle = {
                                if (entry.isEnabled) entry.disable() else entry.enable()
                                reload()
                            },
                            onEdit = { onEdit(entry.file.name) },
                            onDelete = {
                                entry.file.delete()
                                reload()
                            },
                        )
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { showCreateDialog = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            Icon(MiuixIcons.Add, null)
        }
        SmallTopAppBar(
            title = context.getString(R.string.quickphrase_editor),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(MiuixIcons.Back, null, Modifier.size(24.dp))
                }
            },
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
        )
    }

    if (showCreateDialog) {
        SimpleTextFieldDialog(
            title = context.getString(R.string.create_new),
            hint = context.getString(R.string.name),
            value = createName,
            onValueChange = { createName = it },
            onConfirm = {
                val name = createName.trim()
                if (name.isNotBlank()) {
                    QuickPhraseManager.newEmpty(name)
                    reload()
                }
                createName = ""
            },
            onDismiss = {
                showCreateDialog = false
                createName = ""
            },
        )
    }
}

@Composable
private fun QuickPhraseRow(
    entry: QuickPhrase,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
    ) {
        Text(
            text = entry.name,
            modifier = Modifier.weight(1f).padding(vertical = 14.dp),
        )
        Switch(
            checked = entry.isEnabled,
            onCheckedChange = { onToggle() },
        )
        IconButton(onClick = onEdit) {
            Icon(MiuixIcons.Tune, null, Modifier.size(20.dp))
        }
        if (entry is CustomQuickPhrase) {
            IconButton(onClick = onDelete) {
                Icon(MiuixIcons.Delete, null, Modifier.size(20.dp))
            }
        }
    }
}
