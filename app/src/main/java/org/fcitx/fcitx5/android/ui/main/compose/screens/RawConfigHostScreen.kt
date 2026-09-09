/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.ui.main.compose.AppRoute
import org.fcitx.fcitx5.android.ui.main.compose.RawConfigHostType
import org.fcitx.fcitx5.android.ui.main.compose.settings.RawConfigScreen
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Host for fcitx backend config pages. Loads the RawConfig from the fcitx daemon (same API
 * calls as the legacy [org.fcitx.fcitx5.android.ui.main.settings.FcitxPreferenceFragment]
 * subclasses), renders it with [org.fcitx.fcitx5.android.ui.main.compose.settings.RawConfigScreen] and pushes changes back with saveConfig.
 */
@Composable
fun RawConfigHostScreen(
    route: AppRoute.RawConfigHost,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    var raw by remember { mutableStateOf<RawConfig?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }

    val connectionName = "compose-rawconfig-${route.kind.name}-${route.uniqueName ?: ""}"
    val fcitx: FcitxConnection = remember { FcitxDaemon.connect(connectionName) }
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    val context = LocalContext.current

    DisposableEffect(fcitx, connectionName) {
        scope.launch {
            try {
                raw = fcitx.runOnReady { obtainConfig(this, route) }
                errorText = null
            } catch (e: Exception) {
                errorText = e.message
            }
        }
        onDispose {
            // flush last state before leaving. runIfReady submits the save asynchronously on
            // fcitx's own lifecycle scope, so never wait for it here: joining a Main-dispatched
            // coroutine from onDispose used to deadlock the Compose dispatcher and trigger an ANR.
            raw?.let { r ->
                runCatching {
                    fcitx.runIfReady { saveConfig(this, route, r["cfg"]) }
                }
            }
            FcitxDaemon.disconnect(connectionName)
            scope.cancel()
        }
    }

    val loaded = raw
    when {
        loaded != null -> {
            RawConfigScreen(
                raw = loaded,
                onNavigate = onNavigate,
                onBack = onBack,
                onSave = {
                    scope.launch {
                        fcitx.runIfReady { saveConfig(this, route, loaded["cfg"]) }
                    }
                },
                // fcitx reports the global config top-level name in English ("Global Options"),
                // mirror the legacy GlobalConfigFragment and use the localized string instead.
                titleOverride = when (route.kind) {
                    RawConfigHostType.GlobalConfig -> stringResource(R.string.global_options)
                    RawConfigHostType.PhysicalHotkey -> stringResource(R.string.hotkey)
                    else -> null
                },
            )
        }
        errorText != null -> {
            Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                Text(text = errorText ?: "")
            }
        }
        else -> {
            Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

private suspend fun obtainConfig(fcitx: FcitxAPI, route: AppRoute.RawConfigHost): RawConfig =
    when (route.kind) {
        RawConfigHostType.GlobalConfig -> splitHotkey(fcitx.getGlobalConfig()).first
        RawConfigHostType.PhysicalHotkey -> splitHotkey(fcitx.getGlobalConfig()).second
        RawConfigHostType.InputMethodConfig -> fcitx.getImConfig(route.uniqueName.orEmpty())
        RawConfigHostType.AddonConfig -> {
            val addon = route.uniqueName.orEmpty()
            val raw = fcitx.getAddonConfig(addon)
            if (addon == "table") {
                raw.findByName("desc")?.findByName("TableGlobalConfig")?.let {
                    it.subItems = (it.subItems ?: emptyArray()) + RawConfig(
                        "AndroidTable", subItems = arrayOf(
                            RawConfig("Type", "External"),
                            RawConfig("Description", "Manage Table Input Methods")
                        )
                    )
                }
            }
            raw
        }
    }

private suspend fun saveConfig(fcitx: FcitxAPI, route: AppRoute.RawConfigHost, newConfig: RawConfig) =
    when (route.kind) {
        RawConfigHostType.GlobalConfig -> fcitx.setGlobalConfig(newConfig)
        RawConfigHostType.PhysicalHotkey -> fcitx.setGlobalConfig(newConfig)
        RawConfigHostType.InputMethodConfig -> fcitx.setImConfig(route.uniqueName.orEmpty(), newConfig)
        RawConfigHostType.AddonConfig -> fcitx.setAddonConfig(route.uniqueName.orEmpty(), newConfig)
    }

/**
 * Splits the fcitx global config into two **real, independent** configs at the data layer:
 *  - [first]  = the global config with the physical-hotkey group (`Hotkey`) removed;
 *  - [second] = a standalone config containing only the `Hotkey` group.
 *
 * Both keep the full `cfg`/`desc` wrapper expected by [org.fcitx.fcitx5.android.ui.main.compose.settings.RawConfigScreen],
 * so each can be rendered and saved on its own. Saving goes through fcitx's partial `load`, therefore
 * editing one half never clobbers the other (`load` simply leaves the absent group untouched).
 */
private fun splitHotkey(global: RawConfig): Pair<RawConfig, RawConfig> {
    val cfg = global["cfg"]
    val desc = global["desc"]
    val topDef = desc.subItems?.firstOrNull()            // GlobalConfig container
    val customDefs = desc.subItems?.drop(1) ?: emptyList()
    val isHotkey: (RawConfig) -> Boolean = { it.name.equals("Hotkey", ignoreCase = true) }

    val hotkeyCfgGroup = cfg.subItems?.firstOrNull(isHotkey)
    val hotkeyDescGroup = topDef?.subItems?.firstOrNull(isHotkey)

    val globalCfg = RawConfig(
        "cfg",
        subItems = (cfg.subItems?.filter { !isHotkey(it) } ?: emptyList()).toTypedArray()
    )
    val globalTopDef = RawConfig(
        topDef?.name ?: "GlobalConfig",
        subItems = (topDef?.subItems?.filter { !isHotkey(it) } ?: emptyList()).toTypedArray()
    )
    val globalDesc = RawConfig(
        desc.name,
        subItems = (listOf(globalTopDef) + customDefs).toTypedArray()
    )
    val globalConfig = RawConfig("", subItems = arrayOf(globalCfg, globalDesc))

    val hotkeyCfg = RawConfig(
        "cfg",
        subItems = (hotkeyCfgGroup?.let { listOf(it) } ?: emptyList()).toTypedArray()
    )
    val hotkeyTopDef = RawConfig(
        topDef?.name ?: "GlobalConfig",
        subItems = (hotkeyDescGroup?.let { listOf(it) } ?: emptyList()).toTypedArray()
    )
    val hotkeyDesc = RawConfig(
        desc.name,
        subItems = (listOf(hotkeyTopDef) + customDefs).toTypedArray()
    )
    val hotkeyConfig = RawConfig("", subItems = arrayOf(hotkeyCfg, hotkeyDesc))

    return globalConfig to hotkeyConfig
}
