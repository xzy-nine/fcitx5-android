/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 手写识别的特征工程（**纯函数**，与 ochwpro 训练侧 `dataset.py` 的契约一致）。
 *
 * 模型输入为固定长度序列 `(T=200, D=5)`：
 *   [0] x_norm  — 以整段笔迹 bbox 归一化到 [0,1] 的 X
 *   [1] y_norm  — 同上 Y
 *   [2] dx      — 相邻点 X 增量 / rangeX
 *   [3] dy      — 相邻点 Y 增量 / rangeY
 *   [4] pen_down— 1=笔画中，0=抬笔（每笔最后一点）
 * 另一路输入是 `mask[200]`（bool），有效长度内为 true。
 *
 * 这些函数不依赖 Android，便于 JVM 单测。
 */
package org.fcitx.fcitx5.android.data.handwriting

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object HandwritingPreprocess {

    /** 模型固定序列长度（与 ochwpro 导出时的 seq_len 一致）。 */
    const val FIXED_LEN = 200

    /** 每笔最多保留的采样点数（重采样，防止长笔画把 200 点吃满）。 */
    const val MAX_POINTS_PER_STROKE = 8

    /** 特征维度。 */
    const val FEATURE_DIM = 5

    /** 把过长的笔画等距重采样到 [MAX_POINTS_PER_STROKE] 个点。 */
    fun simplifyStrokes(strokes: List<List<StrokePoint>>): List<List<StrokePoint>> =
        strokes.map { stroke ->
            if (stroke.size <= MAX_POINTS_PER_STROKE) {
                stroke
            } else {
                val step = (stroke.size - 1).toFloat() / (MAX_POINTS_PER_STROKE - 1)
                (0 until MAX_POINTS_PER_STROKE).map { i ->
                    stroke[(i * step).roundToInt().coerceIn(0, stroke.size - 1)]
                }
            }
        }

    /** 特征化结果：展平的 [FIXED_LEN * FEATURE_DIM] 特征 + 有效长度。 */
    data class Sequence(val data: FloatArray, val length: Int)

    fun strokesToSequence(strokes: List<List<StrokePoint>>): Sequence {
        val points = ArrayList<StrokePoint>()
        val penDown = ArrayList<Boolean>()
        for (stroke in strokes) {
            for (pt in stroke) {
                points.add(pt)
                penDown.add(true)
            }
            if (penDown.isNotEmpty()) penDown[penDown.size - 1] = false
        }
        val total = points.size
        if (total == 0) return Sequence(FloatArray(FIXED_LEN * FEATURE_DIM), 0)

        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (pt in points) {
            if (pt.x < minX) minX = pt.x
            if (pt.x > maxX) maxX = pt.x
            if (pt.y < minY) minY = pt.y
            if (pt.y > maxY) maxY = pt.y
        }
        // range 至少为 1，避免单点/直线书写时除零
        val rangeX = max(maxX - minX, 1f)
        val rangeY = max(maxY - minY, 1f)

        val kept = min(total, FIXED_LEN)
        val data = FloatArray(FIXED_LEN * FEATURE_DIM)
        for (i in 0 until kept) {
            val base = i * FEATURE_DIM
            data[base] = (points[i].x - minX) / rangeX
            data[base + 1] = (points[i].y - minY) / rangeY
            if (i == 0) {
                data[base + 2] = 0f
                data[base + 3] = 0f
            } else {
                data[base + 2] = (points[i].x - points[i - 1].x) / rangeX
                data[base + 3] = (points[i].y - points[i - 1].y) / rangeY
            }
            data[base + 4] = if (penDown[i]) 1f else 0f
        }
        return Sequence(data, kept)
    }

    /** 有效长度内的 mask 为 true。 */
    fun buildMask(length: Int): BooleanArray {
        val mask = BooleanArray(FIXED_LEN)
        for (i in 0 until min(length, FIXED_LEN)) mask[i] = true
        return mask
    }
}
