/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 手写叠写的窗口与视觉前缀计算（纯逻辑）。
 *
 * 提供笔间间隔、停顿阈值、「已完成前缀」判定与滑窗固化条件，
 * 供 `HandwritingKeyboardLayout` 维护识别窗口与渲染；不依赖模型与 UI。
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

/**
 * 识别窗口笔画上限。
 *
 * 与 [HandwritingPreprocess.FIXED_LEN] / [HandwritingPreprocess.MAX_POINTS_PER_STROKE]
 * 对齐（25×8 = 200 点）：窗口笔画数超过该值时 [HandwritingStrokeFx.isWindowOverLimit]
 * 为真，调用方把最早段固化出窗。
 */
const val HW_RECOGNIZE_WINDOW_LIMIT = 25

/**
 * 段边界计入「已完成前缀」的间隔门槛（ms），
 * 见 [HandwritingStrokeFx.settledStrokesBeforeCurrent]。
 */
const val HW_FADE_GAP_MS = 400L

/**
 * 相邻笔画包围盒间距达到「两笔尺寸均值 × 该比例」时，视为两个字之间的空间硬边界，
 * 见 [HandwritingStrokeFx.spatialBoundaries]。
 */
const val HW_SPATIAL_SPLIT_RATIO = 0.5f

object HandwritingStrokeFx {

    /**
     * 停顿分割阈值：`min(上限, 下限 + 系数/m² × (笔画数 − m)²)`，`m` = [HW_SPLIT_PAUSE_MEDIAN_STROKES]。
     *
     * 笔画数等于 m（常用字中位数）时取到下限，偏离越远越慢；`splitPauseMs(0)` = 750ms。
     *
     * @param windowStrokes 当前识别窗口（未固化）笔画数
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

    /**
     * 空间硬边界：[i] 为 true 表示第 i 笔与第 i-1 笔之间是两个字的分界
     * （盒间距 ≥ 两笔尺寸均值 × [HW_SPATIAL_SPLIT_RATIO]）。长度与 [strokes] 一致，`[0]` 恒 false。
     */
    fun spatialBoundaries(strokes: List<List<StrokePoint>>): BooleanArray {
        val boundaries = BooleanArray(strokes.size)
        for (i in 1 until strokes.size) {
            val previous = boxOf(strokes[i - 1])
            val current = boxOf(strokes[i])
            val limit = (previous.size + current.size) / 2f * HW_SPATIAL_SPLIT_RATIO
            boundaries[i] = boxGap(previous, current) >= limit
        }
        return boundaries
    }

    /** 窗口的笔间时间间隔（gaps[j] = 第 j 笔起笔与上一笔收笔的间隔，gaps[0] = 0）。 */
    fun windowGaps(window: List<List<StrokePoint>>): List<Long> =
        window.mapIndexed { idx, stroke ->
            if (idx == 0) 0L else stroke.first().timeMs - window[idx - 1].last().timeMs
        }

    /**
     * 「已完成前缀」的笔画数：从最后一段往前找第一个间隔 ≥ [HW_FADE_GAP_MS] 的段边界，
     * 返回该边界之前的笔画数；找不到这样的边界时返回 0。
     */
    fun settledStrokesBeforeCurrent(
        segments: List<HandwritingSegmenter.Segment>,
        gaps: List<Long>,
    ): Int {
        for (k in segments.size - 1 downTo 1) {
            val start = segments[k].startStroke
            if (start < gaps.size && gaps[start] >= HW_FADE_GAP_MS) {
                return start
            }
        }
        return 0
    }

    /** 窗口笔画数是否超过 [HW_RECOGNIZE_WINDOW_LIMIT]。 */
    fun isWindowOverLimit(windowStrokes: Int): Boolean =
        windowStrokes > HW_RECOGNIZE_WINDOW_LIMIT

    /**
     * 段数达到 [HandwritingSegmenter.DEFAULT_MAX_SEGMENTS] 且存在已完成前缀时为真，
     * 表示需要把该前缀固化出窗。
     */
    fun needsSettleOnSaturation(segmentCount: Int, settledStrokes: Int): Boolean =
        segmentCount >= HandwritingSegmenter.DEFAULT_MAX_SEGMENTS && settledStrokes > 0
}
