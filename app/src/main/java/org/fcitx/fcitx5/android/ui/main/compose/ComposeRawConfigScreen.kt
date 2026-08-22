/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import arrow.core.getOrElse
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.ui.main.settings.FcitxRawConfigStore
import org.fcitx.fcitx5.android.utils.buildDocumentsProviderIntent
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor.ConfigBool
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor.ConfigCustom
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor.ConfigEnum
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor.ConfigEnumList
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor.ConfigExternal
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor.ConfigInt
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor.ConfigKey
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor.ConfigList
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor.ConfigString
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * Compose renderer for fcitx's dynamic RawConfig pages (replaces the View-based
 * PreferenceScreenFactory rendering). Supports Bool / Enum / Int / String / Key and nested
 * Custom descriptors; list-style descriptors (List / EnumList) are stubbed until their
 * special-purpose screens are migrated. Every write is followed by [onSave].
 * [raw] is the full fcitx config object whose "cfg" and "desc" children are used.
 */
@Composable
fun RawConfigScreen(
    raw: RawConfig,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
) {
    val context = LocalContext.current
    val cfg = raw["cfg"]
    val desc = raw["desc"]
    val store = remember { FcitxRawConfigStore(cfg) }
    val topLevel = remember(desc) {
        ConfigDescriptor.parseTopLevel(desc).getOrElse { null }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = topLevel?.name ?: context.getString(R.string.global_options),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(MiuixIcons.Back, contentDescription = null, modifier = Modifier.size(24.dp))
                    }
                },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            contentPadding = PaddingValues(top = paddingValues.calculateTopPadding()),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (topLevel == null) {
                item {
                    Box(Modifier.padding(16.dp)) {
                        Text(text = context.getString(R.string.failed_to_load_config))
                    }
                }
            } else {
                topLevel.values.forEach { descriptor ->
                    when (descriptor) {
                        is ConfigCustom -> {
                            item { SmallTitle(text = descriptor.description ?: descriptor.name) }
                            item {
                                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                                    val children = descriptor.customTypeDef?.values.orEmpty()
                                    children.forEachIndexed { index, child ->
                                        RawConfigRow(
                                            descriptor = child,
                                            cfg = cfg.findByName(child.name),
                                            store = store,
                                            onNavigate = onNavigate,
                                            onSave = onSave,
                                        )
                                        if (index < children.lastIndex) HorizontalDivider()
                                    }
                                }
                            }
                        }
                        else -> {
                            item {
                                Card(
                                    modifier = Modifier.padding(
                                        horizontal = 12.dp, vertical = 4.dp
                                    )
                                ) {
                                    RawConfigRow(
                                        descriptor = descriptor,
                                        cfg = cfg.findByName(descriptor.name),
                                        store = store,
                                        onNavigate = onNavigate,
                                        onSave = onSave,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RawConfigRow(
    descriptor: ConfigDescriptor<*, *>,
    cfg: RawConfig?,
    store: FcitxRawConfigStore,
    onNavigate: (AppRoute) -> Unit,
    onSave: () -> Unit,
) {
    val context = LocalContext.current
    val title = descriptor.description ?: descriptor.name

    when (descriptor) {
        is ConfigBool -> {
            val current = store.getBoolean(descriptor.name, descriptor.defaultValue ?: false)
            SwitchPreference(
                title = title,
                summary = descriptor.tooltip,
                checked = current,
                onCheckedChange = { newValue ->
                    store.putBoolean(descriptor.name, newValue)
                    onSave()
                },
            )
        }

        is ConfigEnum -> {
            val entries = descriptor.entriesI18n ?: descriptor.entries
            val current = store.getString(descriptor.name, descriptor.defaultValue)
            val index = descriptor.entries.indexOf(current).coerceAtLeast(0)
            OverlaySpinnerPreference(
                items = entries.map { DropdownItem(text = it) },
                selectedIndex = index,
                title = title,
                summary = descriptor.tooltip,
                onSelectedIndexChange = { i ->
                    store.putString(descriptor.name, descriptor.entries[i])
                    onSave()
                },
            )
        }

        is ConfigInt -> {
            val min = descriptor.intMin
            val max = descriptor.intMax
            var v by remember { mutableStateOf(store.getInt(descriptor.name, descriptor.defaultValue ?: 0)) }
            if (min != null && max != null && max - min <= 100) {
                SliderPreference(
                    title = title,
                    value = v.toFloat(),
                    onValueChange = { newValue ->
                        v = newValue.toInt()
                        store.putInt(descriptor.name, v)
                        onSave()
                    },
                    valueText = v.toString(),
                    valueRange = min.toFloat()..max.toFloat(),
                    steps = (max - min - 1).coerceAtLeast(0),
                )
            } else {
                var showDialog by remember { mutableStateOf(false) }
                ArrowPreference(
                    title = title,
                    summary = v.toString(),
                    onClick = { showDialog = true },
                    holdDownState = showDialog,
                )
                if (showDialog) {
                    EditValueDialog(
                        title = title,
                        initialText = v.toString(),
                        onConfirm = { text ->
                            val parsed = text.toIntOrNull() ?: return@EditValueDialog false
                            if (min != null && parsed < min) return@EditValueDialog false
                            if (max != null && parsed > max) return@EditValueDialog false
                            v = parsed
                            store.putInt(descriptor.name, parsed)
                            onSave()
                            true
                        },
                        onDismiss = { showDialog = false },
                    )
                }
            }
        }

        is ConfigString -> {
            var showDialog by remember { mutableStateOf(false) }
            var v by remember {
                mutableStateOf(store.getString(descriptor.name, descriptor.defaultValue).orEmpty())
            }
            ArrowPreference(
                title = title,
                summary = v,
                onClick = { showDialog = true },
                holdDownState = showDialog,
            )
            if (showDialog) {
                EditValueDialog(
                    title = title,
                    initialText = v,
                    onConfirm = { text ->
                        v = text
                        store.putString(descriptor.name, text)
                        onSave()
                        true
                    },
                    onDismiss = { showDialog = false },
                )
            }
        }

        is ConfigKey -> {
            var showDialog by remember { mutableStateOf(false) }
            var v by remember {
                mutableStateOf(store.getString(descriptor.name, descriptor.defaultValue).orEmpty())
            }
            ArrowPreference(
                title = title,
                summary = v,
                onClick = { showDialog = true },
                holdDownState = showDialog,
            )
            if (showDialog) {
                EditValueDialog(
                    title = title,
                    initialText = v,
                    onConfirm = { text ->
                        v = text
                        store.putString(descriptor.name, text)
                        onSave()
                        true
                    },
                    onDismiss = { showDialog = false },
                )
            }
        }

        is ConfigEnumList, is ConfigList -> {
            // list-style descriptors need their dedicated screens (entry list / key list editor);
            // the value editor is not migrated into compose yet.
            ArrowPreference(
                title = title,
                summary = descriptor.tooltip ?: context.getString(R.string.unimplemented_type),
                enabled = false,
                onClick = null,
            )
        }

        is ConfigExternal -> {
            when (descriptor.knownType) {
                ConfigExternal.ETy.PinyinDict -> ArrowPreference(
                    title = title,
                    summary = descriptor.tooltip,
                    onClick = { onNavigate(AppRoute.LegacyPinyinDict("")) },
                )
                ConfigExternal.ETy.Punctuation -> ArrowPreference(
                    title = title,
                    summary = descriptor.tooltip,
                    onClick = {
                        onNavigate(
                            AppRoute.LegacyPunctuation(
                                descriptor.description ?: descriptor.name,
                                descriptor.uri?.substringAfterLast('/'),
                            )
                        )
                    },
                )
                ConfigExternal.ETy.QuickPhrase -> ArrowPreference(
                    title = title,
                    summary = descriptor.tooltip,
                    onClick = { onNavigate(AppRoute.Legacy(LegacyTarget.QuickPhraseList)) },
                )
                ConfigExternal.ETy.AndroidTable -> ArrowPreference(
                    title = title,
                    summary = descriptor.tooltip,
                    onClick = { onNavigate(AppRoute.Legacy(LegacyTarget.TableInputMethods)) },
                )
                ConfigExternal.ETy.PinyinCustomPhrase -> ArrowPreference(
                    title = title,
                    summary = descriptor.tooltip,
                    onClick = { onNavigate(AppRoute.Legacy(LegacyTarget.PinyinCustomPhrase)) },
                )
                ConfigExternal.ETy.RimeUserDataDir -> {
                    var showDialog by remember { mutableStateOf(false) }
                    ArrowPreference(
                        title = title,
                        summary = descriptor.tooltip,
                        onClick = { showDialog = true },
                        holdDownState = showDialog,
                    )
                    if (showDialog) {
                        SimpleConfirmDialog(
                            title = title,
                            message = context.getString(R.string.open_rime_user_data_dir),
                            onConfirm = {
                                showDialog = false
                                try {
                                    context.startActivity(buildDocumentsProviderIntent())
                                } catch (e: Exception) {
                                    android.util.Log.w("RawConfigScreen", "open rime dir failed", e)
                                }
                            },
                            onDismiss = { showDialog = false },
                        )
                    }
                }
                else -> ArrowPreference(
                    title = title,
                    summary = descriptor.tooltip ?: context.getString(R.string.unimplemented_type),
                    enabled = false,
                    onClick = null,
                )
            }
        }

        is ConfigCustom -> Unit // handled by the parent card
    }
}
