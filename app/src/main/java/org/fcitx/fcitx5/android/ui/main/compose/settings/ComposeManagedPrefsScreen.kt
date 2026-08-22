/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.settings

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceCategory
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceUi
import org.fcitx.fcitx5.android.ui.main.compose.dialog.EditValueDialog
import org.fcitx.fcitx5.android.ui.main.settings.EditTextFloatUi
import org.fcitx.fcitx5.android.utils.InputMethodUtil
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults

import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.res.stringResource

/**
 * Compose renderer for a [ManagedPreferenceCategory]. Consumes the shared preference metadata
 * (defined in AppPrefs) and renders it with Miuix preference components.
 *
 * The category itself stays untouched (upstream file): values are read/written through
 * [ManagedPreferenceProvider.managedPreferences] and change events are observed via the
 * provider's [ManagedPreferenceProvider.OnChangeListener], restoring the old
 * ["fireChange" flow][ManagedPreferenceProvider.fireChange].
 */
private fun uiTitleString(context: Context, ui: ManagedPreferenceUi<*>): String = when (ui) {
    is ManagedPreferenceUi.Switch -> context.getString(ui.title)
    is ManagedPreferenceUi.StringList<*> -> context.getString(ui.title)
    is ManagedPreferenceUi.VoiceInputList -> context.getString(ui.title)
    is ManagedPreferenceUi.EditTextInt -> context.getString(ui.title)
    is EditTextFloatUi -> context.getString(ui.title)
    is ManagedPreferenceUi.SeekBarInt -> context.getString(ui.title)
    is ManagedPreferenceUi.TwinSeekBarInt -> context.getString(ui.title)
    else -> ""
}

