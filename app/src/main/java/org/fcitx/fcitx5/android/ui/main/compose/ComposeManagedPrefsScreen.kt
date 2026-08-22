/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceCategory
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceUi
import org.fcitx.fcitx5.android.ui.main.settings.EditTextFloatUi
import org.fcitx.fcitx5.android.utils.InputMethodUtil
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowSpinnerPreference

/**
 * Compose renderer for a [ManagedPreferenceCategory]. Consumes the shared preference metadata
 * (defined in AppPrefs) and renders it with Miuix preference components.
 *
 * The category itself stays untouched (upstream file): values are read/written through
 * [ManagedPreferenceProvider.managedPreferences] and change events are observed via the
 * provider's [ManagedPreferenceProvider.OnChangeListener], restoring the old
 * ["fireChange" flow][ManagedPreferenceProvider.fireChange].
 */
@Composable
fun ManagedPrefsScreen(category: ManagedPreferenceCategory, onBack: () -> Unit) {
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

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(top = 64.dp + topInset),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (category.groups.isEmpty()) {
                item {
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        uiList.forEachIndexed { index, ui ->
                            ManagedPrefRow(ui, prefs)
                            if (index < uiList.lastIndex) HorizontalDivider()
                        }
                    }
                }
            } else {
                category.groups.forEach { group ->
                    item {
                        SmallTitle(text = context.getString(group.title))
                    }
                    item {
                        Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                            val keys = group.keys.filter { uiMap.containsKey(it) }
                            keys.forEachIndexed { index, key ->
                                ManagedPrefRow(uiMap.getValue(key), prefs)
                                if (index < keys.lastIndex) HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
        SmallTopAppBar(
            title = context.getString(category.title),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(MiuixIcons.Back, contentDescription = null, modifier = Modifier.size(24.dp))
                }
            },
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
        )
    }
}

