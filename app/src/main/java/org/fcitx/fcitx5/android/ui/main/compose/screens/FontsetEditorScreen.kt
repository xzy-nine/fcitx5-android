/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.ui.main.compose.dialog.SimpleConfirmDialog
import org.fcitx.fcitx5.android.ui.main.settings.behavior.FontEntrySpec
import org.fcitx.fcitx5.android.ui.main.settings.behavior.FontsetActions
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 字体集编辑页。
 *
 * 每类文字可分别指定字体与字号；字体按选择顺序叠加，先选的作为主字体。
 * 改动在保存后统一写回并触发重载。
 */
@Composable
fun FontsetEditorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val entries = remember {
        listOf(
            FontEntrySpec("font", R.string.fontset_entry_font, context.getString(R.string.fontset_sample_main), 20f),
            FontEntrySpec("key_main_font", R.string.fontset_entry_key_main, context.getString(R.string.fontset_sample_main), 23f),
            FontEntrySpec("key_alt_font", R.string.fontset_entry_key_alt, context.getString(R.string.fontset_sample_alt), 10.67f),
            FontEntrySpec("cand_font", R.string.fontset_entry_candidate, context.getString(R.string.fontset_sample_candidate), 20f),
            FontEntrySpec(
                org.fcitx.fcitx5.android.input.font.FontProviders.KEY_COMMENT_FONT,
                R.string.fontset_entry_comment,
                context.getString(R.string.fontset_sample_comment),
                org.fcitx.fcitx5.android.input.font.FontProviders.DEFAULT_COMMENT_FONT_SIZE,
            ),
            FontEntrySpec("popup_key_font", R.string.fontset_entry_popup_key, context.getString(R.string.fontset_sample_popup), 18f),
            FontEntrySpec("preedit_font", R.string.fontset_entry_preedit, context.getString(R.string.fontset_sample_preedit), 18f),
        )
    }

    var availableFonts by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedFonts by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var selectedSizes by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    var initialFonts by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var initialSizes by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    var editingFontEntry by remember { mutableStateOf<FontEntrySpec?>(null) }
    var editingSizeEntry by remember { mutableStateOf<FontEntrySpec?>(null) }
    var draftFontOrder by remember { mutableStateOf<List<String>>(emptyList()) }
    var draftSize by remember { mutableStateOf(20f) }

    LaunchedEffect(Unit) {
        availableFonts = FontsetActions.availableFonts(context)
        val parsed = FontsetActions.load(context)
        val fonts = entries.associate { entry ->
            entry.key to (parsed[entry.key]?.toList() ?: emptyList())
        }
        val sizes = entries.associate { entry ->
            val raw = parsed["" + entry.key + "_size"]?.firstOrNull()?.toFloatOrNull()
            entry.key to (raw?.coerceIn(FontsetActions.MIN_SIZE, FontsetActions.MAX_SIZE) ?: entry.defaultSize)
        }
        selectedFonts = fonts
        selectedSizes = sizes
        initialFonts = fonts
        initialSizes = sizes
    }

    val hasChanges = entries.any { entry ->
        selectedFonts[entry.key].orEmpty() != initialFonts[entry.key].orEmpty() ||
            selectedSizes[entry.key] != initialSizes[entry.key]
    }

    fun save() {
        FontsetActions.save(context, selectedFonts, selectedSizes, entries)
            .onSuccess {
                initialFonts = selectedFonts
                initialSizes = selectedSizes
                context.toast(R.string.fontset_saved_at)
                // 通知引擎重载配置，使新字体生效
                FcitxDaemon.getFirstConnectionOrNull()?.let { conn ->
                    runCatching { conn.runIfReady { reloadConfig() } }
                        .onFailure { Timber.w(it, "字体集保存后重载配置失败") }
                }
            }
            .onFailure { e ->
                Timber.w(e, "字体集保存失败")
                context.toast(e.message ?: context.getString(R.string.fontset_save_failed_unknown_error, ""))
            }
    }

    PageScaffold(
        title = stringResource(R.string.edit_fontset),
        onBack = onBack,
        floatingActionButton = {
            ArrowPreference(
                title = stringResource(R.string.save),
                enabled = hasChanges,
                modifier = Modifier.padding(16.dp),
                onClick = { save() },
            )
        },
    ) {
        item {
            Text(
                text = stringResource(R.string.fontset_editor_hint),
                modifier = Modifier.padding(16.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        entries.forEach { entry ->
            item {
                Card(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
                ) {
                    Text(
                        text = stringResource(entry.titleRes),
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp),
                    )
                    Text(
                        text = selectedFonts[entry.key].orEmpty()
                            .joinToString(", ")
                            .ifEmpty { stringResource(R.string.none) },
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    ArrowPreference(
                        title = stringResource(R.string.fontset_pick_font),
                        onClick = {
                            editingFontEntry = entry
                            val current = selectedFonts[entry.key].orEmpty()
                            draftFontOrder = current + availableFonts.filter { it !in current }
                        },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.font_size_editor_title),
                        summary = stringResource(
                            R.string.font_size_value,
                            (selectedSizes[entry.key] ?: entry.defaultSize).toInt(),
                        ),
                        onClick = {
                            editingSizeEntry = entry
                            draftSize = selectedSizes[entry.key] ?: entry.defaultSize
                        },
                    )
                }
            }
        }
    }

    // 字体选择：按勾选顺序叠加，勾选顺序即优先级
    editingFontEntry?.let { entry ->
        WindowDialog(
            show = true,
            title = stringResource(R.string.fontset_entry_font),
            summary = stringResource(R.string.fontset_editor_hint),
            onDismissRequest = { editingFontEntry = null },
        ) {
            Column {
                if (availableFonts.isEmpty()) {
                    Text(
                        text = stringResource(R.string.no_fonts_found),
                        modifier = Modifier.padding(16.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                } else {
                    draftFontOrder.forEach { name ->
                        CheckboxPreference(
                            title = name,
                            checked = name in selectedFonts[entry.key].orEmpty(),
                            onCheckedChange = { checked ->
                                val current = selectedFonts[entry.key].orEmpty().toMutableList()
                                if (checked) {
                                    if (name !in current) current += name
                                } else {
                                    current.remove(name)
                                }
                                selectedFonts = selectedFonts + (entry.key to current)
                            },
                        )
                    }
                }
                ArrowPreference(
                    title = stringResource(android.R.string.ok),
                    modifier = Modifier.padding(8.dp),
                    onClick = { editingFontEntry = null },
                )
            }
        }
    }

    // 字号调整
    editingSizeEntry?.let { entry ->
        WindowDialog(
            show = true,
            title = stringResource(R.string.font_size_editor_title),
            summary = stringResource(
                R.string.font_size_editor_hint,
                FontsetActions.MIN_SIZE.toInt(),
                FontsetActions.MAX_SIZE.toInt(),
            ),
            onDismissRequest = { editingSizeEntry = null },
        ) {
            Column {
                Text(
                    text = entry.sample,
                    modifier = Modifier.padding(16.dp),
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.font_size_value, draftSize.toInt()),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Slider(
                    value = draftSize,
                    onValueChange = { draftSize = it },
                    valueRange = FontsetActions.MIN_SIZE..FontsetActions.MAX_SIZE,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                ) {
                    ArrowPreference(
                        title = stringResource(android.R.string.cancel),
                        modifier = Modifier.weight(1f),
                        onClick = { editingSizeEntry = null },
                    )
                    ArrowPreference(
                        title = stringResource(android.R.string.ok),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            selectedSizes = selectedSizes + (entry.key to draftSize)
                            editingSizeEntry = null
                        },
                    )
                }
            }
        }
    }
}
