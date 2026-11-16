/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin.migrate

/**
 * 把 libime 的音节编码还原为拼音文本。
 *
 * 编码按音节成对出现：每两个字节依次是声母与韵母的枚举值。
 * 两个枚举的取值都从字符 `'A'` 起连续编号，`0` 表示「无效/缺省」，
 * 因此表按 `枚举值 - 'A'` 索引。
 */
internal object PinyinCode {

    /** 枚举的起始字符值。 */
    private const val BASE = 'A'.code

    /** 声母表，索引为 `枚举值 - 'A'`。 */
    private val INITIALS = arrayOf(
        "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h",
        "j", "q", "x", "zh", "ch", "sh", "r", "z", "c", "s", "y", "w", "",
    )

    /** 韵母表，索引为 `枚举值 - 'A'`。 */
    private val FINALS = arrayOf(
        "a", "ai", "an", "ang", "ao", "e", "ei", "en", "eng", "er",
        "o", "ong", "ou", "i", "ia", "ie", "iao", "iu", "ian", "in",
        "iang", "ing", "iong", "u", "ua", "uo", "uai", "ui", "uan",
        "un", "uang", "v", "ve", "ue", "ng", "",
    )

    /** 韵母表中 v / ve / ue 的索引，用于 n、l 之后的 ü 写法。 */
    private val V = "v".let { FINALS.indexOf(it) }
    private val VE = "ve".let { FINALS.indexOf(it) }
    private val UE = "ue".let { FINALS.indexOf(it) }

    /**
     * 解码一段音节编码为以空格分隔的拼音串。
     *
     * 声母缺省时只输出韵母；`v`/`ve`/`ue` 在 n、l 之后写作 ü/üe。
     * 编码长度为奇数或含越界值时返回空串，交由调用方跳过该词条。
     */
    fun decode(code: ByteArray): String {
        if (code.isEmpty() || code.size % 2 != 0) return ""
        val sb = StringBuilder(code.size)
        var i = 0
        while (i < code.size) {
            val initial = lookup(INITIALS, code[i]) ?: return ""
            val finalIndex = lookupIndex(FINALS, code[i + 1]) ?: return ""
            // 音节之间以空格分隔，与 Rime 用户词库的编码写法一致
            if (i > 0) sb.append(' ')
            sb.append(initial).append(renderFinal(initial, finalIndex))
            i += 2
        }
        return sb.toString()
    }

    /** 按枚举值取表项；`0`（缺省）返回空串。 */
    private fun lookup(table: Array<String>, raw: Byte): String? {
        val value = raw.toInt() and 0xFF
        if (value == 0) return ""
        val index = value - BASE
        return table.getOrNull(index)
    }

    private fun lookupIndex(table: Array<String>, raw: Byte): Int? {
        val value = raw.toInt() and 0xFF
        if (value == 0) return null
        val index = value - BASE
        return if (index in table.indices) index else null
    }

    /** 韵母文本：ü 系韵母在 n、l 之后写作 ü/üe。 */
    private fun renderFinal(initial: String, finalIndex: Int): String {
        val final = FINALS[finalIndex]
        if (initial != "n" && initial != "l") return final
        return when (finalIndex) {
            V -> "ü"
            VE, UE -> "üe"
            else -> final
        }
    }
}
