/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 录音时的频谱可视化（16 段，0~1）。
 *
 * 用 Goertzel 算法在 16 个对数间隔的中心频率上直接算单频点功率，省掉整段 FFT：
 * 每 100ms 一帧、512 点窗，16 个频点共 ~8k 次乘加，对输入法进程可忽略。
 * 非线程安全：只在录音线程逐帧调用。
 */
package org.fcitx.fcitx5.android.data.voice

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow

class VoiceSpectrum(
    private val bandCount: Int = 16,
    private val sampleRate: Int = 16000,
    private val windowSize: Int = 512,
) {
    companion object {
        private const val F_MIN = 120.0
        private const val F_MAX = 6800.0

        /** 归一化动态范围（dB）：最强频段往下 36dB 之内映射到 0~1。 */
        private const val RANGE_DB = 36f
    }

    private val coeffs = FloatArray(bandCount) { i ->
        val freq = F_MIN * (F_MAX / F_MIN).pow(i.toDouble() / (bandCount - 1))
        (2.0 * cos(2.0 * PI * freq / sampleRate)).toFloat()
    }
    private val window = FloatArray(windowSize) { i ->
        (0.5 - 0.5 * cos(2.0 * PI * i / (windowSize - 1))).toFloat()
    }
    private val frame = FloatArray(windowSize)

    /** 分析最新的 [length] 个 short 采样，返回 [bandCount] 个 0~1 的频段强度。 */
    fun analyze(samples: ShortArray, length: Int): FloatArray {
        val n = minOf(windowSize, length)
        if (n <= 0) return FloatArray(bandCount)
        val offset = windowSize - n
        java.util.Arrays.fill(frame, 0f)
        for (i in 0 until n) {
            frame[offset + i] = samples[length - n + i] / 32768f * window[offset + i]
        }

        val dbs = FloatArray(bandCount)
        var maxDb = -120f
        for (b in 0 until bandCount) {
            val c = coeffs[b]
            var s1 = 0f
            var s2 = 0f
            for (v in frame) {
                val s0 = v + c * s1 - s2
                s2 = s1
                s1 = s0
            }
            val power = (s1 * s1 + s2 * s2 - c * s1 * s2).coerceAtLeast(0f)
            val db = 10f * log10(power / windowSize + 1e-9f)
            dbs[b] = db
            if (db > maxDb) maxDb = db
        }
        val floor = maxDb - RANGE_DB
        return FloatArray(bandCount) { b -> ((dbs[b] - floor) / RANGE_DB).coerceIn(0f, 1f) }
    }
}
