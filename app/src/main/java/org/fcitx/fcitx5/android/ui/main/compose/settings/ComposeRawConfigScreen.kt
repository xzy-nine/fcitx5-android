/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.settings

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import arrow.core.getOrElse
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.Key
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.ui.main.compose.AppRoute
import org.fcitx.fcitx5.android.ui.main.compose.RawConfigHostType
import org.fcitx.fcitx5.android.ui.main.compose.dialog.EditValueDialog
import org.fcitx.fcitx5.android.ui.main.compose.dialog.KeyCaptureDialog
import org.fcitx.fcitx5.android.ui.main.compose.dialog.RawConfigListEditDialog
import org.fcitx.fcitx5.android.ui.main.compose.dialog.RawListEditMode
import org.fcitx.fcitx5.android.ui.main.compose.dialog.SimpleConfirmDialog
import org.fcitx.fcitx5.android.ui.main.compose.screens.PageScaffold
import org.fcitx.fcitx5.android.utils.buildDocumentsProviderIntent
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor.ConfigCustom
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor.ConfigExternal
import org.fcitx.fcitx5.android.utils.config.ConfigType
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Compose renderer for fcitx's dynamic RawConfig pages (replaces the View-based
 * PreferenceScreenFactory rendering).
 *
 * Every [ConfigDescriptor] kind is rendered: Bool / Enum / Int / String / Key / EnumList / List /
 * External / nested Custom. Row shape is decided by [RawConfigRows.specOf], i.e. from the
 * descriptor alone — **not** from the presence of a value node — because fcitx skips saving
 * `External`/`SubConfig` options (so they never exist under `cfg`) while they still must open a
 * sub-editor. Missing nodes fall back to the descriptor default and are created on first write by
 * [RawConfigValue]; only genuinely unrenderable descriptors (unknown list subtype, custom type
 * without a definition) show the "unimplemented type" placeholder.
 *
 * @param currentAddon unique name of the addon whose config is displayed, used to avoid an
 * external row navigating back into the very same page.
 */
@Composable
fun RawConfigScreen(
    raw: RawConfig,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    titleOverride: String? = null,
    currentAddon: String? = null,
) {
    // A failed load (unknown addon / input method) yields an empty RawConfig; never assert here.
    val cfg = raw.findByName("cfg")
    val desc = raw.findByName("desc")
    val topLevel = remember(desc) {
        desc?.let { ConfigDescriptor.parseTopLevel(it).getOrElse { null } }
    }

    PageScaffold(
        title = titleOverride ?: topLevel?.name ?: stringResource(R.string.global_options),
        onBack = onBack,
    ) {
        if (topLevel == null || cfg == null) {
            item {
                Box(Modifier.padding(16.dp)) {
                    Text(text = stringResource(R.string.failed_to_load_config))
                }
            }
        } else {
            topLevel.values.forEach { descriptor ->
                if (RawConfigRows.specOf(descriptor) == RawConfigRowSpec.Group) {
                    item { SmallTitle(text = descriptor.description ?: descriptor.name) }
                    item {
                        ConfigGroupCard(
                            descriptor as ConfigCustom, cfg, currentAddon, onNavigate, onSave
                        )
                    }
                } else {
                    item {
                        Card(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            colors = CardDefaults.defaultColors(
                                color = MiuixTheme.colorScheme.surfaceContainerHighest,
                            ),
                        ) {
                            RawConfigRow(descriptor, cfg, currentAddon, onNavigate, onSave)
                        }
                    }
                }
            }
        }
    }
}

/** Card holding the members of a `Custom` descriptor (fcitx custom type / nested config). */
@Composable
private fun ConfigGroupCard(
    descriptor: ConfigCustom,
    parent: RawConfig,
    currentAddon: String?,
    onNavigate: (AppRoute) -> Unit,
    onSave: () -> Unit,
) {
    val title = descriptor.description ?: descriptor.name
    val children = descriptor.customTypeDef?.values
    Card(
        modifier = Modifier.padding(horizontal = 12.dp),
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        if (children == null) {
            UnsupportedRow(
                title = title,
                tooltip = descriptor.tooltip,
                typeName = ConfigType.pretty(descriptor.ty),
            )
        } else {
            val group = remember(parent, descriptor.name) { parent.groupNode(descriptor.name) }
            children.forEach { child ->
                RawConfigRow(child, group, currentAddon, onNavigate, onSave)
            }
        }
    }
}

