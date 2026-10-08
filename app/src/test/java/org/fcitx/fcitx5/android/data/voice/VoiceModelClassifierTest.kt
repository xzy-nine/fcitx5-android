/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VoiceModelClassifierTest {

    @get:Rule
    val temp = TemporaryFolder()

    // ---- 构造「像 ONNX」的目录：只有 metadata_props，足够让判定器读取 ---

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

    private fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())

    private fun lenDelimited(field: Int, payload: ByteArray) =
        tag(field, 2) + varint(payload.size.toLong()) + payload

    private fun entry(key: String, value: String) =
        lenDelimited(1, key.toByteArray()) + lenDelimited(2, value.toByteArray())

    /** 拼一个含 metadata_props 的「类 ONNX」文件；空元数据也要写够字节以免被当成空文件。 */
    private fun onnx(meta: Map<String, String>): ByteArray {
        val body = meta.entries.fold(ByteArray(0)) { acc, (k, v) -> acc + lenDelimited(14, entry(k, v)) }
        // 字段 8（graph）填充：让文件非空且更像真实模型
        return lenDelimited(8, ByteArray(64) { 0x42 }) + body
    }

    private fun dir(vararg files: Pair<String, ByteArray>): File {
        val d = temp.newFolder()
        File(d, "tokens.txt").writeText("a 1\n")
        files.forEach { (name, bytes) -> File(d, name).writeBytes(bytes) }
        return d
    }

    private fun classify(dir: File): VoiceModelSpec? =
        VoiceModelClassifier.classify(dir, VoiceFileShape.roles(dir))

    // ---- 关键歧义：在线 vs 离线 transducer（文件形状完全一样）----

    @Test
    fun `streaming geomatry in encoder means online transducer`() {
        val d = dir(
            "encoder.onnx" to onnx(
                mapOf("encoder_dims" to "1,2", "decode_chunk_len" to "32", "T" to "39")
            ),
            "decoder.onnx" to onnx(mapOf("vocab_size" to "500", "context_size" to "2")),
            "joiner.onnx" to onnx(mapOf("joiner_dim" to "512")),
        )
        assertEquals(VoiceModelFamily.OnlineTransducer, classify(d)?.family)
    }

    @Test
    fun `offline transducer export lacks streaming geometry`() {
        // icefall 非流式导出的 encoder 只写 model_type/comment 等信息
        val d = dir(
            "encoder.onnx" to onnx(mapOf("model_type" to "zipformer2", "comment" to "non-streaming zipformer2")),
            "decoder.onnx" to onnx(mapOf("vocab_size" to "500", "context_size" to "2")),
            "joiner.onnx" to onnx(mapOf("joiner_dim" to "512")),
        )
        assertEquals(VoiceModelFamily.OfflineTransducer, classify(d)?.family)
    }

    // ---- 关键歧义：Whisper / 在线 paraformer / 离线 paraformer ----

    @Test
    fun `n_mels means whisper`() {
        val d = dir(
            "encoder.onnx" to onnx(mapOf("n_mels" to "80", "n_text_layer" to "4")),
            "decoder.onnx" to onnx(mapOf("n_vocab" to "51865")),
        )
        assertEquals(VoiceModelFamily.OfflineWhisper, classify(d)?.family)
    }

    @Test
    fun `encoder_output_size means online paraformer`() {
        val d = dir(
            "encoder.onnx" to onnx(
                mapOf("encoder_output_size" to "512", "lfr_window_size" to "7")
            ),
            "decoder.onnx" to onnx(mapOf("decoder_num_blocks" to "16")),
        )
        assertEquals(VoiceModelFamily.OnlineParaformer, classify(d)?.family)
    }

    @Test
    fun `firered asr encoder decoder identified by sos eos max_len`() {
        val d = dir(
            "encoder.onnx" to onnx(mapOf("sos" to "1", "eos" to "2", "max_len" to "448")),
            "decoder.onnx" to onnx(mapOf("num_decoder_layers" to "4")),
        )
        assertEquals(VoiceModelFamily.OfflineFireRedAsr, classify(d)?.family)
    }

    // ---- 单文件家族 ---

    @Test
    fun `sense voice identified by itn keys`() {
        val d = dir("model.onnx" to onnx(mapOf("with_itn" to "1", "without_itn" to "2")))
        assertEquals(VoiceModelFamily.OfflineSenseVoice, classify(d)?.family)
    }

    @Test
    fun `sense voice wins over offline paraformer despite shared lfr keys`() {
        // SenseVoice 与离线 paraformer 都带 lfr/归一化统计量；靠 ITN 与语种键区分，
        // 顺序错了会把它判成 paraformer
        val d = dir(
            "model.onnx" to onnx(
                mapOf(
                    "lfr_window_size" to "7",
                    "neg_mean" to "1.0",
                    "inv_stddev" to "2.0",
                    "lang_zh" to "3",
                    "with_itn" to "4",
                )
            )
        )
        assertEquals(VoiceModelFamily.OfflineSenseVoice, classify(d)?.family)
    }

    @Test
    fun `offline paraformer still identified without itn keys`() {
        val d = dir(
            "model.onnx" to onnx(
                mapOf("lfr_window_size" to "7", "neg_mean" to "1.0", "inv_stddev" to "2.0")
            )
        )
        assertEquals(VoiceModelFamily.OfflineParaformer, classify(d)?.family)
    }

    @Test
    fun `dolphin identified by invstd spelling`() {
        val d = dir("model.onnx" to onnx(mapOf("vocab_size" to "500", "invstd" to "0.5")))
        assertEquals(VoiceModelFamily.OfflineDolphin, classify(d)?.family)
    }

    @Test
    fun `zipformer2 ctc identified by streaming geometry`() {
        val d = dir(
            "model.onnx" to onnx(mapOf("encoder_dims" to "1,2", "decode_chunk_len" to "32"))
        )
        assertEquals(VoiceModelFamily.OnlineZipformer2Ctc, classify(d)?.family)
    }

    @Test
    fun `t one identified by state_dim`() {
        val d = dir("model.onnx" to onnx(mapOf("state_dim" to "512", "frame_length_ms" to "10")))
        assertEquals(VoiceModelFamily.OnlineToneCtc, classify(d)?.family)
    }

    @Test
    fun `online nemo ctc identified by chunk shift`() {
        val d = dir("model.onnx" to onnx(mapOf("chunk_shift" to "16", "window_size" to "2.0")))
        assertEquals(VoiceModelFamily.OnlineNemoCtc, classify(d)?.family)
    }

    @Test
    fun `online nemo transducer identified by cache dims`() {
        val d = dir(
            "encoder.onnx" to onnx(mapOf("cache_last_channel_dim1" to "1024")),
            "decoder.onnx" to onnx(mapOf("pred_rnn_layers" to "1")),
            "joiner.onnx" to onnx(mapOf("joiner_dim" to "640")),
        )
        assertEquals(VoiceModelFamily.OnlineTransducer, classify(d)?.family)
    }

    @Test
    fun `explicit model_type family is honoured`() {
        val d = dir("model.onnx" to onnx(mapOf("model_type" to "fire-red-asr-2-ctc")))
        assertEquals(VoiceModelFamily.OfflineFireRedAsrCtc, classify(d)?.family)
    }

    // ---- 拒绝而不是冒险：判不出家族时必须返回 null ----

    @Test
    fun `single file model with no discriminating metadata is rejected`() {
        // 没有任何家族专属键，也没有 model_type：单文件 CTC 家族无法还原，必须拒绝
        val d = dir("model.onnx" to onnx(mapOf("vocab_size" to "500")))
        assertNull(classify(d))
    }

    @Test
    fun `directory without tokens is not a model`() {
        val d = temp.newFolder()
        File(d, "encoder.onnx").writeBytes(onnx(mapOf("n_mels" to "80")))
        assertNull(classify(d))
        assertTrue(VoiceFileShape.roles(d).isEmpty())
    }

    @Test
    fun `directory without any onnx is not a model`() {
        val d = temp.newFolder()
        File(d, "tokens.txt").writeText("a 1\n")
        assertTrue(VoiceFileShape.roles(d).isEmpty())
    }

    // ---- 文件形状：角色分配与辅助文件排除 ----

    @Test
    fun `roles prefer int8 and exclude auxiliary files`() {
        val d = dir(
            "encoder-epoch-99.int8.onnx" to onnx(emptyMap()),
            "encoder-epoch-99.onnx" to onnx(emptyMap()),
            "decoder.onnx" to onnx(emptyMap()),
            "joiner.int8.onnx" to onnx(emptyMap()),
            "bpe.model" to ByteArray(16),
            "test_wavs_0.wav" to ByteArray(16),
        )
        val roles = VoiceFileShape.roles(d)
        assertTrue(roles.getValue(VoiceFileRole.ENCODER).name.contains("int8"))
        assertEquals("decoder.onnx", roles.getValue(VoiceFileRole.DECODER).name)
        assertTrue(roles.getValue(VoiceFileRole.JOINER).name.contains("int8"))
        assertEquals("tokens.txt", roles.getValue(VoiceFileRole.TOKENS).name)
        // 辅助文件不得被当成模型
        assertTrue(roles.values.none { it.name.endsWith(".model") || it.name.endsWith(".wav") })
    }

    @Test
    fun `unnamed single file becomes the model role`() {
        val d = dir("sherpa-onnx-model.onnx" to onnx(emptyMap()))
        assertEquals(
            "sherpa-onnx-model.onnx",
            VoiceFileShape.roles(d).getValue(VoiceFileRole.MODEL).name,
        )
    }

    @Test
    fun `spec carries absolute paths of every role`() {
        val d = dir(
            "encoder.onnx" to onnx(mapOf("decode_chunk_len" to "32", "encoder_dims" to "1")),
            "decoder.onnx" to onnx(mapOf("vocab_size" to "1")),
            "joiner.onnx" to onnx(mapOf("joiner_dim" to "1")),
        )
        val spec = classify(d)!!
        assertTrue(spec.files.values.all { File(it).isAbsolute })
        assertTrue(spec.files.getValue(VoiceFileRole.TOKENS).endsWith("tokens.txt"))
    }
}
