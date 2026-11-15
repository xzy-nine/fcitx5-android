/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 只读 ONNX 文件的 `metadata_props`（ModelProto 第 14 号字段）。
 *
 * 为什么不用 ORT 会话读：sherpa-onnx 的 C++ 侧通过 `Ort::ModelMetadata` 拿到的
 * 就是同一份 `metadata_props`，而为了读几 KB 元数据去 `OrtSession` 加载一个几百 MB
 * 的模型要多花数秒。这里按 protobuf 线格式**跳过 graph 等大字段**，只取元数据，
 * 毫秒级完成。
 *
 * 解析只依赖「字段号 + 线格式」：遇到不认识的字段按其长度整体跳过，
 * 因此对 ONNX 版本演进是健壮的。
 */
package org.fcitx.fcitx5.android.data.voice

import java.io.EOFException
import java.io.File
import java.io.InputStream

object OnnxMetadata {

    /** ModelProto.metadata_props 的字段号。 */
    private const val FIELD_METADATA_PROPS = 14

    /** proto 线格式。 */
    private const val WIRE_VARINT = 0
    private const val WIRE_LENGTH_DELIMITED = 2
    private const val WIRE_FIXED64 = 1
    private const val WIRE_FIXED32 = 5

    /** 单次扫描允许跳过的最大字节数（防止损坏文件让我们空转到天荒地老）。 */
    private const val MAX_SKIP = 1L shl 33

    /**
     * 读取 [file] 的元数据键值对；文件不可读或格式不对时返回空表。
     *
     * 不做全量校验——本函数只用于「判定模型家族」，读不到就当作无法判定，
     * 由调用方拒绝该模型（而不是冒险按错误家族去构造原生引擎）。
     */
    fun read(file: File): Map<String, String> = try {
        file.inputStream().buffered().use { read(it) }
    } catch (e: Exception) {
        emptyMap()
    }

    /**
     * 从流里读取（便于单测直接喂字节）。
     *
     * 文件可能被截断或根本不是 ONNX：任何解析异常都只意味着「读不到元数据」，
     * 因此返回已解析到的部分（而不是把异常抛给市场页）。
     */
    fun read(stream: InputStream): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        try {
            while (true) {
                val tag = readVarint(stream) ?: break // EOF：顶层字段读完
                val field = (tag ushr 3).toInt()
                val wire = (tag and 0x7L).toInt()

                if (field == FIELD_METADATA_PROPS && wire == WIRE_LENGTH_DELIMITED) {
                    val length = readVarint(stream) ?: break
                    val payload = readExact(stream, length)
                    parseEntry(payload)?.let { (k, v) -> result[k] = v }
                } else {
                    if (!skipField(stream, wire)) break
                }
            }
        } catch (e: Exception) {
            // 截断/损坏：保留已读到的键，交由调用方按「判据不足」处理
        }
        return result
    }

    /** StringStringEntryProto：1=key，2=value。 */
    private fun parseEntry(payload: ByteArray): Pair<String, String>? {
        var key: String? = null
        var value = ""
        var index = 0
        while (index < payload.size) {
            val (tag, next) = varintAt(payload, index) ?: return null
            index = next
            val field = (tag ushr 3).toInt()
            val wire = (tag and 0x7L).toInt()
            if (wire != WIRE_LENGTH_DELIMITED) {
                index = skipAt(payload, index, wire) ?: return null
                continue
            }
            val (length, afterLen) = varintAt(payload, index) ?: return null
            val end = afterLen + length.toInt()
            if (end > payload.size) return null
            val text = String(payload, afterLen, length.toInt(), Charsets.UTF_8)
            when (field) {
                1 -> key = text
                2 -> value = text
            }
            index = end
        }
        val k = key ?: return null
        return k to value
    }

    /** 跳过当前字段；返回 false 表示流已不可用。 */
    private fun skipField(stream: InputStream, wire: Int): Boolean = when (wire) {
        WIRE_VARINT -> readVarint(stream) != null
        WIRE_LENGTH_DELIMITED -> {
            val length = readVarint(stream) ?: return false
            if (length < 0 || length > MAX_SKIP) return false
            skipExact(stream, length)
        }

        WIRE_FIXED64 -> skipExact(stream, 8)
        WIRE_FIXED32 -> skipExact(stream, 4)
        // 3/4 是已废弃的 group；遇到即认为文件不可解析
        else -> false
    }

    private fun readExact(stream: InputStream, length: Long): ByteArray {
        if (length < 0 || length > Int.MAX_VALUE) throw EOFException("bad length $length")
        val buffer = ByteArray(length.toInt())
        var read = 0
        while (read < buffer.size) {
            val n = stream.read(buffer, read, buffer.size - read)
            if (n < 0) throw EOFException("unexpected EOF")
            read += n
        }
        return buffer
    }

    private fun skipExact(stream: InputStream, length: Long): Boolean {
        var remaining = length
        val scratch = ByteArray(8 * 1024)
        while (remaining > 0) {
            val n = stream.read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
            if (n < 0) return false
            remaining -= n
        }
        return true
    }

    /** 读一个 varint；EOF 返回 null。 */
    private fun readVarint(stream: InputStream): Long? {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            val b = stream.read()
            if (b < 0) return null
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        return null
    }

    /** 数组内读 varint；返回 (值, 新下标)。 */
    private fun varintAt(data: ByteArray, start: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var index = start
        while (shift < 64 && index < data.size) {
            val b = data[index].toInt()
            index++
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result to index
            shift += 7
        }
        return null
    }

    /** 数组内跳过字段；返回新下标。 */
    private fun skipAt(data: ByteArray, index: Int, wire: Int): Int? = when (wire) {
        WIRE_VARINT -> varintAt(data, index)?.second
        WIRE_LENGTH_DELIMITED -> {
            val (length, after) = varintAt(data, index) ?: return null
            val end = after + length.toInt()
            if (end > data.size) null else end
        }

        WIRE_FIXED64 -> (index + 8).takeIf { it <= data.size }
        WIRE_FIXED32 -> (index + 4).takeIf { it <= data.size }
        else -> null
    }
}
