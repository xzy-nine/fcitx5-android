/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class OnnxMetadataTest {

    /** 手工拼一个 protobuf 字段：tag = (field << 3) | wire。 */
    private fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())

    private fun varint(value: Long): ByteArray {
        var v = value
        val out = ArrayList<Byte>()
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.add(b.toByte())
                break
            }
            out.add((b or 0x80).toByte())
        }
        return out.toByteArray()
    }

    private fun lenDelimited(field: Int, payload: ByteArray) =
        tag(field, 2) + varint(payload.size.toLong()) + payload

    private fun entry(key: String, value: String) =
        lenDelimited(1, key.toByteArray()) + lenDelimited(2, value.toByteArray())

    @Test
    fun `reads metadata props and skips unknown fields`() {
        // 字段 1（ir_version, varint）+ 字段 8（graph，应被整体跳过）+ 字段 14（metadata_props）
        val bytes = tag(1, 0) + varint(7L) +
                lenDelimited(8, ByteArray(5000) { 0x42 }) +
                lenDelimited(14, entry("decode_chunk_len", "32")) +
                lenDelimited(14, entry("model_type", "zipformer2"))
        val meta = OnnxMetadata.read(ByteArrayInputStream(bytes))
        assertEquals("32", meta["decode_chunk_len"])
        assertEquals("zipformer2", meta["model_type"])
    }

    @Test
    fun `skips fixed width fields`() {
        val bytes = tag(3, 5) + ByteArray(4) +           // fixed32
                tag(4, 1) + ByteArray(8) +               // fixed64
                lenDelimited(14, entry("n_mels", "80"))
        val meta = OnnxMetadata.read(ByteArrayInputStream(bytes))
        assertEquals("80", meta["n_mels"])
    }

    @Test
    fun `empty and garbage input yield empty map`() {
        assertTrue(OnnxMetadata.read(ByteArrayInputStream(ByteArray(0))).isEmpty())
        // 截断的长度前缀不应抛异常
        val truncated = tag(14, 2) + varint(999L) + byteArrayOf(1, 2, 3)
        assertTrue(OnnxMetadata.read(ByteArrayInputStream(truncated)).isEmpty())
    }

    @Test
    fun `entry without key is ignored`() {
        val bytes = lenDelimited(14, lenDelimited(2, "orphan".toByteArray()))
        assertTrue(OnnxMetadata.read(ByteArrayInputStream(bytes)).isEmpty())
    }

    @Test
    fun `unicode values survive`() {
        val bytes = lenDelimited(14, entry("comment", "流式 zipformer2"))
        assertEquals("流式 zipformer2", OnnxMetadata.read(ByteArrayInputStream(bytes))["comment"])
    }

    @Test
    fun `null-ish lookups behave`() {
        val meta = OnnxMetadata.read(ByteArrayInputStream(lenDelimited(14, entry("a", ""))))
        assertEquals("", meta["a"])
        assertNull(meta["missing"])
    }
}
