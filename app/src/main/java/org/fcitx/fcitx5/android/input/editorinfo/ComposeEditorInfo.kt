/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editorinfo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * EditorInfo 检查器的 Compose 渲染层，纯 UI 无业务逻辑。
 *
 * 以纵向滚动列表单列渲染 [EditorInfoParser] 产出的 `键 -> 值` 属性表
 * （两列：加粗键名 + 可换行取值），颜色一律取 [MiuixTheme] 系统主题。
 */
@Composable
fun ComposeEditorInfoContent(
    properties: Map<String, String>,
) {
    val background = MiuixTheme.colorScheme.background
    val onSurface = MiuixTheme.colorScheme.onSurface
    val divider = MiuixTheme.colorScheme.dividerLine
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        properties.forEach { (key, value) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = key,
                    color = onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier
                        .width(128.dp)
                        .padding(vertical = 4.dp),
                )
                Text(
                    text = value,
                    color = onSurface,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 4.dp),
                )
            }
            HorizontalDivider(color = divider, thickness = 0.5.dp)
        }
    }
}