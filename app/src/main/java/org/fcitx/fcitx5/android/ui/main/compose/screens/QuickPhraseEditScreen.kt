/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.screens

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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.quickphrase.QuickPhrase
import org.fcitx.fcitx5.android.data.quickphrase.QuickPhraseData
import org.fcitx.fcitx5.android.data.quickphrase.QuickPhraseEntry
import org.fcitx.fcitx5.android.data.quickphrase.QuickPhraseManager
import org.fcitx.fcitx5.android.ui.main.compose.dialog.SimpleTwoFieldDialog
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
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
 * Compose renderer for editing one quick phrase's keyword->phrase entries
 * (replaces the View-based QuickPhraseEditFragment).
 */
@Composable
fun QuickPhraseEditScreen(
    fileName: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val quickPhrase: QuickPhrase? = remember(fileName) {
        QuickPhraseManager.listQuickPhrase().firstOrNull { it.file.name == fileName }
    }
    var entries by remember { mutableStateOf<List<QuickPhraseEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var editTarget by remember { mutableStateOf<Pair<Int, QuickPhraseEntry>?>(null) }
    var isNew by remember { mutableStateOf(false) }
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    val saveMutex = remember { Mutex() }
    var currentSaveJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(quickPhrase) {
        val data = quickPhrase?.let { withContext(Dispatchers.IO) { it.loadData() } }
        if (data != null) {
            entries = data.toList()
        }
        loading = false
    }

    DisposableEffect(Unit) {
        onDispose {
            currentSaveJob?.cancel()
            scope.cancel()
        }
    }

    fun save() {
        val qp = quickPhrase ?: return
        val data = QuickPhraseData(entries)
        currentSaveJob = scope.launch {
            saveMutex.withLock {
                withContext(Dispatchers.IO) { qp.saveData(data) }
            }
        }
    }

    if (loading || quickPhrase == null) {
        Box(
            Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    PageScaffold(
        title = quickPhrase.name,
        onBack = onBack,
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editTarget = -1 to QuickPhraseEntry("", "")
                    isNew = true
                },
                modifier = Modifier.padding(16.dp),
            ) {
                Icon(MiuixIcons.Add, stringResource(R.string.add))
            }
        },
        contentBottomPadding = 88.dp,
    ) {
        itemsIndexed(entries, key = { index, _ -> "$index-${entries[index].keyword}" }) { index, entry ->
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
                                text = "${entry.keyword} → ${entry.phrase.replace("\n", "\\n")}",
                                modifier = Modifier.weight(1f).padding(vertical = 14.dp),
                            )
                            IconButton(
                                onClick = {
                                    editTarget = index to entry
                                    isNew = false
                                },
                            ) {
                                Icon(MiuixIcons.Tune, stringResource(R.string.edit), Modifier.size(20.dp))
                            }
                            IconButton(
                                onClick = {
                                    entries = entries.toMutableList().apply { removeAt(index) }
                                    save()
                                },
                            ) {
                                Icon(MiuixIcons.Delete, stringResource(R.string.delete), Modifier.size(20.dp))
                            }
                        }
                    }
                }
    }

    editTarget?.let { (index, entry) ->
        SimpleTwoFieldDialog(
            title = stringResource(R.string.quickphrase_editor),
            field1Label = stringResource(R.string.quickphrase_keyword),
            field2Label = stringResource(R.string.quickphrase_phrase),
            value1 = entry.keyword,
            value2 = entry.phrase,
            onConfirm = { k, p ->
                if (isNew) {
                    entries = entries + QuickPhraseEntry(k, p)
                } else {
                    entries = entries.toMutableList().apply { set(index, QuickPhraseEntry(k, p)) }
                }
                save()
                editTarget = null
            },
            onDismiss = { editTarget = null },
        )
    }
}
