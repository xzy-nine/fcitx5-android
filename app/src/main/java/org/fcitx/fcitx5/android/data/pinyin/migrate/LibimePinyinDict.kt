/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin.migrate

import com.qyntrax.unzstd.ZstdInputStream
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * 读取 libime 拼音词库的二进制格式。
 *
 * 文件头为 magic 与版本号（大端 u32）：
 * - 版本 1：正文是未压缩的 trie
 * - 版本 2：正文是 zstd 压缩的 trie
 *
 * trie 的键是 `拼音编码 + '!' + 汉字`，值是词频。
 */
internal object LibimePinyinDict {

    /** 拼音词库的二进制魔数。 */
    const val MAGIC = 0x000fc613

    /** 拼音编码与汉字之间的分隔符。 */
    private const val HANZI_SEPARATOR = '!'.code.toByte()

    /** 一条词条：汉字与对应拼音编码。 */
    class Word(val hanzi: String, val code: ByteArray, val weight: Int)

    /**
     * 读取词库中的全部词条。
     *
     * [weightOf] 把 trie 里的 32 位值还原为词频；返回 null 表示该值不是有效词频。
     */
    fun read(file: File, weightOf: (Int) -> Float? = ::defaultWeight): List<Word> {
        FileInputStream(file).use { return read(it, weightOf) }
    }

    fun read(input: InputStream, weightOf: (Int) -> Float? = ::defaultWeight): List<Word> {
        val header = DataInputStream(BufferedInputStream(input))
        val magic = header.readInt()
        if (magic != MAGIC) throw IOException("不是 libime 拼音词库")
        val body: InputStream = when (val version = header.readInt()) {
            1 -> header
            2 -> ZstdInputStream(header)
            else -> throw IOException("不支持的 libime 词库版本：$version")
        }
        val trie = CedarTrie.read(body)
        val result = ArrayList<Word>()
        for (entry in trie.entries()) {
            if (weightOf(entry.value) == null) continue
            val sep = entry.key.indexOf(HANZI_SEPARATOR)
            if (sep <= 0 || sep == entry.key.size - 1) continue
            val hanzi = String(entry.key, sep + 1, entry.key.size - sep - 1, Charsets.UTF_8)
            if (hanzi.isEmpty()) continue
            result += Word(hanzi, entry.key.copyOfRange(0, sep), entry.value)
        }
        return result
    }

    /**
     * 词频的默认还原方式：trie 直接存 float 的位模式。
     *
     * 非有限值表示该节点只是中间节点，不构成词条。
     */
    fun defaultWeight(raw: Int): Float? {
        val value = Float.fromBits(raw)
        return if (value.isFinite()) value else null
    }
}
