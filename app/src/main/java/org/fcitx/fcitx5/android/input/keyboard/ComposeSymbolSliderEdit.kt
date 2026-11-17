/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.settingsColor
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 符号滑块内容编辑。
 *
 * 输入的每个字符对应滑块的一段，字符顺序即竖排自上而下的顺序。
 * 确认后经 [SymbolSliderEditStore] 交回数字键盘。
 */
@Composable
fun ComposeSymbolSliderEdit(
    initialSymbols: String,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialSymbols) }

    WindowDialog(
        show = true,
        title = stringResource(R.string.symbol_slider_edit_title),
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            TextField(
                value = text,
                onValueChange = { text = it },
                label = stringResource(R.string.symbol_slider_edit_title),
            )
            Text(
                text = stringResource(R.string.symbol_slider_edit_hint),
                modifier = Modifier.padding(top = 8.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ArrowPreference(
                    title = stringResource(android.R.string.cancel),
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss,
                )
                ArrowPreference(
                    title = stringResource(android.R.string.ok),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        SymbolSliderEditStore.set(text)
                        onDismiss()
                    },
                )
            }
        }
    }
}
