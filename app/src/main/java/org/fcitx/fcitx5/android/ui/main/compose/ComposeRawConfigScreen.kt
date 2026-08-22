/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import arrow.core.getOrElse
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.RawConfig
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
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Compose renderer for fcitx's dynamic RawConfig pages (replaces the View-based
 * PreferenceScreenFactory rendering). Supports Bool / Enum / EnumList / Int / String / List /
 * Key and nested Custom descriptors.
 *
 * Values are read/written on the **parent** config node (unlike the old FcitxRawConfigStore whose
 * `cfg[key]` non-null assertion would crash for names missing on the top level); every write
 * is followed by [onSave].
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
    val topLevel = remember(desc) {
        ConfigDescriptor.parseTopLevel(desc).getOrElse { null }
    }
    var cfgVersion by remember { mutableIntStateOf(0) }
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
        LazyColumn(
            contentPadding = PaddingValues(top = 64.dp + topInset),
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
                                Card(
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    colors = CardDefaults.defaultColors(
                                        color = MiuixTheme.colorScheme.surfaceContainerHighest,
                                    ),
                                ) {
                                    val children = descriptor.customTypeDef?.values.orEmpty()
                                    val customNode = cfg.findByName(descriptor.name)
                                    children.forEachIndexed { index, child ->
                                        RawConfigRow(
                                            descriptor = child,
                                            parent = customNode,
                                            onNavigate = onNavigate,
                                            onSave = onSave,
                                            onMutated = { cfgVersion++ },
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
                                    ),
                                    colors = CardDefaults.defaultColors(
                                        color = MiuixTheme.colorScheme.surfaceContainerHighest,
                                    ),
                                ) {
                                    RawConfigRow(
                                        descriptor = descriptor,
                                        parent = cfg,
                                        onNavigate = onNavigate,
                                        onSave = onSave,
                                        onMutated = { cfgVersion++ },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        SmallTopAppBar(
            title = topLevel?.name ?: context.getString(R.string.global_options),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(MiuixIcons.Back, contentDescription = null, modifier = Modifier.size(24.dp))
                }
            },
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
        )
    }
}


/** Node lookup inherited from the old FcitxRawConfigStore without the non-null assertion. */
private fun RawConfig?.node(name: String): RawConfig? = this?.findByName(name)

