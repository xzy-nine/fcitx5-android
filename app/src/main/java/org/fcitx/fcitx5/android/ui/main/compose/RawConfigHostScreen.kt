/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose

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
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * Host for fcitx backend config pages. Loads the RawConfig from the fcitx daemon (same API
 * calls as the legacy [org.fcitx.fcitx5.android.ui.main.settings.FcitxPreferenceFragment]
 * subclasses), renders it with [RawConfigScreen] and pushes changes back with saveConfig.
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
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main) }

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
            // flush last state before leaving
            raw?.let { r ->
                scope.launch {
                    fcitx.runIfReady { saveConfig(this, route, r["cfg"]) }
                }
            }
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
            )
        }
        errorText != null -> {
            Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background), contentAlignment = Alignment.Center) {
                Text(text = errorText ?: "")
            }
        }
        else -> {
            Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

private suspend fun obtainConfig(fcitx: FcitxAPI, route: AppRoute.RawConfigHost): RawConfig =
    when (route.kind) {
        RawConfigHostType.GlobalConfig -> fcitx.getGlobalConfig()
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
        RawConfigHostType.InputMethodConfig -> fcitx.setImConfig(route.uniqueName.orEmpty(), newConfig)
        RawConfigHostType.AddonConfig -> fcitx.setAddonConfig(route.uniqueName.orEmpty(), newConfig)
    }
