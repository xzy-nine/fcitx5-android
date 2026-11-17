/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.update.HostsConfig
import org.fcitx.fcitx5.android.ui.main.update.UpdatePrefs
import org.fcitx.fcitx5.android.ui.main.update.UpdateRepository
import org.fcitx.fcitx5.android.utils.formatDateTime
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 更新加速 Hosts 设置页。
 *
 * 填入映射来源地址后拉取映射，命中规则的主机名会在下载时改用映射地址。
 * 离开页面时统一写回一次，避免中途多次写入使版本缓存失效。
 */
@Composable
fun UpdateHostsSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var source by remember { mutableStateOf(UpdatePrefs.loadHostsConfig(context).sourceUrl) }
    var enabled by remember { mutableStateOf(UpdatePrefs.loadHostsConfig(context).enabled) }
    var hostConfig by remember { mutableStateOf(UpdatePrefs.loadHostsConfig(context)) }
    var updating by remember { mutableStateOf(false) }

    fun leave() {
        val trimmed = source.trim()
        val validSource = if (trimmed.isEmpty() || !UpdateRepository.isValidHttpUrl(trimmed)) "" else trimmed
        UpdateRepository.saveHostsSettings(context, enabled, validSource)
        onBack()
    }

    fun startUpdate() {
        val trimmed = source.trim()
        if (trimmed.isEmpty()) {
            context.toast(R.string.update_hosts_source_required)
            return
        }
        if (!UpdateRepository.isValidHttpUrl(trimmed) || !trimmed.startsWith("https://")) {
            context.toast(R.string.update_hosts_source_invalid)
            return
        }
        if (!enabled) {
            context.toast(R.string.update_hosts_enable_required)
            return
        }
        UpdateRepository.saveHostsSource(context, trimmed)
        updating = true
        scope.launch {
            try {
                val count = withContext(Dispatchers.IO) { UpdateRepository.updateHostsMapping(context) }
                hostConfig = UpdatePrefs.loadHostsConfig(context)
                context.toast(context.getString(R.string.update_hosts_updated_count, count))
            } catch (t: Throwable) {
                Timber.w(t, "拉取 Hosts 映射失败")
                context.toast(R.string.update_hosts_update_failed)
            } finally {
                updating = false
            }
        }
    }

    PageScaffold(
        title = stringResource(R.string.update_hosts_settings_title),
        onBack = { leave() },
    ) {
        item { SmallTitle(text = stringResource(R.string.update_hosts_settings_title)) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                TextField(
                    value = source,
                    onValueChange = { source = it },
                    label = stringResource(R.string.update_hosts_source_label),
                )
                SwitchPreference(
                    title = stringResource(R.string.update_hosts_enable_title),
                    checked = enabled,
                    onCheckedChange = { checked ->
                        enabled = checked
                        UpdateRepository.saveHostsEnabled(context, checked)
                    },
                )
                Text(
                    text = statusLine(hostConfig),
                    modifier = Modifier.padding(16.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                TextButton(
                    text = stringResource(R.string.update_hosts_update_button),
                    enabled = !updating,
                    onClick = { startUpdate() },
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }
}

/** 上次更新时间与映射条数；从未更新时给出明确提示。 */
@Composable
private fun statusLine(config: HostsConfig): String =
    if (config.updatedAt <= 0L) {
        stringResource(R.string.update_hosts_status_never)
    } else {
        stringResource(
            R.string.update_hosts_status_line,
            formatDateTime(config.updatedAt),
            config.mapping.size,
        )
    }