/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.compose.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.fcitx.fcitx5.android.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 数据恢复的进度与结果界面。
 *
 * 恢复期间不读取任何偏好，主题固定为系统样式；调用方负责提供隔离的 Context。
 */
@Composable
fun UserDataRestoreScreen(
    report: String,
    working: Boolean,
    success: Boolean,
    onRestart: () -> Unit,
    onRetry: () -> Unit,
    onSaveDiagnostics: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (working) {
                    CircularProgressIndicator(modifier = Modifier.size(48.dp))
                }
                Text(
                    text = when {
                        working -> stringResource(R.string.loading)
                        success -> stringResource(R.string.restore_data_ready)
                        else -> stringResource(R.string.restore_recovery_title)
                    },
                    textAlign = TextAlign.Center,
                )
                if (!working && report.isNotBlank()) {
                    Text(
                        text = report,
                        textAlign = TextAlign.Center,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                if (!working) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (success) {
                            top.yukonga.miuix.kmp.preference.ArrowPreference(
                                title = stringResource(android.R.string.ok),
                                onClick = onRestart,
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.restore_recovery_explanation),
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            top.yukonga.miuix.kmp.preference.ArrowPreference(
                                title = stringResource(R.string.restore_retry),
                                onClick = onRetry,
                            )
                            top.yukonga.miuix.kmp.preference.ArrowPreference(
                                title = stringResource(R.string.restore_save_diagnostics),
                                onClick = onSaveDiagnostics,
                            )
                            top.yukonga.miuix.kmp.preference.ArrowPreference(
                                title = stringResource(android.R.string.cancel),
                                onClick = onDismiss,
                            )
                        }
                    }
                }
            }
        }
    }
}