@Composable
private fun RawConfigRow(
    descriptor: ConfigDescriptor<*, *>,
    parent: RawConfig?,
    onNavigate: (AppRoute) -> Unit,
    onSave: () -> Unit,
    onMutated: () -> Unit,
) {
    val context = LocalContext.current
    val title = descriptor.description ?: descriptor.name
    val node = parent.node(descriptor.name)

    if (node == null) {
        ArrowPreference(
            title = title,
            summary = descriptor.tooltip ?: context.getString(R.string.unimplemented_type),
            enabled = false,
            onClick = null,
        )
        return
    }

    fun write(value: String) {
        node.value = value
        onSave()
        onMutated()
    }

    when (descriptor) {
        is ConfigBool -> {
            var v by remember(node) { mutableStateOf(node.value == "True") }
            SwitchPreference(
                title = title,
                summary = descriptor.tooltip,
                checked = v,
                onCheckedChange = { new ->
                    v = new
                    write(if (new) "True" else "False")
                },
            )
        }

        is ConfigEnum -> {
            val entries = descriptor.entriesI18n ?: descriptor.entries
            val index = descriptor.entries.indexOf(node.value).coerceAtLeast(0)
            WindowDropdownPreference(
                items = entries,
                selectedIndex = index,
                title = title,
                summary = descriptor.tooltip,
                onSelectedIndexChange = { i -> write(descriptor.entries[i]) },
            )
        }

        is ConfigInt -> {
            val min = descriptor.intMin
            val max = descriptor.intMax
            var showDialog by remember { mutableStateOf(false) }
            var v by remember(node) { mutableStateOf(node.value.toIntOrNull() ?: 0) }
            if (min != null && max != null && max > min) {
                ExpandableNumberPreference(
                    title = title,
                    value = v,
                    onValueChange = { newValue ->
                        v = newValue
                        write(newValue.toString())
                    },
                    min = min,
                    max = max,
                    step = if (max - min > 1000) 10 else 1,
                )
            } else {
                ArrowPreference(
                    title = title,
                    summary = v.toString(),
                    onClick = { showDialog = true },
                    holdDownState = showDialog,
                )
            }
            if (showDialog) {
                EditValueDialog(
                    title = title,
                    initialText = v.toString(),
                    onConfirm = { text ->
                        val parsed = text.toIntOrNull() ?: return@EditValueDialog false
                        if (min != null && parsed < min) return@EditValueDialog false
                        if (max != null && parsed > max) return@EditValueDialog false
                        v = parsed
                        write(parsed.toString())
                        true
                    },
                    onDismiss = { showDialog = false },
                )
            }
        }

        is ConfigString -> {
            var showDialog by remember { mutableStateOf(false) }
            var v by remember(node) { mutableStateOf(node.value) }
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
                        write(text)
                        true
                    },
                    onDismiss = { showDialog = false },
                )
            }
        }

        is ConfigKey -> {
            var showDialog by remember { mutableStateOf(false) }
            var v by remember(node) { mutableStateOf(node.value) }
            val localized = runCatching {
                org.fcitx.fcitx5.android.core.Key.parse(v).localizedString
            }.getOrDefault(v)
            ArrowPreference(
                title = title,
                summary = localized.ifEmpty { context.getString(R.string.none) },
                onClick = { showDialog = true },
                holdDownState = showDialog,
            )
            if (showDialog) {
                KeyCaptureDialog(
                    title = title,
                    initialKey = v,
                    onConfirm = { serialized ->
                        v = serialized
                        write(serialized)
                    },
                    onDismiss = { showDialog = false },
                )
            }
        }

        is ConfigEnumList -> {
            var showDialog by remember { mutableStateOf(false) }
            val current = node.subItems?.map { it.value }.orEmpty()
            ArrowPreference(
                title = title,
                summary = current.joinToString(", ") {
                    descriptor.entriesI18n?.getOrNull(descriptor.entries.indexOf(it)) ?: it
                }.ifEmpty { context.getString(R.string.none) },
                onClick = { showDialog = true },
                holdDownState = showDialog,
            )
            if (showDialog) {
                RawConfigListEditDialog(
                    title = title,
                    initialEntries = current,
                    mode = RawListEditMode.Choices(descriptor.entries, descriptor.entriesI18n),
                    onConfirm = { values ->
                        node.subItems = values.mapIndexed { i, v ->
                            RawConfig(i.toString(), v)
                        }.toTypedArray()
                        onSave()
                    },
                    onDismiss = { showDialog = false },
                )
            }
        }

        is ConfigList -> {
            val subtype = (descriptor.ty as? org.fcitx.fcitx5.android.utils.config.ConfigType.TyList)?.subtype
            val editMode = when (subtype) {
                org.fcitx.fcitx5.android.utils.config.ConfigType.TyBool -> RawListEditMode.Bool
                org.fcitx.fcitx5.android.utils.config.ConfigType.TyInt -> RawListEditMode.Number
                org.fcitx.fcitx5.android.utils.config.ConfigType.TyString -> RawListEditMode.FreeText
                org.fcitx.fcitx5.android.utils.config.ConfigType.TyKey -> RawListEditMode.Key
                else -> null
            }
            if (editMode == null) {
                ArrowPreference(
                    title = title,
                    summary = descriptor.tooltip ?: context.getString(R.string.unimplemented_type),
                    enabled = false,
                    onClick = null,
                )
                return
            }
            var showDialog by remember { mutableStateOf(false) }
            val current = node.subItems?.map { it.value }.orEmpty()
            ArrowPreference(
                title = title,
                summary = current.joinToString(", ").ifEmpty { context.getString(R.string.none) },
                onClick = { showDialog = true },
                holdDownState = showDialog,
            )
            if (showDialog) {
                RawConfigListEditDialog(
                    title = title,
                    initialEntries = current,
                    mode = editMode,
                    onConfirm = { values ->
                        node.subItems = values.mapIndexed { i, v ->
                            RawConfig(i.toString(), v)
                        }.toTypedArray()
                        onSave()
                    },
                    onDismiss = { showDialog = false },
                )
            }
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
                ConfigExternal.ETy.Chttrans -> ArrowPreference(
                    title = title,
                    summary = descriptor.tooltip,
                    onClick = { onNavigate(AppRoute.RawConfigHost(RawConfigHostType.AddonConfig, "chttrans")) },
                )
                ConfigExternal.ETy.TableGlobal -> ArrowPreference(
                    title = title,
                    summary = descriptor.tooltip,
                    onClick = { onNavigate(AppRoute.RawConfigHost(RawConfigHostType.AddonConfig, "table")) },
                )
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