@Composable
fun ManagedPrefsScreen(
    category: ManagedPreferenceCategory,
    onBack: () -> Unit,
    highlightKey: String? = null,
    showTopBar: Boolean = true,
) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(category) {
        val listener = object : ManagedPreferenceProvider.OnChangeListener {
            override fun onChange(key: String) {
                version += 1
            }
        }
        category.registerOnChangeListener(listener)
        onDispose { category.unregisterOnChangeListener(listener) }
    }

    val prefs = category.managedPreferences
    val uiList = category.managedPreferencesUi
    val uiMap = uiList.associateBy { it.key }
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val highlightColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.15f)

    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
        LazyColumn(
            contentPadding = PaddingValues(
                top = (if (showTopBar) 64.dp else 12.dp) + topInset
            ),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (category.groups.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        colors = CardDefaults.defaultColors(
                            color = MiuixTheme.colorScheme.surfaceContainerHighest,
                        ),
                    ) {
                        uiList.forEachIndexed { index, ui ->
                            val isHighlight = uiTitleString(context, ui) == highlightKey
                            Box(
                                Modifier.background(
                                    if (isHighlight) highlightColor else Color.Transparent
                                ),
                            ) {
                                ManagedPrefRow(ui, prefs, version, category::fireChange)
                            }
                            if (index < uiList.lastIndex) HorizontalDivider()
                        }
                    }
                }
            } else {
                category.groups.forEach { group ->
                    item {
                        SmallTitle(text = stringResource(group.title))
                    }
                    item {
                        Card(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        colors = CardDefaults.defaultColors(
                            color = MiuixTheme.colorScheme.surfaceContainerHighest,
                        ),
                    ) {
                            val keys = group.keys.filter { uiMap.containsKey(it) }
                            keys.forEachIndexed { index, key ->
                                val ui = uiMap.getValue(key)
                                Box(
                                    Modifier.background(
                                        if (uiTitleString(context, ui) == highlightKey) highlightColor
                                        else Color.Transparent
                                    ),
                                ) {
                                    ManagedPrefRow(ui, prefs, version, category::fireChange)
                                }
                                if (index < keys.lastIndex) HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
        if (showTopBar) {
            SmallTopAppBar(
                color = MiuixTheme.colorScheme.surfaceContainer,
                title = stringResource(category.title),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(MiuixIcons.Back, contentDescription = null, modifier = Modifier.size(24.dp))
                    }
                },
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ManagedPrefRow(
    ui: ManagedPreferenceUi<*>,
    prefs: Map<String, ManagedPreference<*>>,
    version: Int,
    fireChange: (String) -> Unit,
) {
    val context = LocalContext.current
    when (ui) {
        is ManagedPreferenceUi.Switch -> {
            val pref = prefs[ui.key] as? ManagedPreference.PBool ?: return
            var checked by remember(version) { mutableStateOf(pref.getValue()) }
            SwitchPreference(
                title = stringResource(ui.title),
                summary = ui.summary?.let { stringResource(it) },
                checked = checked,
                onCheckedChange = { newValue ->
                    checked = newValue
                    pref.setValue(newValue)
                    fireChange(ui.key)
                },
                enabled = ui.isEnabled(),
            )
        }

        is ManagedPreferenceUi.StringList<*> -> {
            val pref = prefs[ui.key] as? ManagedPreference.PStringLike<*> ?: return
            val currentValue = pref.getValue()
            val currentIndex = ui.entryValues.indexOf(currentValue).coerceAtLeast(0)
            WindowDropdownPreference(
                items = ui.entryValues.mapIndexed { index, _ ->
                    stringResource(ui.entryLabels[index])
                },
                selectedIndex = currentIndex,
                title = stringResource(ui.title),
                enabled = ui.isEnabled(),
                onSelectedIndexChange = { index ->
                    @Suppress("UNCHECKED_CAST")
                    (pref as ManagedPreference.PStringLike<Any>)
                        .setValue(ui.entryValues[index])
                    fireChange(ui.key)
                },
            )
        }

        is ManagedPreferenceUi.VoiceInputList -> {
            val pref = prefs[ui.key] as? ManagedPreference.PString ?: return
            val voiceInputMethods = InputMethodUtil.listVoiceInputMethods()
            val labels = listOf(stringResource(R.string.system_default)) +
                voiceInputMethods.map { it.first.loadLabel(context.packageManager).toString() }
            val values = listOf("") + voiceInputMethods.map { it.first.id }
            val currentIndex = values.indexOf(pref.getValue()).coerceAtLeast(0)
            WindowDropdownPreference(
                items = labels,
                selectedIndex = currentIndex,
                title = stringResource(ui.title),
                enabled = ui.isEnabled(),
                onSelectedIndexChange = { index ->
                    pref.setValue(values[index])
                    fireChange(ui.key)
                },
            )
        }

        is ManagedPreferenceUi.EditTextInt -> {
            val pref = prefs[ui.key] as? ManagedPreference.PInt ?: return
            var showDialog by remember { mutableStateOf(false) }
            val hasRange = ui.min < ui.max && (ui.max - ui.min) <= 10000
            if (hasRange) {
                ExpandableNumberPreference(
                    title = stringResource(ui.title),
                    value = pref.getValue(),
                    onValueChange = { newValue ->
                        pref.setValue(newValue.coerceIn(ui.min, ui.max))
                        fireChange(ui.key)
                    },
                    min = ui.min,
                    max = ui.max,
                    step = 1,
                    suffix = ui.unit,
                    enabled = ui.isEnabled(),
                )
            } else {
                ArrowPreference(
                    title = stringResource(ui.title),
                    summary = "${pref.getValue()}${ui.unit}",
                    onClick = { showDialog = true },
                    holdDownState = showDialog,
                    enabled = ui.isEnabled(),
                )
            }
            if (showDialog) {
                EditValueDialog(
                    title = stringResource(ui.title),
                    initialText = pref.getValue().toString(),
                    onConfirm = { text ->
                        val parsed = text.toIntOrNull() ?: return@EditValueDialog false
                        if (parsed !in ui.min..ui.max) return@EditValueDialog false
                        pref.setValue(parsed)
                        fireChange(ui.key)
                        true
                    },
                    onDismiss = { showDialog = false },
                )
            }
        }

        is ManagedPreferenceUi.SeekBarInt -> {
            val pref = prefs[ui.key] as? ManagedPreference.PInt ?: return
            ExpandableNumberPreference(
                title = stringResource(ui.title),
                value = pref.getValue(),
                onValueChange = { newValue ->
                    val stepped =
                        ui.min + ((newValue - ui.min) / ui.step).toLong() * ui.step
                    pref.setValue(stepped.coerceIn(ui.min.toLong(), ui.max.toLong()).toInt())
                    fireChange(ui.key)
                },
                min = ui.min,
                max = ui.max,
                step = ui.step,
                suffix = ui.unit,
                enabled = ui.isEnabled(),
            )
        }

        is ManagedPreferenceUi.TwinSeekBarInt -> {
            val primaryPref = prefs[ui.key] as? ManagedPreference.PInt ?: return
            val secondaryPref = prefs[ui.secondaryKey] as? ManagedPreference.PInt ?: return
            var expanded by remember { mutableStateOf(false) }
            BasicComponent(
                title = stringResource(ui.title),
                summary = "${primaryPref.getValue()}${ui.unit} / ${secondaryPref.getValue()}${ui.unit}",
                onClick = { expanded = !expanded },
                holdDownState = expanded,
                enabled = ui.isEnabled(),
                bottomAction = {
                    AnimatedVisibility(expanded && ui.isEnabled()) {
                        Column {
                            ExpandableNumberPreference(
                                title = stringResource(ui.label),
                                value = primaryPref.getValue(),
                                onValueChange = { newValue ->
                                    primaryPref.setValue(newValue)
                                    fireChange(ui.key)
                                },
                                min = ui.min,
                                max = ui.max,
                                step = ui.step,
                                suffix = ui.unit,
                            )
                            ExpandableNumberPreference(
                                title = stringResource(ui.secondaryLabel),
                                value = secondaryPref.getValue(),
                                onValueChange = { newValue ->
                                    secondaryPref.setValue(newValue)
                                    fireChange(ui.secondaryKey)
                                },
                                min = ui.min,
                                max = ui.max,
                                step = ui.step,
                                suffix = ui.unit,
                            )
                        }
                    }
                },
            )
        }

        is EditTextFloatUi -> {
            val pref = prefs[ui.key] as? ManagedPreference.PFloat ?: return
            var showDialog by remember { mutableStateOf(false) }
            ArrowPreference(
                title = stringResource(ui.title),
                summary = pref.getValue().toString() + ui.unit,
                onClick = { showDialog = true },
                holdDownState = showDialog,
                enabled = ui.isEnabled(),
            )
            if (showDialog) {
                EditValueDialog(
                    title = stringResource(ui.title),
                    initialText = pref.getValue().toString(),
                    onConfirm = { text ->
                        val parsed = text.toFloatOrNull() ?: return@EditValueDialog false
                        if (parsed < ui.min || parsed > ui.max) return@EditValueDialog false
                        pref.setValue(parsed)
                        fireChange(ui.key)
                        true
                    },
                    onDismiss = { showDialog = false },
                )
            }
        }
    }
}
