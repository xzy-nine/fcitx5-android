/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Kingz Cheung
 *
 * 从 Xime (https://github.com/ximeiorg/xime) 的 service/VoiceRecognitionHandler.kt 中
 * 抽出的纯文本规则，见仓库根 NOTICE.md。抽出来的目的：这些判断是语音上屏正确性的关键
 * （部分结果 → 最终结果的增量/替换），且完全不依赖 Android，便于本仓库用纯 JVM 单测覆盖。
 */
package com.kingzcheung.xime.speech

/** 最终结果相对已写入 composing 的部分结果，应该如何上屏。 */
sealed interface VoiceCommitPlan {

    /** 最终结果已经被 composing 完整覆盖，只需结束 composing。 */
    data object FinishOnly : VoiceCommitPlan

    /** 最终结果以部分结果为前缀：只提交增量，避免重复上屏。 */
    data class Append(val remainder: String) : VoiceCommitPlan

    /** 最终结果与部分结果不一致：整段提交（IME 侧 commitText 会替换已有 composing）。 */
    data class Replace(val text: String) : VoiceCommitPlan
}

object VoiceTextRules {

    /** 句末标点（含中英文），用于避免重复追加。 */
    private const val SENTENCE_END = "。！？；：，、；：,.!?;:，"

    /** 部分结果清洗：语音引擎的空间标记与多余空格不显示。 */
    fun cleanPartial(text: String): String = text.replace(" ", "")

    /**
     * 计算上屏计划（与 Xime 的 commitFinal 判定逐字一致）。
     *
     * @param finalText 最终识别文本（已带标点）
     * @param partial 之前写入 composing 的部分结果
     */
    fun planCommit(finalText: String, partial: String): VoiceCommitPlan = when {
        partial.isEmpty() -> VoiceCommitPlan.Replace(finalText)
        !finalText.startsWith(partial) -> VoiceCommitPlan.Replace(finalText)
        else -> {
            val remainder = finalText.substring(partial.length)
            if (remainder.isEmpty()) VoiceCommitPlan.FinishOnly
            else VoiceCommitPlan.Append(remainder)
        }
    }

    /**
     * 追加启发式句末标点（逐字取自 Xime 的 addPunctuation/heuristicPunctuation）：
     * 末尾已有句末标点则不追加，疑问句加「？」、极短句加「，」，其余加「。」。
     */
    fun addPunctuation(text: String): String {
        val cleanText = text.trim().replace(" ", "")
        if (cleanText.isEmpty()) return text
        if (cleanText.last() in SENTENCE_END) return cleanText
        return cleanText + heuristicPunctuation(cleanText)
    }

    fun heuristicPunctuation(text: String): String = when {
        text.any { it in "吗呢么吧" } ||
                text.contains("什么") ||
                text.contains("怎么") ||
                text.contains("为什么") ||
                text.contains("如何") ||
                text.contains("哪") -> "？"

        text.length < 4 -> "，"
        else -> "。"
    }
}
