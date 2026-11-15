/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 16kHz 单声道 float 采样 → 16bit PCM WAV（纯逻辑，只用 java.io）。
 *
 * 调试页用它把「原始 / 处理后」两段音频落盘：既供应用内播放，也能导出到外部目录后用
 * 电脑试听。编码与各在线平台要求的输入格式一致（单声道 16bit）。
 */
package org.fcitx.fcitx5.android.data.voice.preprocess

import java.io.File

object VoiceWavWriter {

    /** WAV 头长度（RIFF/WAVE/fmt/data）。 */
    const val HEADER_BYTES = 44

    /** 编码后的字节数（含头）。 */
    fun byteCount(sampleCount: Int): Int = HEADER_BYTES + sampleCount * 2

    /** 把 [samples] 的 `[from, until)` 区间编码成 WAV 字节数组。 */
    fun encode(
        samples: FloatArray,
        sampleRate: Int,
        from: Int = 0,
        until: Int = samples.size,
    ): ByteArray {
        val start = from.coerceIn(0, samples.size)
        val end = until.coerceIn(start, samples.size)
        val count = end - start
        val out = ByteArray(byteCount(count))
        val dataBytes = count * 2

        fun ascii(offset: Int, text: String) {
            for (i in text.indices) out[offset + i] = text[i].code.toByte()
        }

        fun int32(offset: Int, value: Int) {
            out[offset] = (value and 0xFF).toByte()
            out[offset + 1] = ((value shr 8) and 0xFF).toByte()
            out[offset + 2] = ((value shr 16) and 0xFF).toByte()
            out[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }

        fun int16(offset: Int, value: Int) {
            out[offset] = (value and 0xFF).toByte()
            out[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }

        ascii(0, "RIFF")
        int32(4, 36 + dataBytes)
        ascii(8, "WAVE")
        ascii(12, "fmt ")
        int32(16, 16)                       // fmt 块长度
        int16(20, 1)                        // PCM
        int16(22, 1)                        // 单声道
        int32(24, sampleRate)
        int32(28, sampleRate * 2)           // 字节率 = 采样率 × 声道数 × 位深/8
        int16(32, 2)                        // 块对齐
        int16(34, 16)                       // 位深
        ascii(36, "data")
        int32(40, dataBytes)

        var offset = HEADER_BYTES
        for (i in start until end) {
            val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
            out[offset] = (v and 0xFF).toByte()
            out[offset + 1] = ((v shr 8) and 0xFF).toByte()
            offset += 2
        }
        return out
    }

    /** 编码并写入 [file]（覆盖）。 */
    fun write(
        file: File,
        samples: FloatArray,
        sampleRate: Int,
        from: Int = 0,
        until: Int = samples.size,
    ) {
        file.parentFile?.mkdirs()
        file.writeBytes(encode(samples, sampleRate, from, until))
    }
}
