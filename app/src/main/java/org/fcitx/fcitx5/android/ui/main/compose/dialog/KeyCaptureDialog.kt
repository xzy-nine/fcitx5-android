/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.dialog

import android.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.fcitx.fcitx5.android.core.Key
import org.fcitx.fcitx5.android.ui.main.settings.KeyPreferenceUi
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog
import androidx.compose.ui.res.stringResource

/**
 * Window dialog wrapping the legacy [KeyPreferenceUi] capture view (Compose hosts View).
 * [onConfirm] receives the serialized key string (round-trips through [Key.parse]).
 */
@Composable
fun KeyCaptureDialog(
    title: String,
    initialKey: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var show by remember { mutableStateOf(true) }
    val keyUi = remember { mutableStateOf<KeyPreferenceUi?>(null) }

    if (show) {
        WindowDialog(
            show = true,
            title = title,
            onDismissRequest = {
                show = false
                onDismiss()
            },
        ) {
            Column(Modifier.fillMaxWidth()) {
                AndroidView(
                    factory = { ctx ->
                        KeyPreferenceUi(ctx).apply {
                            initialKey?.takeIf { it.isNotEmpty() }?.let { text ->
                                runCatching { setKey(Key.parse(text)) }
                            }
                        }.also { keyUi.value = it }.root
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight(),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                ) {
                    TextButton(
                        text = stringResource(R.string.cancel),
                        onClick = {
                            show = false
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = stringResource(R.string.ok),
                        onClick = {
                            keyUi.value?.lastKey?.let { key ->
                                onConfirm(key.toString())
                            }
                            show = false
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }
}
