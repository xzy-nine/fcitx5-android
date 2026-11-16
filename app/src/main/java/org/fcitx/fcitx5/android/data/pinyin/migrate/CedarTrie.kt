/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin.migrate

import java.io.DataInputStream
import java.io.InputStream

/**
 * 双数组 trie 的只读解析器，对应 libime 中 cedar 的序列化格式。
 *
 * 文件布局（字段顺序固定，整型为大端序）：
 * ```
 * u32    tail 长度
 * u32    node 数量
 * u8[]   tail 数据
 * node[] 节点：base i32, check i32
 * u32    bheadF
 * u32    bheadC
 * u32    bheadO
 * ninfo[] 每节点 2 字节：sibling, child
 * block[] 每 256 节点一块
 * ```
 *
 * 节点的 `base` 为负表示键的剩余部分存于 tail，其绝对值是 tail 偏移；
 * 为正是子节点基址，`base xor 字符` 即子节点下标。
 */
internal class CedarTrie private constructor(
    private val tail: ByteArray,
    private val base: IntArray,
    private val check: IntArray,
    private val sibling: IntArray,
    private val child: IntArray,
) {
    /** 一条键值对；[key] 为原始字节序列，[value] 为该键的 32 位数据。 */
    class Entry(val key: ByteArray, val value: Int)

    /** 遍历游标，对应 cedar 的 `npos_t` 与 `len` 引用参数。 */
    private class Cursor {
        var from = 0
        var offset = 0
        var len = 0
    }

    /**
     * 按 cedar 的深度优先顺序遍历全部键值对。
     *
     * 单次遍历的节点数有上限保护：损坏数据不会导致无限循环。
     */
    fun entries(): Sequence<Entry> = sequence {
        val cursor = Cursor()
        var visited = 0
        val limit = base.size * 8 + 1024
        while (true) {
            val result = begin(cursor)
            if (result == NO_PATH) break
            if (result != NO_VALUE) {
                yield(Entry(suffix(cursor), result))
            }
            if (!next(cursor)) break
            if (++visited > limit) break
        }
    }

    /** 找到当前游标下的第一个键，返回其值，或 [NO_PATH] / [NO_VALUE]。 */
    private fun begin(cursor: Cursor): Int {
        var b = if (cursor.offset != 0) -cursor.offset else base[cursor.from]
        if (b >= 0) {
            var c = child[cursor.from]
            if (cursor.from == 0) {
                c = sibling[b xor c]
                if (c == 0) return NO_PATH
            }
            while (c != 0 && b >= 0) {
                cursor.from = b xor c
                b = base[cursor.from]
                c = child[cursor.from]
                cursor.len++
            }
            if (b >= 0) {
                return base[b xor c]
            }
        }
        val tailLen = strlen(-b)
        cursor.offset = -b + tailLen
        cursor.len += tailLen
        return readInt(tail, -b + tailLen + 1)
    }

    /** 前进到下一个键；返回 false 表示已遍历完毕。 */
    private fun next(cursor: Cursor): Boolean {
        var c = 0
        if (cursor.offset != 0) {
            cursor.len -= cursor.offset - (-base[cursor.from])
            cursor.offset = 0
        } else {
            c = sibling[base[cursor.from]]
        }
        while (c == 0 && (cursor.from != 0 || cursor.offset != 0)) {
            c = sibling[cursor.from]
            cursor.from = check[cursor.from]
            cursor.len--
        }
        if (c == 0) return false
        cursor.from = base[cursor.from] xor c
        cursor.len++
        // 进入新子树后立刻定位到其首个叶子
        var b = if (cursor.offset != 0) -cursor.offset else base[cursor.from]
        if (b >= 0) {
            var cc = child[cursor.from]
            if (cursor.from == 0) {
                cc = sibling[b xor cc]
            }
            while (cc != 0 && b >= 0) {
                cursor.from = b xor cc
                b = base[cursor.from]
                cc = child[cursor.from]
                cursor.len++
            }
            if (b >= 0) return true
        }
        val tailLen = strlen(-b)
        cursor.offset = -b + tailLen
        cursor.len += tailLen
        return true
    }

    /** 由当前游标回溯出完整键字节。 */
    private fun suffix(cursor: Cursor): ByteArray {
        var from = cursor.from
        var len = cursor.len
        val key = ByteArray(len)
        if (cursor.offset != 0) {
            var tailLen = strlen(-base[from])
            if (len > tailLen) {
                len -= tailLen
            } else {
                tailLen = len
                len = 0
            }
            System.arraycopy(tail, cursor.offset - tailLen, key, len, tailLen)
        }
        while (len > 0) {
            len--
            val parent = check[from]
            key[len] = (base[parent] xor from).toByte()
            from = parent
        }
        return key
    }

    /** tail 中自 [start] 起、以 0 结尾的字节数。 */
    private fun strlen(start: Int): Int {
        var i = start
        while (i < tail.size && tail[i] != 0.toByte()) i++
        return i - start
    }

    companion object {
        const val NO_VALUE = -1
        const val NO_PATH = -2

        private fun readInt(a: ByteArray, at: Int): Int =
            ((a[at].toInt() and 0xFF) shl 24) or
                ((a[at + 1].toInt() and 0xFF) shl 16) or
                ((a[at + 2].toInt() and 0xFF) shl 8) or
                (a[at + 3].toInt() and 0xFF)

        /** 读取一棵序列化的 trie。 */
        fun read(input: InputStream): CedarTrie {
            val stream = input as? DataInputStream ?: DataInputStream(input)
            val tailLen = stream.readInt()
            val nodeCount = stream.readInt()
            require(tailLen > 0 && nodeCount > 0) { "非法的 trie 头部" }

            val tail = ByteArray(tailLen)
            stream.readFully(tail)

            val base = IntArray(nodeCount)
            val check = IntArray(nodeCount)
            for (i in 0 until nodeCount) {
                base[i] = stream.readInt()
                check[i] = stream.readInt()
            }
            // bheadF / bheadC / bheadO 只用于插入
            stream.readInt()
            stream.readInt()
            stream.readInt()

            val sibling = IntArray(nodeCount)
            val child = IntArray(nodeCount)
            for (i in 0 until nodeCount) {
                sibling[i] = stream.readUnsignedByte()
                child[i] = stream.readUnsignedByte()
            }
            return CedarTrie(tail, base, check, sibling, child)
        }
    }
}