@Composable
private fun ManagedPrefRow(ui: ManagedPreferenceUi<*>, prefs: Map<String, ManagedPreference<*>>) {
    val context = LocalContext.current
    when (ui) {
        is ManagedPreferenceUi.Switch -> {
            val pref = prefs[ui.key] as? ManagedPreference.PBool ?: return
            SwitchPreference(
                title = context.getString(ui.title),
                summary = ui.summary?.let { context.getString(it) },
                checked = pref.getValue(),
                onCheckedChange = { newValue -> pref.setValue(newValue) },
                enabled = ui.isEnabled(),
            )
        }

        is ManagedPreferenceUi.StringList<*> -> {
            val pref = prefs[ui.key] as? ManagedPreference.PStringLike<*> ?: return
            val currentValue = pref.getValue()
            val currentIndex = ui.entryValues.indexOf(currentValue).coerceAtLeast(0)
            WindowSpinnerPreference(
                items = ui.entryValues.mapIndexed { index, _ ->
                    DropdownItem(text = context.getString(ui.entryLabels[index]))
                },
                selectedIndex = currentIndex,
                title = context.getString(ui.title),
                dialogButtonString = context.getString(android.R.string.ok),
                enabled = ui.isEnabled(),
                onSelectedIndexChange = { index ->
                    @Suppress("UNCHECKED_CAST")
                    (pref as ManagedPreference.PStringLike<Any>)
                        .setValue(ui.entryValues[index])
                },
            )
        }

        is ManagedPreferenceUi.VoiceInputList -> {
            val pref = prefs[ui.key] as? ManagedPreference.PString ?: return
            val voiceInputMethods = InputMethodUtil.listVoiceInputMethods()
            val labels = listOf(context.getString(R.string.system_default)) +
                voiceInputMethods.map { it.first.loadLabel(context.packageManager).toString() }
            val values = listOf("") + voiceInputMethods.map { it.first.id }
            val currentIndex = values.indexOf(pref.getValue()).coerceAtLeast(0)
            WindowSpinnerPreference(
                items = labels.map { DropdownItem(text = it) },
                selectedIndex = currentIndex,
                title = context.getString(ui.title),
                dialogButtonString = context.getString(android.R.string.ok),
                enabled = ui.isEnabled(),
                onSelectedIndexChange = { index -> pref.setValue(values[index]) },
            )
        }

        is ManagedPreferenceUi.EditTextInt -> {
            val pref = prefs[ui.key] as? ManagedPreference.PInt ?: return
            var showDialog by remember { mutableStateOf(false) }
            ArrowPreference(
                title = context.getString(ui.title),
                summary = "${pref.getValue()}${ui.unit}",
                onClick = { showDialog = true },
                holdDownState = showDialog,
                enabled = ui.isEnabled(),
            )
            if (showDialog) {
                EditValueDialog(
                    title = context.getString(ui.title),
                    initialText = pref.getValue().toString(),
                    onConfirm = { text ->
                        val parsed = text.toIntOrNull() ?: return@EditValueDialog false
                        if (parsed !in ui.min..ui.max) return@EditValueDialog false
                        pref.setValue(parsed)
                        true
                    },
                    onDismiss = { showDialog = false },
                )
            }
        }

        is ManagedPreferenceUi.SeekBarInt -> {
            val pref = prefs[ui.key] as? ManagedPreference.PInt ?: return
            val range = ui.min.toFloat()..ui.max.toFloat()
            val steps = ((ui.max.toLong() - ui.min) / ui.step - 1).coerceIn(0, 1000).toInt()
            SliderPreference(
                title = context.getString(ui.title),
                value = pref.getValue().toFloat(),
                onValueChange = { newValue ->
                    val stepped = ui.min + ((newValue - ui.min) / ui.step).toLong() * ui.step
                    pref.setValue(stepped.coerceIn(ui.min.toLong(), ui.max.toLong()).toInt())
                },
                valueText = pref.getValue().toString() + ui.unit,
                valueRange = range,
                steps = steps,
                enabled = ui.isEnabled(),
            )
        }

        is ManagedPreferenceUi.TwinSeekBarInt -> {
            val primaryPref = prefs[ui.key] as? ManagedPreference.PInt ?: return
            val secondaryPref = prefs[ui.secondaryKey] as? ManagedPreference.PInt ?: return
            val range = ui.min.toFloat()..ui.max.toFloat()
            val steps = ((ui.max.toLong() - ui.min) / ui.step - 1).coerceIn(0, 1000).toInt()
            ArrowPreference(
                title = context.getString(ui.title),
                bottomAction = {
                    Column {
                        SliderPreference(
                            title = context.getString(ui.label),
                            value = primaryPref.getValue().toFloat(),
                            onValueChange = { newValue ->
                                val stepped = ui.min + ((newValue - ui.min) / ui.step).toLong() * ui.step
                                primaryPref.setValue(stepped.coerceIn(ui.min.toLong(), ui.max.toLong()).toInt())
                            },
                            valueText = primaryPref.getValue().toString() + ui.unit,
                            valueRange = range,
                            steps = steps,
                            enabled = ui.isEnabled(),
                        )
                        SliderPreference(
                            title = context.getString(ui.secondaryLabel),
                            value = secondaryPref.getValue().toFloat(),
                            onValueChange = { newValue ->
                                val stepped = ui.min + ((newValue - ui.min) / ui.step).toLong() * ui.step
                                secondaryPref.setValue(stepped.coerceIn(ui.min.toLong(), ui.max.toLong()).toInt())
                            },
                            valueText = secondaryPref.getValue().toString() + ui.unit,
                            valueRange = range,
                            steps = steps,
                            enabled = ui.isEnabled(),
                        )
                    }
                },
                enabled = ui.isEnabled(),
            )
        }

        is EditTextFloatUi -> {
            val pref = prefs[ui.key] as? ManagedPreference.PFloat ?: return
            var showDialog by remember { mutableStateOf(false) }
            ArrowPreference(
                title = context.getString(ui.title),
                summary = pref.getValue().toString() + ui.unit,
                onClick = { showDialog = true },
                holdDownState = showDialog,
                enabled = ui.isEnabled(),
            )
            if (showDialog) {
                EditValueDialog(
                    title = context.getString(ui.title),
                    initialText = pref.getValue().toString(),
                    onConfirm = { text ->
                        val parsed = text.toFloatOrNull() ?: return@EditValueDialog false
                        if (parsed < ui.min || parsed > ui.max) return@EditValueDialog false
                        pref.setValue(parsed)
                        true
                    },
                    onDismiss = { showDialog = false },
                )
            }
        }
    }
}
