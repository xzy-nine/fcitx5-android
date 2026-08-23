/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.screens

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.broadcast.BroadcastSecurityManager
import org.fcitx.fcitx5.android.data.broadcast.db.PairedAppEntity
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.ui.main.compose.dialog.SimpleConfirmDialog
import org.fcitx.fcitx5.android.utils.toast
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.res.stringResource

/**
 * Compose renderer for the clipboard broadcast / pairing settings (replaces the View-based
 * BroadcastSettingsFragment). Custom custom-branch UI that generic ManagedPrefsScreen lacks.
 */
@Composable
fun BroadcastScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = AppPrefs.getInstance().broadcast
    var enabled by remember { mutableStateOf(prefs.enabled.getValue()) }
    var pairingCode by remember { mutableStateOf("") }
    var pairedApps by remember { mutableStateOf<List<PairedAppEntity>>(emptyList()) }
    var revokeTarget by remember { mutableStateOf<PairedAppEntity?>(null) }

    fun reloadApps() {
        scope.launch {
            pairedApps = withContext(Dispatchers.IO) {
                BroadcastSecurityManager.getAllPairedApps()
            }
        }
    }

    LaunchedEffect(Unit) { reloadApps() }

    LaunchedEffect(enabled) {
        if (enabled) {
            pairingCode = withContext(Dispatchers.IO) {
                BroadcastSecurityManager.getPairingCode()
                    ?: BroadcastSecurityManager.generatePairingCode()
            }
        }
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        prefs.enabled.setValue(value)
        if (value) BroadcastSecurityManager.startListening()
        else BroadcastSecurityManager.stopListening()
    }

    fun copyPairingCode() {
        val code = pairingCode.ifBlank {
            BroadcastSecurityManager.getPairingCode()
                ?: BroadcastSecurityManager.generatePairingCode()
        }
        pairingCode = code
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("pairing_code", code)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard.setPrimaryClip(clip)
        context.toast(R.string.pairing_code_copied)
    }

    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
        LazyColumn(
            contentPadding = PaddingValues(
                top = 64.dp + WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                bottom = 24.dp,
            ),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    colors = CardDefaults.defaultColors(
                        color = MiuixTheme.colorScheme.surfaceContainerHighest,
                    ),
                ) {
                    SwitchPreference(
                        title = stringResource(R.string.broadcast_enable),
                        summary = stringResource(R.string.broadcast_enable_summary),
                        checked = enabled,
                        onCheckedChange = { setEnabled(it) },
                    )
                    if (enabled) {
                        ArrowPreference(
                            title = stringResource(R.string.pairing_code),
                            summary = pairingCode,
                            onClick = { copyPairingCode() },
                            startAction = {
                                Icon(
                                    MiuixIcons.Lock,
                                    null,
                                    Modifier.size(22.dp),
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            },
                        )
                    }
                }
            }
            if (enabled) {
                item {
                    SmallTitle(text = stringResource(R.string.paired_apps))
                }
            }
            if (enabled && pairedApps.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        colors = CardDefaults.defaultColors(
                            color = MiuixTheme.colorScheme.surfaceContainerHighest,
                        ),
                    ) {
                        Text(
                            text = stringResource(R.string.no_paired_apps),
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            } else if (enabled) {
                items(pairedApps, key = { it.packageName }) { app ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                        colors = CardDefaults.defaultColors(
                            color = MiuixTheme.colorScheme.surfaceContainerHighest,
                        ),
                    ) {
                        ArrowPreference(
                            title = app.appName.ifEmpty { app.packageName },
                            summary = app.packageName,
                            onClick = { revokeTarget = app },
                        )
                    }
                }
            }
        }
        SmallTopAppBar(
            color = MiuixTheme.colorScheme.surfaceContainer,
            title = stringResource(R.string.broadcast_settings),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(MiuixIcons.Back, stringResource(R.string.back), Modifier.size(24.dp))
                }
            },
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
        )
    }

    revokeTarget?.let { app ->
        SimpleConfirmDialog(
            title = stringResource(R.string.revoke_pairing),
            message = stringResource(R.string.revoke_pairing_confirm, app.packageName),
            onConfirm = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        BroadcastSecurityManager.revokePairing(app.packageName)
                    }
                    context.toast(context.getString(R.string.app_revoked, app.packageName))
                    revokeTarget = null
                    reloadApps()
                }
            },
            onDismiss = { revokeTarget = null },
        )
    }
}
