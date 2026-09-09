/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.preedit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text

/**
 * Compose 预编辑栏
 *
 * 上行文本：auxUp + preedit（含光标标记）
 * 下行文本：auxDown
 */
@Composable
fun ComposePreedit(
    state: PreeditState,
    visuals: PreeditVisuals,
    modifier: Modifier = Modifier,
) {
    if (!state.visible) return

    Column(
        modifier = modifier
            .wrapContentWidth()
            .background(visuals.backgroundColor)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        // 上行：auxUp + preedit（含光标）
        if (state.upText.isNotEmpty()) {
            val annotatedText = buildAnnotatedString {
                val text = state.upText
                val cursor = state.upCursor
                if (cursor < 0) {
                    // 无光标
                    withStyle(SpanStyle(color = visuals.textColor, fontSize = 16.sp)) {
                        append(text)
                    }
                } else {
                    // 有光标：前半 + 光标竖线 + 后半
                    val clampedCursor = cursor.coerceAtMost(text.length)
                    if (clampedCursor > 0) {
                        withStyle(SpanStyle(color = visuals.textColor, fontSize = 16.sp)) {
                            append(text.substring(0, clampedCursor))
                        }
                    }
                    // 光标竖线
                    withStyle(
                        SpanStyle(
                            color = visuals.textColor,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    ) {
                        append("\u2502")
                    }
                    if (clampedCursor < text.length) {
                        withStyle(SpanStyle(color = visuals.textColor, fontSize = 16.sp)) {
                            append(text.substring(clampedCursor))
                        }
                    }
                }
            }
            Text(text = annotatedText)
        }

        // 下行：auxDown
        if (state.downText.isNotEmpty()) {
            Text(
                text = state.downText,
                color = visuals.textColor,
                fontSize = 16.sp,
            )
        }
    }
}
