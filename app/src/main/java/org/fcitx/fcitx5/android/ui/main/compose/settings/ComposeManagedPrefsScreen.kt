/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.settings

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.UserDataImportCompat
import org.fcitx.fcitx5.android.data.UserDataManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceCategory
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceUi
import org.fcitx.fcitx5.android.ui.main.compose.dialog.EditValueDialog
import org.fcitx.fcitx5.android.ui.main.compose.dialog.SimpleConfirmDialog
import org.fcitx.fcitx5.android.ui.main.compose.screens.PageScaffold
import org.fcitx.fcitx5.android.ui.main.settings.EditTextFloatUi
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.buildDocumentsProviderIntent
import org.fcitx.fcitx5.android.utils.formatDateTime
import org.fcitx.fcitx5.android.utils.importErrorDialog
import org.fcitx.fcitx5.android.utils.iso8601UTCDateTime
import org.fcitx.fcitx5.android.utils.queryFileName
import org.fcitx.fcitx5.android.utils.toast
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Backup
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.Import
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Timer
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.icon.extended.UploadCloud
import top.yukonga.miuix.kmp.icon.extended.VerticalSplit
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import kotlin.time.Duration.Companion.milliseconds

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
    onWebDavSync: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isAdvanced = category === AppPrefs.getInstance().advanced
    val fcitx = if (isAdvanced) remember { FcitxDaemon.connect("compose-advanced-settings") } else null
    var version by remember { mutableIntStateOf(0) }
    var exportTime by remember { mutableLongStateOf(0L) }
    var confirmImport by remember { mutableStateOf(false) }

    DisposableEffect(category, fcitx) {
        val listener = object : ManagedPreferenceProvider.OnChangeListener {
            override fun onChange(key: String) {
                version += 1
            }
        }
        category.registerOnChangeListener(listener)
        onDispose {
            category.unregisterOnChangeListener(listener)
            fcitx?.let { FcitxDaemon.disconnect("compose-advanced-settings") }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)!!.use { out ->
                        UserDataManager.export(out, exportTime).getOrThrow()
                    }
                }
                context.toast(R.string.done)
            } catch (e: Exception) {
                context.toast(e)
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val name = context.contentResolver.queryFileName(uri) ?: return@launch
            if (!name.endsWith(".zip")) {
                context.importErrorDialog(R.string.exception_user_data_filename, name)
                return@launch
            }
            try {
                // stop fcitx before overwriting files
                FcitxDaemon.stopFcitx()
                val metadata = withContext(Dispatchers.IO) {
                    UserDataImportCompat.import(context.contentResolver.openInputStream(uri)!!).getOrThrow()
                }
                AppUtil.showRestartNotification(context)
                context.toast(context.getString(R.string.user_data_imported, formatDateTime(metadata.exportTime)))
                // delay exit to ensure Notification and Toast has been created
                delay(400.milliseconds)
                AppUtil.exit()
            } catch (e: Exception) {
                FcitxDaemon.startFcitx()
                context.importErrorDialog(e)
            }
        }
    }

    val highlightColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.15f)
    val listState = rememberLazyListState()
    var currentHighlight by remember { mutableStateOf(highlightKey) }

    // Auto-scroll to the highlighted item and clear highlight after delay
    LaunchedEffect(highlightKey) {
        if (highlightKey != null) {
            currentHighlight = highlightKey
            delay(300.milliseconds) // Wait for layout to complete
            val index = findHighlightIndex(context, category, highlightKey)
            if (index >= 0) {
                listState.animateScrollToItem(index)
            }
            // Clear highlight after 2 seconds
            delay(2000.milliseconds)
            currentHighlight = null
        }
    }

    // Shared LazyColumn/PageScaffold content so the showTopBar branches stay in sync
    val uiMap = category.managedPreferencesUi.associateBy { it.key }
    val settingsContent: LazyListScope.() -> Unit = {
        renderSettingsContent(
            category = category,
            uiMap = uiMap,
            prefs = category.managedPreferences,
            version = version,
            fireChange = category::fireChange,
            currentHighlight = currentHighlight,
            highlightColor = highlightColor,
            isAdvanced = isAdvanced,
            onBrowseData = {
                try {
                    context.startActivity(buildDocumentsProviderIntent())
                } catch (e: Exception) {
                    context.toast(e)
                }
            },
            onExportData = {
                scope.launch {
                    fcitx?.runOnReady { save() }
                    exportTime = System.currentTimeMillis()
                    exportLauncher.launch("fcitx5-android_${iso8601UTCDateTime(exportTime)}.zip")
                }
            },
            onImportClick = { confirmImport = true },
            onWebDav = onWebDavSync,
        )
    }

    if (showTopBar) {
        PageScaffold(
            title = stringResource(category.title),
            onBack = onBack,
            listState = listState,
            content = settingsContent,
        )
    } else {
        Box(
            Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.surface)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .scrollEndHaptic()
                    .overScrollVertical(),
                overscrollEffect = null,
                content = settingsContent,
            )
        }
    }

    if (isAdvanced && confirmImport) {
        SimpleConfirmDialog(
            title = stringResource(R.string.import_user_data),
            message = stringResource(R.string.confirm_import_user_data),
            onConfirm = {
                confirmImport = false
                importLauncher.launch("application/zip")
            },
            onDismiss = { confirmImport = false },
        )
    }
}

