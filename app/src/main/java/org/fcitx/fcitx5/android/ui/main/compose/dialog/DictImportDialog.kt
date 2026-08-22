/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog
import org.fcitx.fcitx5.android.R
import androidx.compose.ui.res.stringResource

/**
 * Miuix-styled confirmation dialog for importing a pinyin dictionary from an external file
 * (replaces the legacy [androidx.appcompat.app.AlertDialog] with the same wording).
 */
@Composable
internal fun DictImportDialog(
    uri: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    WindowDialog(
        show = uri != null,
        title = stringResource(R.string.pinyin_dict),
        summary = stringResource(R.string.whether_import_dict),
        onDismissRequest = onDismiss,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            TextButton(
                text = stringResource(android.R.string.cancel),
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = stringResource(android.R.string.ok),
                onClick = { uri?.let(onConfirm) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}
