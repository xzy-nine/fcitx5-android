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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.getPunctuationConfig
import org.fcitx.fcitx5.android.data.punctuation.PunctuationManager
import org.fcitx.fcitx5.android.data.punctuation.PunctuationMapEntry
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.ui.main.compose.dialog.SimpleThreeFieldDialog
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

/**
 * Compose renderer for the punctuation map editor (replaces the View-based
 * PunctuationEditorFragment). Entries are key->mapping(+altMapping) triples saved to fcitx.
 */
@Composable
fun PunctuationScreen(
    title: String,
    lang: String?,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val fcitx: FcitxConnection = remember { FcitxDaemon.connect("compose-punctuation") }
    val effectiveLang = lang ?: "zh_CN"
    var entries by remember { mutableStateOf<List<PunctuationMapEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Pair<Int, PunctuationMapEntry>?>(null) }
    var isNew by remember { mutableStateOf(false) }
    // labels come from the fcitx config description (same as the legacy fragment, no hardcoding)
    var keyLabel by remember { mutableStateOf("") }
    var mappingLabel by remember { mutableStateOf("") }
    var altMappingLabel by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        try {
            val raw = fcitx.runOnReady { getPunctuationConfig(effectiveLang) }
            entries = PunctuationManager.parseRawConfig(raw)
            // parse the description of the map-entry options for the edit dialog field labels
            raw["desc"][PunctuationManager.MAP_ENTRY_CONFIG].subItems?.forEach {
                val desc = it["Description"].value
                when (it.name) {
                    PunctuationManager.KEY -> keyLabel = desc
                    PunctuationManager.MAPPING -> mappingLabel = desc
                    PunctuationManager.ALT_MAPPING -> altMappingLabel = desc
                }
            }
        } catch (e: Exception) {
            // fcitx may not be reachable (daemon down / service restart); fail gracefully
            // instead of leaving the screen stuck on the loading spinner forever
            loadFailed = true
        }
        loading = false
    }

    DisposableEffect(Unit) {
        onDispose {
            FcitxDaemon.disconnect("compose-punctuation")
        }
    }

    fun save() {
        scope.launch {
            fcitx.runOnReady { PunctuationManager.save(this, effectiveLang, entries) }
        }
    }

    if (loading) {
        Box(
            Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    if (loadFailed) {
        Box(
            Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.failed_to_load_config),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        return
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
                itemsIndexed(entries) { index, entry ->
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
                                text = "${entry.key}\u2003→\u2003${entry.mapping} ${entry.altMapping}",
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
        }
        FloatingActionButton(
            onClick = {
                editTarget = -1 to PunctuationMapEntry("", "", "")
                isNew = true
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            Icon(MiuixIcons.Add, stringResource(R.string.add))
        }
        SmallTopAppBar(
            color = MiuixTheme.colorScheme.surfaceContainer,
            title = title,
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(MiuixIcons.Back, stringResource(R.string.back), Modifier.size(24.dp))
                }
            },
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
        )
    }

    editTarget?.let { (index, entry) ->
        SimpleThreeFieldDialog(
            title = title,
            field1Label = keyLabel,
            field2Label = mappingLabel,
            field3Label = altMappingLabel,
            value1 = entry.key,
            value2 = entry.mapping,
            value3 = entry.altMapping,
            onConfirm = { key, mapping, alt ->
                if (isNew) {
                    entries = entries + PunctuationMapEntry(key, mapping, alt)
                } else {
                    entries = entries.toMutableList().apply {
                        set(index, PunctuationMapEntry(key, mapping, alt))
                    }
                }
                save()
                editTarget = null
            },
            onDismiss = { editTarget = null },
        )
    }
}
