/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 语音上屏的纯文本规则（无 Android 依赖，便于 JVM 单测）。
 *
 * 本地 sherpa-onnx 模型不输出标点（在线平台的模型一般自带标点，因此这里的追加规则对它们
 * 基本是空操作），所以最终上屏前补一个启发式句末标点，保持与内置输入体验一致。
 */
package org.fcitx.fcitx5.android.data.voice

object VoiceTextRules {

    /** 句末/停顿标点：末尾已是其中之一就不再追加。 */
    private const val TRAILING_PUNCTUATION = "。！？!?；;：:，,、…～~—"

    /** 疑问语气词/疑问词：命中则用问号。 */
    private val QUESTION_HINTS = listOf(
        "吗", "呢", "吧", "么", "什么", "怎么", "怎样", "如何", "哪", "多少", "是不是",
    )

    /** 部分结果清洗：引擎可能带空格（英文词间保留单空格，其他空白去掉）。 */
    fun cleanPartial(text: String): String = text.trim()

    /**
     * 追加启发式句末标点：空文本或已带标点则原样返回；
     * 疑问语气用「？」，其余用「。」。
     */
    fun addPunctuation(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return text
        if (trimmed.last() in TRAILING_PUNCTUATION) return trimmed
        return trimmed + if (isQuestion(trimmed)) "？" else "。"
    }

    /** 是否为疑问句（按常见疑问语气词/疑问词判断）。 */
    fun isQuestion(text: String): Boolean = QUESTION_HINTS.any { text.contains(it) }
}
