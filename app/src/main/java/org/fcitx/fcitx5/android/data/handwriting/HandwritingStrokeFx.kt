/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 手写笔迹的纯几何/时间计算（不依赖模型与 UI）。
 *
 * 提供：自适应停顿阈值（键盘画布判断「写完一段」）、笔画包围盒与盒间距
 * （触控笔的橡皮擦相交判定、本地手势启发式）。
 */
package org.fcitx.fcitx5.android.data.handwriting

import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** 停顿分割阈值在笔画数 = 中位数处取到下限（ms），见 [HandwritingStrokeFx.splitPauseMs]。 */
const val HW_SPLIT_PAUSE_MIN_MS = 500L

/** 停顿分割阈值上限（ms），见 [HandwritingStrokeFx.splitPauseMs]。 */
const val HW_SPLIT_PAUSE_MAX_MS = 1500L

/** 常用字笔画中位数（曲线谷底所在的窗口笔画数）。 */
const val HW_SPLIT_PAUSE_MEDIAN_STROKES = 9

/** 曲线系数：窗口笔画数偏离中位数 [HW_SPLIT_PAUSE_MEDIAN_STROKES] 笔时，阈值抬升 250ms。 */
const val HW_SPLIT_PAUSE_CURVE_RISE_MS = 250.0

object HandwritingStrokeFx {

    /**
     * 停顿阈值：`min(上限, 下限 + 系数/m² × (笔画数 − m)²)`，`m` = [HW_SPLIT_PAUSE_MEDIAN_STROKES]。
     *
     * 笔画数等于 m（常用字中位数）时取到下限，偏离越远越慢；`splitPauseMs(0)` = 750ms。
     *
     * @param windowStrokes 当前识别窗口笔画数
     */
    fun splitPauseMs(windowStrokes: Int): Long {
        val median = HW_SPLIT_PAUSE_MEDIAN_STROKES.toDouble()
        val offset = (windowStrokes - HW_SPLIT_PAUSE_MEDIAN_STROKES).toDouble()
        val rise = HW_SPLIT_PAUSE_CURVE_RISE_MS / (median * median) * offset * offset
        return (HW_SPLIT_PAUSE_MIN_MS + rise)
            .coerceAtMost(HW_SPLIT_PAUSE_MAX_MS.toDouble())
            .roundToLong()
    }

    /** 笔画包围盒。 */
    data class Box(
        val minX: Float,
        val maxX: Float,
        val minY: Float,
        val maxY: Float,
    ) {
        val width: Float get() = maxX - minX
        val height: Float get() = maxY - minY

        /** 尺寸代理：宽高较大者（至少 1，避免退化笔画除零）。 */
        val size: Float get() = max(max(width, height), 1f)
    }

    /** 笔画包围盒（空笔画退化为原点）。 */
    fun boxOf(stroke: List<StrokePoint>): Box {
        if (stroke.isEmpty()) return Box(0f, 0f, 0f, 0f)
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (point in stroke) {
            if (point.x < minX) minX = point.x
            if (point.x > maxX) maxX = point.x
            if (point.y < minY) minY = point.y
            if (point.y > maxY) maxY = point.y
        }
        return Box(minX, maxX, minY, maxY)
    }

    /** 两包围盒的空间间距：两轴分离量的欧氏距离，相交/相接为 0。 */
    fun boxGap(a: Box, b: Box): Float {
        val dx = max(max(a.minX - b.maxX, b.minX - a.maxX), 0f)
        val dy = max(max(a.minY - b.maxY, b.minY - a.maxY), 0f)
        return sqrt(dx * dx + dy * dy)
    }
}
