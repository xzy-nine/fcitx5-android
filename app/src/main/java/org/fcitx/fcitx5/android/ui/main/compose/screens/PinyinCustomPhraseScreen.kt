/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.reloadPinyinCustomPhrase
import org.fcitx.fcitx5.android.data.pinyin.CustomPhraseManager
import org.fcitx.fcitx5.android.data.pinyin.customphrase.PinyinCustomPhrase
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.ui.main.compose.dialog.SimpleThreeFieldDialog
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.absoluteValue
import kotlin.math.min

private const val CHINESE_ADDONS_DOMAIN = "fcitx5-chinese-addons"
private const val KEY = "Key"
private const val ORDER = "Order"
private const val PHRASE = "Phrase"
private const val MANAGE_CUSTOM_PHRASE = "Manage Custom Phrase"

private data class TranslateAndLabels(
    val title: String,
    val key: String,
    val order: String,
    val phrase: String,
)

/**
 * Compose renderer for the pinyin custom phrase list (replaces the View-based
 * PinyinCustomPhraseFragment). Entries are key/order/phrase triples; edits are saved through
 * CustomPhraseManager and reloaded into fcitx.
 */
@Composable
fun PinyinCustomPhraseScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fcitx: FcitxConnection = remember { FcitxDaemon.connect("compose-pinyin-custom-phrase") }
    var entries by remember { mutableStateOf<List<PinyinCustomPhrase>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var editTarget by remember { mutableStateOf<Pair<Int, PinyinCustomPhrase>?>(null) }
    var isNew by remember { mutableStateOf(false) }
    // translated via fcitx, same as the legacy fragment (no hardcoded strings)
    var title by remember { mutableStateOf("") }
    var keyLabel by remember { mutableStateOf("") }
    var orderLabel by remember { mutableStateOf("") }
    var phraseLabel by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val t = fcitx.runOnReady {
            TranslateAndLabels(
                translate(MANAGE_CUSTOM_PHRASE, CHINESE_ADDONS_DOMAIN),
                translate(KEY, CHINESE_ADDONS_DOMAIN),
                translate(ORDER, CHINESE_ADDONS_DOMAIN),
                translate(PHRASE, CHINESE_ADDONS_DOMAIN),
            )
        }
        title = t.title
        keyLabel = t.key
        orderLabel = t.order
        phraseLabel = t.phrase
    }

    LaunchedEffect(Unit) {
        entries = withContext(Dispatchers.IO) {
            CustomPhraseManager.load()?.toList() ?: emptyList()
        }
        loading = false
    }

    DisposableEffect(Unit) {
        onDispose { FcitxDaemon.disconnect("compose-pinyin-custom-phrase") }
    }

    fun save() {
        scope.launch {
            withContext(Dispatchers.IO) {
                CustomPhraseManager.save(entries.toTypedArray())
            }
            fcitx.runOnReady { reloadPinyinCustomPhrase() }
        }
    }

    if (loading) {
        Box(
            Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    fun showEntry(x: PinyinCustomPhrase): String {
        val s = x.serialize()
        val firstLF = s.indexOf('\n')
        val endIndex = min(if (firstLF > 0) firstLF else s.length, 20)
        return if (endIndex == s.length) s else s.take(endIndex) + "…"
    }

    PageScaffold(
        title = title,
        onBack = onBack,
        contentBottomPadding = 88.dp,
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editTarget = -1 to PinyinCustomPhrase("", 1, "")
                    isNew = true
                },
            ) {
                Icon(MiuixIcons.Add, stringResource(R.string.add))
            }
        },
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
                                text = showEntry(entry),
                                modifier = Modifier.weight(1f).padding(vertical = 14.dp),
                            )
                            Switch(
                                checked = entry.enabled,
                                onCheckedChange = {
                                    entries = entries.toMutableList().apply {
                                        set(index, entry.copyEnabled(it))
                                    }
                                    save()
                                },
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
        SimpleThreeFieldDialog(
            title = title,
            field1Label = keyLabel,
            field2Label = orderLabel,
            field3Label = phraseLabel,
            value1 = entry.key,
            value2 = entry.order.absoluteValue.toString(),
            value3 = entry.value,
            onConfirm = { key, order, phrase ->
                val parsed = order.toIntOrNull()?.takeIf { it > 0 } ?: 1
                if (isNew) {
                    entries = entries + PinyinCustomPhrase(key, parsed, phrase)
                } else {
                    // keep the disabled state: a negative order marks the phrase as disabled
                    val signed = if (entry.enabled) parsed else -parsed
                    entries = entries.toMutableList().apply {
                        set(index, PinyinCustomPhrase(key, signed, phrase))
                    }
                }
                save()
                editTarget = null
            },
            onDismiss = { editTarget = null },
        )
    }
}
