/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.voice.preprocess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** WAV 落盘（调试页试听/导出）与波形包络的纯逻辑校验。 */
class VoiceWavWriterTest {

    @Test
    fun `header describes 16k mono 16bit pcm`() {
        val bytes = VoiceWavWriter.encode(FloatArray(10), SAMPLE_RATE)

        assertEquals(VoiceWavWriter.byteCount(10), bytes.size)
        assertEquals("RIFF", ascii(bytes, 0, 4))
        assertEquals("WAVE", ascii(bytes, 8, 4))
        assertEquals("fmt ", ascii(bytes, 12, 4))
        assertEquals("data", ascii(bytes, 36, 4))
        assertEquals(36 + 20, le32(bytes, 4))
        assertEquals(16, le32(bytes, 16))
        assertEquals(1, le16(bytes, 20))
        assertEquals(1, le16(bytes, 22))
        assertEquals(SAMPLE_RATE, le32(bytes, 24))
        assertEquals(SAMPLE_RATE * 2, le32(bytes, 28))
        assertEquals(2, le16(bytes, 32))
        assertEquals(16, le16(bytes, 34))
        assertEquals(20, le32(bytes, 40))
    }

    @Test
    fun `samples are clipped and written little endian`() {
        val bytes = VoiceWavWriter.encode(
            floatArrayOf(0f, 1f, -1f, 2f, -2f),
            SAMPLE_RATE,
        )

        assertEquals(0, le16(bytes, 44))
        assertEquals(32767, le16(bytes, 46))
        assertEquals(-32767, le16(bytes, 48))
        assertEquals(32767, le16(bytes, 50))
        assertEquals(-32767, le16(bytes, 52))
    }

    @Test
    fun `sub range is encoded alone`() {
        val bytes = VoiceWavWriter.encode(floatArrayOf(1f, 0f, -1f), SAMPLE_RATE, from = 1, until = 2)

        assertEquals(VoiceWavWriter.byteCount(1), bytes.size)
        assertEquals(0, le16(bytes, 44))
    }

    @Test
    fun `write creates the parent directory`() {
        val dir = Files.createTempDirectory("voice-wav").toFile()
        val target = File(File(dir, "nested"), "sample.wav")
        try {
            VoiceWavWriter.write(target, floatArrayOf(1f, -1f), SAMPLE_RATE)
            assertTrue(target.isFile)
            assertEquals(VoiceWavWriter.byteCount(2).toLong(), target.length())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `peaks cover the whole range`() {
        val samples = FloatArray(1000) { if (it < 500) 0.1f else 0.8f }
        val peaks = VoiceWaveform.peaks(samples, 10)

        assertEquals(10, peaks.size)
        assertEquals(0.1f, peaks[0], 1e-5f)
        assertEquals(0.8f, peaks[9], 1e-5f)
    }

    @Test
    fun `normalize scales to the global peak`() {
        val samples = FloatArray(1000) { if (it < 500) 0.1f else 0.8f }
        val peaks = VoiceWaveform.peaks(samples, 10, normalize = true)

        assertEquals(1f, peaks.max(), 1e-5f)
        assertEquals(0.125f, peaks[0], 1e-5f)
    }

    @Test
    fun `empty range yields silent buckets`() {
        val peaks = VoiceWaveform.peaks(FloatArray(0), 4)

        assertEquals(4, peaks.size)
        assertEquals(0f, peaks.sum(), 0f)
        assertEquals(0f, VoiceWaveform.peakAmplitude(FloatArray(0), 0, 0), 0f)
    }

    @Test
    fun `peak amplitude respects the sub range`() {
        val samples = floatArrayOf(0.2f, -0.9f, 0.3f)

        assertEquals(0.9f, VoiceWaveform.peakAmplitude(samples), 1e-6f)
        assertEquals(0.2f, VoiceWaveform.peakAmplitude(samples, 0, 1), 1e-6f)
        assertEquals(0.3f, VoiceWaveform.peakAmplitude(samples, 2, 3), 1e-6f)
    }
}

private const val SAMPLE_RATE = 16000

private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
    String(bytes, offset, length, Charsets.US_ASCII)

/** 小端 16bit，按补码解释（与 WAV 里的有符号采样一致）。 */
private fun le16(bytes: ByteArray, offset: Int): Int = u16(bytes, offset).toShort().toInt()

private fun u16(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

private fun le32(bytes: ByteArray, offset: Int): Int =
    u16(bytes, offset) or (u16(bytes, offset + 2) shl 16)
