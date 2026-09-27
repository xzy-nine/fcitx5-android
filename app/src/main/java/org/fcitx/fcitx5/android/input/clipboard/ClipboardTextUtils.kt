/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import kotlin.math.min

/**
 * 剪贴板条目文本摘要工具（原寄生在死 RecyclerView 适配器 [ClipboardAdapter] 的
 * `companion.excerptText`，供 [ComposeClipboard] 复用，使后者不再依赖 View 适配器）。
 *
 * 截断超长文本以降低渲染开销：每行最多 [chars] 个字符，最多 [lines] 行。
 *
 * @param str 原文
 * @param mask 是否用「•」遮蔽文本（敏感内容）
 * @param lines 最大输出行数
 * @param chars 每行最大字符数
 */
fun excerptClipboardText(
    str: String,
    mask: Boolean = false,
    lines: Int = 4,
    chars: Int = 128,
): String = buildString {
    val length = str.length
    var lineBreak = -1
    for (i in 1..lines) {
        val start = lineBreak + 1   // skip previous '\n'
        val excerptEnd = min(start + chars, length)
        lineBreak = str.indexOf('\n', start)
        if (lineBreak < 0) {
            // no line breaks remaining, substring to end of text
            if (mask) {
                append(ClipboardEntry.BULLET.repeat(excerptEnd - start))
            } else {
                append(str.substring(start, excerptEnd))
            }
            break
        } else {
            val end = min(excerptEnd, lineBreak)
            // append one line exactly
            if (mask) {
                append(ClipboardEntry.BULLET.repeat(end - start))
            } else {
                appendLine(str.substring(start, end))
            }
        }
    }
}