/**
 * One grouped setting block: a [Card] that stacks the preference rows of a single group
 * (or the whole flattened list when the category has no explicit groups), separated
 * by [HorizontalDivider]. Matches KernelSU's "one Card per group" organization.
 */
@Composable
private fun ManagedGroupCard(
    items: List<ManagedPreferenceUi<*>>,
    prefs: Map<String, ManagedPreference<*>>,
    version: Int,
    fireChange: (String) -> Unit,
    currentHighlight: String?,
    highlightColor: Color,
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.padding(horizontal = 12.dp),
    ) {
        items.forEachIndexed { index, ui ->
            ManagedHighlightRow(
                ui = ui,
                prefs = prefs,
                version = version,
                fireChange = fireChange,
                isHighlight = uiTitleString(context, ui) == currentHighlight,
                highlightColor = highlightColor,
            )
            if (index < items.lastIndex) HorizontalDivider()
        }
    }
}

/**
 * Wraps a single preference row with the search-highlight background.
 */
@Composable
private fun ManagedHighlightRow(
    ui: ManagedPreferenceUi<*>,
    prefs: Map<String, ManagedPreference<*>>,
    version: Int,
    fireChange: (String) -> Unit,
    isHighlight: Boolean,
    highlightColor: Color,
) {
    Box(Modifier.background(if (isHighlight) highlightColor else Color.Transparent)) {
        ManagedPrefRow(
            ui = ui,
            prefs = prefs,
            version = version,
            fireChange = fireChange,
        )
    }
}

/**
 * Builds the [LazyListScope] content shared by [ManagedPrefsScreen]'s two render paths:
 * grouped [SmallTitle] + [ManagedGroupCard] pairs, plus the Advanced data-management card.
 */
private fun LazyListScope.renderSettingsContent(
    category: ManagedPreferenceCategory,
    uiMap: Map<String, ManagedPreferenceUi<*>>,
    prefs: Map<String, ManagedPreference<*>>,
    version: Int,
    fireChange: (String) -> Unit,
    currentHighlight: String?,
    highlightColor: Color,
    isAdvanced: Boolean,
    onBrowseData: () -> Unit,
    onExportData: () -> Unit,
    onImportClick: () -> Unit,
    onWebDav: (() -> Unit)?,
) {
    if (category.groups.isEmpty()) {
        item {
            ManagedGroupCard(
                items = category.managedPreferencesUi,
                prefs = prefs,
                version = version,
                fireChange = fireChange,
                currentHighlight = currentHighlight,
                highlightColor = highlightColor,
            )
        }
    } else {
        category.groups.forEach { group ->
            item {
                SmallTitle(text = stringResource(group.title))
            }
            item {
                ManagedGroupCard(
                    items = group.keys.filter { uiMap.containsKey(it) }.map { uiMap.getValue(it) },
                    prefs = prefs,
                    version = version,
                    fireChange = fireChange,
                    currentHighlight = currentHighlight,
                    highlightColor = highlightColor,
                )
            }
        }
    }
    if (isAdvanced) {
        item {
            SmallTitle(text = stringResource(R.string.data_management))
        }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
            ) {
                ArrowPreference(
                    title = stringResource(R.string.browse_user_data_dir),
                    summary = stringResource(R.string.data_browse_summary),
                    onClick = onBrowseData,
                    startAction = { managedPrefIcon(MiuixIcons.Folder) },
                )
                ArrowPreference(
                    title = stringResource(R.string.export_user_data),
                    summary = stringResource(R.string.data_export_summary),
                    onClick = onExportData,
                    startAction = { managedPrefIcon(MiuixIcons.UploadCloud) },
                )
                ArrowPreference(
                    title = stringResource(R.string.import_user_data),
                    summary = stringResource(R.string.data_import_summary),
                    onClick = onImportClick,
                    startAction = { managedPrefIcon(MiuixIcons.Import) },
                )
                if (onWebDav != null) {
                    ArrowPreference(
                        title = stringResource(R.string.webdav_settings_title),
                        summary = stringResource(R.string.webdav_advanced_summary),
                        onClick = onWebDav,
                        startAction = { managedPrefIcon(MiuixIcons.Backup) },
                    )
                }
            }
        }
    }
}

