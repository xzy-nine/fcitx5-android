/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.screens

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.data.DataManager
import org.fcitx.fcitx5.android.core.data.FileSource
import org.fcitx.fcitx5.android.core.data.PluginLoadFailed
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Compose renderer for the plugin list (replaces the View-based PluginFragment).
 * Groups plugins by reload/loaded/failed state in Card rows, refreshes on package changes.
 */
@Composable
fun PluginListScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var synced by remember { mutableStateOf<DataManager.PluginSet?>(null) }
    var detected by remember { mutableStateOf<DataManager.PluginSet?>(null) }
    var loading by remember { mutableStateOf(true) }

    fun refresh() {
        scope.launch {
            suspendCancellableCoroutine { cont ->
                if (DataManager.synced) cont.resumeWith(Result.success(Unit))
                else DataManager.addOnNextSyncedCallback {
                    cont.resumeWith(Result.success(Unit))
                }
            }
            synced = DataManager.getSyncedPluginSet()
            detected = DataManager.detectPlugins()
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val packageChangeReceiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { refresh() }
        }
    }

    DisposableEffect(Unit) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        context.registerReceiver(packageChangeReceiver, filter)
        onDispose { context.unregisterReceiver(packageChangeReceiver) }
    }

    if (loading || synced == null) {
        Box(
            Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    val curSynced = synced!!
    val curDetected = detected!!

    fun pluginAbout(pkg: String) {
        val pm = context.packageManager
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(
                Intent(DataManager.PLUGIN_INTENT),
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(Intent(DataManager.PLUGIN_INTENT), PackageManager.MATCH_ALL)
        }
        val info = resolved.firstOrNull { it.activityInfo.packageName == pkg }
        if (info != null) {
            context.startActivity(Intent().apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
            })
        } else {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                data = Uri.fromParts("package", pkg, null)
            })
        }
    }

    @Composable
    fun failedSummary(reason: PluginLoadFailed): String = when (reason) {
        is PluginLoadFailed.DataDescriptorParseError -> stringResource(R.string.invalid_data_descriptor)
        is PluginLoadFailed.MissingDataDescriptor -> stringResource(R.string.missing_data_descriptor)
        PluginLoadFailed.MissingPluginDescriptor -> stringResource(R.string.missing_plugin_descriptor)
        is PluginLoadFailed.PathConflict -> {
            val owner = when (reason.existingSrc) {
                FileSource.Main -> stringResource(R.string.main_program)
                is FileSource.Plugin -> reason.existingSrc.descriptor.name
            }
            stringResource(R.string.path_conflict, reason.path, owner)
        }
        is PluginLoadFailed.PluginAPIIncompatible -> stringResource(R.string.incompatible_api, reason.api)
        PluginLoadFailed.PluginDescriptorParseError -> stringResource(R.string.invalid_plugin_descriptor)
    }

    val (loaded, failed) = curSynced

    PageScaffold(
        title = stringResource(R.string.plugins),
        onBack = onBack,
        contentBottomPadding = 24.dp,
    ) {
        if (curSynced != curDetected) {
                item {
                    Card(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        colors = CardDefaults.defaultColors(
                            color = MiuixTheme.colorScheme.surfaceContainerHighest,
                        ),
                    ) {
                        ArrowPreference(
                            title = stringResource(R.string.plugin_needs_reload),
                            startAction = {
                                Icon(MiuixIcons.Info, null, Modifier.size(20.dp))
                            },
                            onClick = {
                                DataManager.addOnNextSyncedCallback {
                                    scope.launch {
                                        synced = DataManager.getSyncedPluginSet()
                                        detected = DataManager.detectPlugins()
                                    }
                                }
                                FcitxDaemon.restartFcitx()
                            },
                        )
                    }
                }
            }
            if (loaded.isEmpty() && failed.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        colors = CardDefaults.defaultColors(
                            color = MiuixTheme.colorScheme.surfaceContainerHighest,
                        ),
                    ) {
                        Text(
                            text = stringResource(R.string.no_plugins),
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            } else {
                if (loaded.isNotEmpty()) {
                    item { SmallTitle(text = stringResource(R.string.plugins_loaded)) }
                    item {
                        Card(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            colors = CardDefaults.defaultColors(
                                color = MiuixTheme.colorScheme.surfaceContainerHighest,
                            ),
                        ) {
                            loaded.forEachIndexed { index, p ->
                                ArrowPreference(
                                    title = p.name,
                                    summary = "${p.versionName}\n${p.description}",
                                    onClick = { pluginAbout(p.packageName) },
                                )
                                if (index < loaded.size - 1) HorizontalDivider()
                            }
                        }
                    }
                }
                if (failed.isNotEmpty()) {
                    item { SmallTitle(text = stringResource(R.string.plugins_failed)) }
                    item {
                        Card(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            colors = CardDefaults.defaultColors(
                                color = MiuixTheme.colorScheme.surfaceContainerHighest,
                            ),
                        ) {
                            failed.entries.forEachIndexed { index, entry ->
                                val pkg = entry.key
                                val reason = entry.value
                                ArrowPreference(
                                    title = pkg,
                                    summary = failedSummary(reason),
                                    onClick = { pluginAbout(pkg) },
                                )
                                if (index < failed.size - 1) HorizontalDivider()
                            }
                        }
                    }
                }
            }
    }
}
