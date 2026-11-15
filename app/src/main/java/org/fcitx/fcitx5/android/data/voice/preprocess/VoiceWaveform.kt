/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 波形绘制用的降采样（纯逻辑，无 Android 依赖）。
 *
 * 整段录音有几十万采样，按像素桶取峰值包络后再画，绘制开销与视图宽度成正比。
 */
package org.fcitx.fcitx5.android.data.voice.preprocess

import kotlin.math.abs

object VoiceWaveform {

    /**
     * 取 [buckets] 个桶的峰值包络（0..1）。
     *
     * 桶边界按区间长度比例均分并向下取整，相邻桶不重叠、合起来覆盖整个区间；
     * [normalize] 为 true 时按全局峰值归一（弱信号也能看清形状）。
     */
    fun peaks(
        samples: FloatArray,
        buckets: Int,
        from: Int = 0,
        until: Int = samples.size,
        normalize: Boolean = false,
    ): FloatArray {
        if (buckets <= 0) return FloatArray(0)
        val start = from.coerceIn(0, samples.size)
        val end = until.coerceIn(start, samples.size)
        val length = end - start
        val out = FloatArray(buckets)
        if (length <= 0) return out

        var maxPeak = 0f
        for (b in 0 until buckets) {
            val bucketStart = start + (length.toLong() * b / buckets).toInt()
            val bucketEnd = start + (length.toLong() * (b + 1) / buckets).toInt()
            var peak = 0f
            for (i in bucketStart until bucketEnd) {
                val v = abs(samples[i])
                if (v > peak) peak = v
            }
            out[b] = peak
            if (peak > maxPeak) maxPeak = peak
        }

        if (normalize && maxPeak > 0f) {
            for (b in out.indices) out[b] = (out[b] / maxPeak).coerceIn(0f, 1f)
        }
        return out
    }

    /** 区间内的峰值幅度（0..1）；用于把门限 dBFS 映射到归一化包络的纵轴上。 */
    fun peakAmplitude(
        samples: FloatArray,
        from: Int = 0,
        until: Int = samples.size,
    ): Float {
        val start = from.coerceIn(0, samples.size)
        val end = until.coerceIn(start, samples.size)
        var peak = 0f
        for (i in start until end) {
            val v = abs(samples[i])
            if (v > peak) peak = v
        }
        return peak
    }
}
