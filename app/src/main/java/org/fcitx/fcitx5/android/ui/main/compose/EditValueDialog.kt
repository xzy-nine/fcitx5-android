/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * Simple text-input dialog used by [ManagedPrefsScreen] for edit-text style preferences
 * (replaces the legacy EditTextIntPreference / EditTextFloatPreference dialog flow).
 * [onConfirm] returns false when the input is invalid (dialog stays open).
 */
@Composable
fun EditValueDialog(
    title: String,
    initialText: String,
    onConfirm: (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var show by remember { mutableStateOf(true) }
    val context = LocalContext.current
    val state = rememberTextFieldState(initialText)
    val currentConfirm by rememberUpdatedState(onConfirm)
    WindowDialog(
        show = show,
        title = title,
        onDismissRequest = {
            show = false
            onDismiss()
        },
    ) {
        TextField(
            state = state,
            label = title,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            TextButton(
                text = context.getString(android.R.string.cancel),
                onClick = {
                    show = false
                    onDismiss()
                },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = context.getString(android.R.string.ok),
                onClick = {
                    if (currentConfirm(state.text.toString())) {
                        show = false
                        onDismiss()
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}
