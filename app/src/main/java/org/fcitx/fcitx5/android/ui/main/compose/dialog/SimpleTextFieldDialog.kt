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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.window.WindowDialog
import androidx.compose.ui.res.stringResource

/** OK/Cancel dialog with two text fields, in miuix style (used by the quick phrase editor). */
@Composable
fun SimpleTwoFieldDialog(
    title: String,
    field1Label: String,
    field2Label: String,
    value1: String,
    value2: String,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var show by remember { mutableStateOf(true) }
    var text1 by remember { mutableStateOf(TextFieldValue(value1)) }
    var text2 by remember { mutableStateOf(TextFieldValue(value2)) }
    val context = LocalContext.current
    WindowDialog(
        show = show,
        title = title,
        onDismissRequest = {
            show = false
            onDismiss()
        },
    ) {
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            TextField(
                value = text1,
                onValueChange = { text1 = it },
                label = field1Label,
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = text2,
                onValueChange = { text2 = it },
                label = field2Label,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
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
                    show = false
                    onConfirm(text1.text, text2.text)
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/** OK/Cancel dialog with three text fields, in miuix style (pinyin custom phrase editor). */
@Composable
fun SimpleThreeFieldDialog(
    title: String,
    field1Label: String,
    field2Label: String,
    field3Label: String,
    value1: String,
    value2: String,
    value3: String,
    onConfirm: (String, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var show by remember { mutableStateOf(true) }
    var text1 by remember { mutableStateOf(TextFieldValue(value1)) }
    var text2 by remember { mutableStateOf(TextFieldValue(value2)) }
    var text3 by remember { mutableStateOf(TextFieldValue(value3)) }
    val context = LocalContext.current
    WindowDialog(
        show = show,
        title = title,
        onDismissRequest = {
            show = false
            onDismiss()
        },
    ) {
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            TextField(
                value = text1,
                onValueChange = { text1 = it },
                label = field1Label,
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = text2,
                onValueChange = { text2 = it },
                label = field2Label,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            TextField(
                value = text3,
                onValueChange = { text3 = it },
                label = field3Label,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
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
                    show = false
                    onConfirm(text1.text, text2.text, text3.text)
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/** Minimal OK/Cancel dialog with a single text field, in miuix style. */
@Composable
fun SimpleTextFieldDialog(
    title: String,
    hint: String,
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var show by remember { mutableStateOf(true) }
    var text by remember { mutableStateOf(TextFieldValue(value)) }
    val context = LocalContext.current
    WindowDialog(
        show = show,
        title = title,
        onDismissRequest = {
            show = false
            onDismiss()
        },
    ) {
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            TextField(
                value = text,
                onValueChange = {
                    text = it
                    onValueChange(it.text)
                },
                label = hint,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
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
                    show = false
                    onConfirm()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}
