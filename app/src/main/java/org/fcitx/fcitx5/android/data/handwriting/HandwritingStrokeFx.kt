/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 手写叠写的窗口与视觉前缀计算（纯逻辑）。
 *
 * 提供笔间间隔、自适应停顿阈值、「已完成前缀」判定与滑窗固化条件，
 * 供 `HandwritingKeyboardLayout` 维护识别窗口与三段式渲染；不依赖模型与 UI。
 */
package org.fcitx.fcitx5.android.data.handwriting

/** 停顿分割阈值基准（ms），见 [HandwritingStrokeFx.splitPauseMs]。 */
const val HW_SPLIT_PAUSE_BASE_MS = 700L

/** 停顿分割阈值每多一笔的递减量（ms），见 [HandwritingStrokeFx.splitPauseMs]。 */
const val HW_SPLIT_PAUSE_STEP_MS = 50L

/** 停顿分割阈值下限（ms）。 */
const val HW_SPLIT_PAUSE_MIN_MS = 500L

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

object HandwritingStrokeFx {

    /**
     * 停顿分割阈值：`基准 − (笔画数 − 1) × 递减量`，不低于下限。
     *
     * @param windowStrokes 当前识别窗口（未固化）笔画数
     */
    fun splitPauseMs(windowStrokes: Int): Long =
        (HW_SPLIT_PAUSE_BASE_MS - (windowStrokes - 1).coerceAtLeast(0) * HW_SPLIT_PAUSE_STEP_MS)
            .coerceAtLeast(HW_SPLIT_PAUSE_MIN_MS)

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
