/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.fcitx.fcitx5.android.R
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 把颜色格式化为 #AARRGGBB。 */
private fun formatArgbHex(argb: Int): String = String.format(
    "#%02X%02X%02X%02X",
    (argb ushr 24) and 0xFF,
    (argb ushr 16) and 0xFF,
    (argb ushr 8) and 0xFF,
    argb and 0xFF,
)

/** 解析 #RRGGBB 或 #AARRGGBB；无法解析时返回 null。 */
private fun parseArgbHex(input: String): Int? {
    val normalized = input.trim().removePrefix("#")
    val hex = when (normalized.length) {
        6 -> "FF" + normalized
        8 -> normalized
        else -> return null
    }
    return try {
        java.lang.Long.parseLong(hex, 16).toInt()
    } catch (_: Exception) {
        null
    }
}

/**
 * 颜色编辑器。
 *
 * 用色板选择颜色，透明度与色相在同一面板内调整；也可直接输入 ARGB 十六进制值。
 * 允许清空时返回 null 表示「不指定颜色」。
 */
@Composable
fun ThemeColorEditorScreen(
    title: String,
    initialColor: Int,
    allowClear: Boolean,
    onResult: (Int?) -> Unit,
    onBack: () -> Unit,
) {
    var color by remember { mutableStateOf(Color(initialColor)) }
    var hexText by remember { mutableStateOf(formatArgbHex(initialColor)) }

    PageScaffold(
        title = title,
        onBack = onBack,
        floatingActionButton = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (allowClear) {
                    ArrowPreference(
                        title = stringResource(R.string.clear),
                        modifier = Modifier.weight(1f),
                        onClick = { onResult(null) },
                    )
                }
                ArrowPreference(
                    title = stringResource(R.string.save),
                    modifier = Modifier.weight(1f),
                    onClick = { onResult(color.toArgb()) },
                )
            }
        },
    ) {
        item {
            // 当前颜色预览
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .height(72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(color),
            )
        }

        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                ColorPicker(
                    color = color,
                    onColorChanged = { picked ->
                        color = picked
                        hexText = formatArgbHex(picked.toArgb())
                    },
                )
            }
        }

        item { SmallTitle(text = stringResource(R.string.theme_color_editor_argb_label)) }
        item {
            Card(
                modifier = Modifier.padding(12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                TextField(
                    value = hexText,
                    onValueChange = { input ->
                        hexText = input
                        parseArgbHex(input)?.let { color = Color(it) }
                    },
                    label = stringResource(R.string.theme_color_editor_argb_label),
                )
                Text(
                    text = stringResource(R.string.theme_color_editor_argb_hint),
                    modifier = Modifier.padding(16.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}