@Composable
private fun managedPrefIcon(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MiuixTheme.colorScheme.onBackground,
        modifier = Modifier
            .padding(end = 12.dp)
            .size(22.dp),
    )
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
                startAction = { managedPrefIcon(MiuixIcons.Tune) },
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
                startAction = { managedPrefIcon(MiuixIcons.Settings) },
                onSelectedIndexChange = { index ->
                    @Suppress("UNCHECKED_CAST")
                    (pref as ManagedPreference.PStringLike<Any>)
                        .setValue(ui.entryValues[index])
                    fireChange(ui.key)
                },
            )
        }

        is ManagedPreferenceUi.EditTextInt -> {
            val pref = prefs[ui.key] as? ManagedPreference.PInt ?: return
            var showDialog by remember { mutableStateOf(false) }
            val hasRange = ui.min < ui.max && (ui.max.toLong() - ui.min.toLong()) <= 10000
            if (hasRange) {
                SimpleSliderPreference(
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
                    startAction = { managedPrefIcon(MiuixIcons.Edit) },
                )
            } else {
                ArrowPreference(
                    title = stringResource(ui.title),
                    summary = "${pref.getValue()}${ui.unit}",
                    onClick = { showDialog = true },
                    holdDownState = showDialog,
                    enabled = ui.isEnabled(),
                    startAction = { managedPrefIcon(MiuixIcons.Edit) },
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
            SimpleSliderPreference(
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
                startAction = { managedPrefIcon(MiuixIcons.Timer) },
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
                startAction = { managedPrefIcon(MiuixIcons.VerticalSplit) },
                bottomAction = {
                    AnimatedVisibility(expanded && ui.isEnabled()) {
                        Column {
                            SimpleSliderPreference(
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
                                enabled = ui.isEnabled(),
                            )
                            SimpleSliderPreference(
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
                                enabled = ui.isEnabled(),
                            )
                        }
                    }
                },
            )
        }

        is EditTextFloatUi -> {
            val pref = prefs[ui.key] as? ManagedPreference.PFloat ?: return
            // unbounded / huge ranges have no meaningful slider -> keep the edit dialog
            val hasRange = ui.max > ui.min && (ui.max.toDouble() - ui.min.toDouble()) <= 100.0
            if (hasRange) {
                SimpleSliderPreference(
                    title = stringResource(ui.title),
                    value = pref.getValue(),
                    onValueChange = { newValue ->
                        pref.setValue(newValue.coerceIn(ui.min, ui.max))
                        fireChange(ui.key)
                    },
                    min = ui.min,
                    max = ui.max,
                    step = ui.step,
                    decimals = ui.decimals,
                    suffix = ui.unit,
                    enabled = ui.isEnabled(),
                    startAction = { managedPrefIcon(MiuixIcons.Edit) },
                )
            } else {
                var showDialog by remember { mutableStateOf(false) }
                ArrowPreference(
                    title = stringResource(ui.title),
                    summary = pref.getValue().toString() + ui.unit,
                    onClick = { showDialog = true },
                    holdDownState = showDialog,
                    enabled = ui.isEnabled(),
                    startAction = { managedPrefIcon(MiuixIcons.Edit) },
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
}

/**
 * Find the LazyColumn item index that contains the highlighted preference.
 * Returns the index to scroll to, or -1 if not found.
 *
 * LazyColumn structure:
 * - For each group: 1 item for SmallTitle + 1 item for Card
 * - For ungrouped: 1 item for Card
 * - For advanced: 1 item for SmallTitle + 1 item for Card
 */
private fun findHighlightIndex(
    context: Context,
    category: ManagedPreferenceCategory,
    highlightKey: String
): Int {
    var index = 0
    if (category.groups.isEmpty()) {
        // Ungrouped: single Card item at index 0
        val hasHighlight = category.managedPreferencesUi.any {
            uiTitleString(context, it) == highlightKey
        }
        return if (hasHighlight) 0 else -1
    } else {
        // Grouped: SmallTitle (index 0) + Card (index 1), then next group...
        val uiMap = category.managedPreferencesUi.associateBy { it.key }
        category.groups.forEach { group ->
            index += 1 // SmallTitle item
            val cardIndex = index
            index += 1 // Card item
            val keys = group.keys.filter { uiMap.containsKey(it) }
            val hasHighlight = keys.any { key ->
                val ui = uiMap.getValue(key)
                uiTitleString(context, ui) == highlightKey
            }
            if (hasHighlight) return cardIndex
        }
    }
    return -1
}