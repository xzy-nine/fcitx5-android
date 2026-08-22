/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.dialog

import android.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog
import androidx.compose.ui.res.stringResource

/** Minimal OK/Cancel confirmation dialog in miiux style. */
@Composable
fun SimpleConfirmDialog(
    title: String,
    message: String? = null,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var show by remember { mutableStateOf(true) }
    val context = LocalContext.current
    WindowDialog(
        show = show,
        title = title,
        summary = message,
        onDismissRequest = {
            show = false
            onDismiss()
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
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
                    show = false
                    onConfirm()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}