/** Node of a custom group, created on demand when fcitx did not save it. */
private fun RawConfig.groupNode(name: String): RawConfig =
    findByName(name) ?: getOrCreate(name)

@Composable
private fun RawConfigRow(
    descriptor: ConfigDescriptor<*, *>,
    parent: RawConfig,
    currentAddon: String?,
    onNavigate: (AppRoute) -> Unit,
    onSave: () -> Unit,
) {
    val context = LocalContext.current
    val title = descriptor.description ?: descriptor.name
    val accessor = remember(parent, descriptor.name) { RawConfigValue(parent, descriptor.name) }
    // equality of RawConfig is content based, so this key also refreshes the local state right
    // after a write (the freshly written node carries the new value)
    val node = accessor.node
    val fallback = RawConfigRows.defaultValueOf(descriptor)

    fun write(value: String) {
        accessor.write(value)
        onSave()
    }

    when (val spec = RawConfigRows.specOf(descriptor)) {
        RawConfigRowSpec.Bool -> {
            var checked by remember(node) { mutableStateOf(accessor.read(fallback) == "True") }
            SwitchPreference(
                title = title,
                summary = descriptor.tooltip,
                checked = checked,
                onCheckedChange = { new ->
                    checked = new
                    write(if (new) "True" else "False")
                },
            )
        }

        is RawConfigRowSpec.Enum -> {
            val entries = descriptor as ConfigDescriptor.ConfigEnum
            var index by remember(node) {
                mutableIntStateOf(entries.entries.indexOf(accessor.read(fallback)).coerceAtLeast(0))
            }
            WindowDropdownPreference(
                items = spec.entriesI18n ?: spec.entries,
                selectedIndex = index,
                title = title,
                summary = descriptor.tooltip,
                onSelectedIndexChange = { i ->
                    index = i
                    write(entries.entries[i])
                },
            )
        }

        is RawConfigRowSpec.Number -> {
            val min = spec.min
            val max = spec.max
            var showDialog by remember { mutableStateOf(false) }
            var v by remember(node) { mutableIntStateOf(accessor.read(fallback).toIntOrNull() ?: 0) }
            if (min != null && max != null) {
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

        RawConfigRowSpec.Text -> {
            var showDialog by remember { mutableStateOf(false) }
            var v by remember(node) { mutableStateOf(accessor.read(fallback)) }
            ArrowPreference(
                title = title,
                summary = v.ifEmpty { stringResource(R.string.none) },
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

        RawConfigRowSpec.Key -> {
            var showDialog by remember { mutableStateOf(false) }
            var v by remember(node) { mutableStateOf(accessor.read(fallback)) }
            val localized = runCatching { Key.parse(v).localizedString }.getOrDefault(v)
            ArrowPreference(
                title = title,
                summary = localized.ifEmpty { stringResource(R.string.none) },
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

        is RawConfigRowSpec.EnumList -> {
            var showDialog by remember { mutableStateOf(false) }
            var current by remember(node) {
                mutableStateOf(accessor.readValues(RawConfigRows.defaultListOf(descriptor)))
            }
            ArrowPreference(
                title = title,
                summary = current.joinToString(", ") {
                    spec.entriesI18n?.getOrNull(spec.entries.indexOf(it)) ?: it
                }.ifEmpty { stringResource(R.string.none) },
                onClick = { showDialog = true },
                holdDownState = showDialog,
            )
            if (showDialog) {
                RawConfigListEditDialog(
                    title = title,
                    initialEntries = current,
                    mode = RawListEditMode.Choices(spec.entries, spec.entriesI18n),
                    onConfirm = { values ->
                        current = values
                        accessor.writeValues(values)
                        onSave()
                    },
                    onDismiss = { showDialog = false },
                )
            }
        }

        is RawConfigRowSpec.ListEditor -> {
            var showDialog by remember { mutableStateOf(false) }
            var current by remember(node) {
                mutableStateOf(accessor.readValues(RawConfigRows.defaultListOf(descriptor)))
            }
            val displayed = if (spec.mode == RawListEditMode.Key) {
                current.map { runCatching { Key.parse(it).localizedString }.getOrDefault(it) }
            } else {
                current
            }
            ArrowPreference(
                title = title,
                summary = displayed.joinToString(", ").ifEmpty { stringResource(R.string.none) },
                onClick = { showDialog = true },
                holdDownState = showDialog,
            )
            if (showDialog) {
                RawConfigListEditDialog(
                    title = title,
                    initialEntries = current,
                    mode = spec.mode,
                    onConfirm = { values ->
                        current = values
                        accessor.writeValues(values)
                        onSave()
                    },
                    onDismiss = { showDialog = false },
                )
            }
        }

        RawConfigRowSpec.External -> {
            val external = descriptor as ConfigExternal
            val action = remember(external, currentAddon) {
                RawConfigRows.externalActionOf(external, currentAddon)
            }
            val route = remember(action) { action.routeOrNull() }
            var showRimeDialog by remember { mutableStateOf(false) }
            val resolved = action !is RawConfigExternalAction.Unresolved
            ArrowPreference(
                title = title,
                summary = if (resolved) {
                    descriptor.tooltip
                } else {
                    unsupportedSummary(descriptor.tooltip, "External")
                },
                enabled = resolved,
                holdDownState = showRimeDialog,
                onClick = when {
                    route != null -> { { onNavigate(route) } }
                    action is RawConfigExternalAction.RimeUserDataDir -> ({ showRimeDialog = true })
                    else -> null
                },
            )
            if (showRimeDialog) {
                SimpleConfirmDialog(
                    title = title,
                    message = stringResource(R.string.open_rime_user_data_dir),
                    onConfirm = {
                        showRimeDialog = false
                        try {
                            context.startActivity(buildDocumentsProviderIntent())
                        } catch (e: Exception) {
                            Log.w("RawConfigScreen", "open rime dir failed", e)
                        }
                    },
                    onDismiss = { showRimeDialog = false },
                )
            }
        }

        RawConfigRowSpec.Group -> {
            val custom = descriptor as ConfigCustom
            val children = custom.customTypeDef?.values
            if (children == null) {
                UnsupportedRow(title, descriptor.tooltip, ConfigType.pretty(descriptor.ty))
            } else {
                // nested custom type inside a group card: keep rendering its members
                val group = remember(parent, descriptor.name) { parent.groupNode(descriptor.name) }
                Column {
                    children.forEach { child ->
                        RawConfigRow(child, group, currentAddon, onNavigate, onSave)
                    }
                }
            }
        }

        is RawConfigRowSpec.Unsupported -> UnsupportedRow(title, descriptor.tooltip, spec.typeName)
    }
}

/** Placeholder for descriptors without an in-app editor (unknown list/custom type). */
@Composable
private fun UnsupportedRow(title: String, tooltip: String?, typeName: String) {
    ArrowPreference(
        title = title,
        summary = unsupportedSummary(tooltip, typeName),
        enabled = false,
        onClick = null,
    )
}

@Composable
private fun unsupportedSummary(tooltip: String?, typeName: String): String =
    listOfNotNull(tooltip, "${stringResource(R.string.unimplemented_type)} '$typeName'")
        .joinToString("\n")

/** Destination of a resolved external row; `null` when it needs a local dialog instead. */
private fun RawConfigExternalAction.routeOrNull(): AppRoute? = when (this) {
    RawConfigExternalAction.PinyinDictionary -> AppRoute.PinyinDictionary("")
    is RawConfigExternalAction.Punctuation -> AppRoute.Punctuation(title, lang)
    RawConfigExternalAction.QuickPhrase -> AppRoute.QuickPhraseList
    RawConfigExternalAction.PinyinCustomPhrase -> AppRoute.PinyinCustomPhrase
    RawConfigExternalAction.TableInputMethods -> AppRoute.TableInputMethods
    // the addon name must go into `uniqueName`: RawConfigHostScreen reads it as the addon id
    is RawConfigExternalAction.AddonConfig ->
        AppRoute.RawConfigHost(RawConfigHostType.AddonConfig, name = addon, uniqueName = addon)
    RawConfigExternalAction.RimeUserDataDir -> null
    is RawConfigExternalAction.Unresolved -> null
}
