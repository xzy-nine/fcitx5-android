/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose

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
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.AddonInfo
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Compose renderer for the addon list (replaces the View-based AddonListFragment).
 * Each addon toggles via a checkbox; disabling one that other addons depend on asks for
 * confirmation. Configurable addons expose a settings button opening AddonConfig.
 */
@Composable
fun AddonListScreen(
    onOpenConfig: (String, String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val fcitx: FcitxConnection = remember { FcitxDaemon.connect("compose-addonlist") }
    val scope = remember {
        kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }
    var addons by remember { mutableStateOf<List<AddonInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    // pending disable request waiting for user confirmation
    var pendingDisable by remember {
        mutableStateOf<Triple<AddonInfo, List<Pair<String, FcitxAPI.AddonDep>>, () -> Unit>?>(null)
    }

    fun pushState() {
        val ids = addons.map { it.uniqueName }.toTypedArray()
        val states = addons.map { it.enabled }.toBooleanArray()
        fcitx.runIfReady { setAddonState(ids, states) }
    }

    DisposableEffect(Unit) {
        scope.launch {
            val sorted = fcitx.runOnReady {
                addons().sortedBy { it.uniqueName }
            }
            addons = sorted
            loading = false
        }
        onDispose {
            fcitx.runIfReady { save() }
            FcitxDaemon.disconnect("compose-addonlist")
            scope.cancel()
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

    val displayNames = remember(addons) {
        addons.associate { it.uniqueName to it.displayName }
    }

    fun attemptDisable(entry: AddonInfo, reset: () -> Unit) {
        val dependents = fcitx.runImmediately { getAddonReverseDependencies(entry.uniqueName) }
        if (dependents.isEmpty()) {
            addons = addons.map { if (it.uniqueName == entry.uniqueName) it.copy(enabled = false) else it }
            pushState()
        } else {
            pendingDisable = Triple(entry, dependents, reset)
        }
    }

    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxSize()) {
            LazyColumn(
                contentPadding = PaddingValues(
                    top = 64.dp + WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                ),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(addons, key = { it.uniqueName }) { entry ->
                    CheckboxPreference(
                        title = entry.displayName,
                        summary = entry.comment,
                        checked = entry.enabled,
                        enabled = entry.uniqueName != "androidfrontend",
                        onCheckedChange = { checked ->
                            if (checked) {
                                addons = addons.map {
                                    if (it.uniqueName == entry.uniqueName) it.copy(enabled = true) else it
                                }
                                pushState()
                            } else {
                                attemptDisable(entry) {
                                    // re-check in the UI on cancel (handled via state refresh)
                                }
                            }
                        },
                        endActions = {
                            if (entry.isConfigurable && entry.enabled &&
                                entry.uniqueName != "clipboard"
                            ) {
                                IconButton(
                                    onClick = { onOpenConfig(entry.displayName, entry.uniqueName) },
                                ) {
                                    Icon(MiuixIcons.Tune, null, Modifier.size(20.dp))
                                }
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
        SmallTopAppBar(
            color = MiuixTheme.colorScheme.surfaceContainer,
            title = context.getString(R.string.addons),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(MiuixIcons.Back, null, Modifier.size(24.dp))
                }
            },
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
        )
    }

    pendingDisable?.let { (entry, dependents, reset) ->
        fun summary(depType: FcitxAPI.AddonDep, template: Int): String? {
            val names = dependents
                .filter { it.second == depType }
                .mapNotNull { (u, _) -> displayNames[u] ?: u }
            return names.takeIf { it.isNotEmpty() }
                ?.joinToString(", ")
                ?.let { context.getString(template, it) }
        }
        val dep = summary(FcitxAPI.AddonDep.Required, R.string.disable_addon_warn_dep)
        val optDep = summary(FcitxAPI.AddonDep.Optional, R.string.disable_addon_warn_optdep)
        val msg = buildString {
            appendLine(context.getString(R.string.disable_addon_warn_name, entry.displayName))
            dep?.let { append("- "); appendLine(it) }
            optDep?.let { append("- "); appendLine(it) }
            appendLine(context.getString(R.string.disable_addon_warn_confirm))
        }
        SimpleConfirmDialog(
            title = context.getString(R.string.disable_addon_warn_title),
            message = msg,
            onConfirm = {
                addons = addons.map {
                    if (it.uniqueName == entry.uniqueName) it.copy(enabled = false) else it
                }
                pushState()
                pendingDisable = null
            },
            onDismiss = {
                reset()
                pendingDisable = null
            },
        )
    }
}
