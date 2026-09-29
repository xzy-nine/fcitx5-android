/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 叠写（连写多字）切分 —— 在单字模型之上做「过分割 + 动态规划」。
 *
 * 思路（与 ochwpro 的 `stroke_segmenter.py` 同源，实现独立）：
 * - 枚举笔画序列的所有切分点，每段送单字模型打分；
 * - DP 求全局最优切分，段间比较用「段均分 − 每多切一段的惩罚」，
 *   避免「两个低分字之和虚高压过一个高分字」，也避免「多切一刀白赚分」；
 * - 笔间停顿是分字最强信号：明显停顿（≥[GAP_SPLIT_MS]）加分、连笔（≤[GAP_JOIN_MS]）压分；
 * - 「一个字」先验：DP 切出多段但没有**显著换字停顿**时，若整段单字识别分数够高，
 *   按单字处理 —— 专治「部位成字」（写「张」中途被切成 [弓][卜]）。
 *
 * 段推理结果按 (start,end) 缓存；笔画只允许尾部追加，故追加一笔只新增少量推理。
 */
package org.fcitx.fcitx5.android.data.handwriting

import kotlin.math.max

class HandwritingSegmenter(
    /** 活动笔画最多同时叠写的字数。 */
    private val maxSegments: Int = DEFAULT_MAX_SEGMENTS,
    /** 单段（一个字）允许的最大笔画数。 */
    private val maxStrokesPerSegment: Int = DEFAULT_MAX_STROKES_PER_SEGMENT,
    /** 单字识别函数：生产环境注入 [HandwritingEngine.predict]，测试注入假模型。 */
    private val predictFn: suspend (List<List<StrokePoint>>, Int) -> List<HandwritingCandidate>,
) {

    /** 一个切分段（对应一个字）。 */
    data class Segment(
        val startStroke: Int,
        val strokeCount: Int,
        val candidates: List<HandwritingCandidate>,
    )

    data class Result(
        val segments: List<Segment>,
        val totalScore: Float,
        val avgScore: Float,
    )

    private val segmentCache = HashMap<Long, List<HandwritingCandidate>>()

    /**
     * 对笔画序列做叠写切分识别。
     *
     * @param strokes 笔画序列（时间序；前缀不可变）
     * @param gaps 笔间时间间隔（gaps[j] = 第 j 笔起笔与上一笔收笔的间隔，gaps[0] 恒为 0）
     */
    suspend fun recognize(
        strokes: List<List<StrokePoint>>,
        gaps: List<Long> = emptyList(),
    ): Result {
        if (strokes.isEmpty()) return Result(emptyList(), 0f, 0f)

        val n = strokes.size
        val neg = Float.NEGATIVE_INFINITY
        // best[k][i]：前 i 笔切成 k 段的最大总分
        val best = Array(maxSegments + 1) { FloatArray(n + 1) { neg } }
        val back = Array(maxSegments + 1) { IntArray(n + 1) { -1 } }
        best[0][0] = 0f

        for (k in 1..maxSegments) {
            for (i in 1..n) {
                val minJ = (i - maxStrokesPerSegment).coerceAtLeast(0)
                for (j in minJ until i) {
                    val prev = best[k - 1][j]
                    if (prev == neg) continue
                    val candidates = candidatesOf(j, i, strokes)
                    if (candidates.isEmpty()) continue

                    // 时间偏置：新段起笔与上一段收笔的停顿影响切分倾向
                    val gapBias = if (j > 0 && j < gaps.size) gapBias(gaps[j]) else 0f
                    val score = prev + candidates[0].score + gapBias
                    if (best[k][i] == neg || score > best[k][i]) {
                        best[k][i] = score
                        back[k][i] = j
                    }
                }
            }
        }

        // 段间比较：段均分 − 切分惩罚
        var bestK = 0
        var bestNorm = neg
        for (k in 1..maxSegments) {
            val total = best[k][n]
            if (total == neg) continue
            val norm = total / k - SEGMENT_PENALTY * (k - 1)
            if (norm > bestNorm) {
                bestNorm = norm
                bestK = k
            }
        }
        if (bestK <= 0) return Result(emptyList(), 0f, 0f)

        // 「一个字」先验：没有显著换字停顿时，整段单字分数够高就按单字处理
        if (bestK >= 2 && !hasPauseBoundary(gaps)) {
            val merged = candidatesOf(0, n, strokes)
            val mergedScore = merged.firstOrNull()?.score ?: 0f
            if (mergedScore >= MERGED_CHAR_MIN_SCORE) {
                return Result(listOf(Segment(0, n, merged)), mergedScore, mergedScore)
            }
        }

        val segments = ArrayList<Segment>(bestK)
        var i = n
        for (k in bestK downTo 1) {
            val j = back[k][i]
            segments.add(0, Segment(j, i - j, candidatesOf(j, i, strokes)))
            i = j
        }
        val total = best[bestK][n]
        return Result(segments, total, total / bestK)
    }

    /** 笔画缓冲被裁剪或清空时调用，使段缓存失效。 */
    fun reset() = segmentCache.clear()

    /** 窗口滑窗：头部裁掉 [k] 笔后段缓存 key 平移（无需重新推理）。 */
    fun onStrokesTrimmed(k: Int) {
        if (k <= 0) return
        val shifted = HashMap<Long, List<HandwritingCandidate>>()
        for ((key, candidates) in segmentCache) {
            val start = (key / 1000L).toInt()
            val end = (key % 1000L).toInt()
            if (end > k) {
                shifted[(start - k).toLong() * 1000L + (end - k)] = candidates
            }
        }
        segmentCache.clear()
        segmentCache.putAll(shifted)
    }

    private suspend fun candidatesOf(
        j: Int,
        i: Int,
        strokes: List<List<StrokePoint>>,
    ): List<HandwritingCandidate> {
        val key = j.toLong() * 1000L + i
        segmentCache[key]?.let { return it }
        val segment = strokes.subList(j, i)
        val candidates = predictFn(segment, SEGMENT_TOP_K).take(SEGMENT_TOP_K)
        // 空结果也缓存，避免写不出字的段被反复推理
        segmentCache[key] = candidates
        return candidates
    }

    /** 切分点时间偏置：明显停顿倾向切分（加分），连笔压制切分（减分）。 */
    private fun gapBias(gapMs: Long): Float = when {
        gapMs >= GAP_SPLIT_MS -> GAP_SPLIT_BONUS
        gapMs <= GAP_JOIN_MS -> GAP_JOIN_MALUS
        else -> 0f
    }

    /**
     * 显著换字停顿检测（相对阈值）：
     * - 间隔样本少（<3）时用绝对阈值（无中位数参照）；
     * - 否则边界间隔 ≥ max(中位数×2, [GAP_PAUSE_MIN_MS]) 才算换字。
     * 慢写者字内间隔本来就高 → 判单字；快写者字间间隔相对突出 → 仍能检出。
     */
    private fun hasPauseBoundary(gaps: List<Long>): Boolean {
        val inner = gaps.drop(1)
        if (inner.isEmpty()) return false
        val threshold = if (inner.size < 3) {
            GAP_SPLIT_MS
        } else {
            val median = inner.sorted()[inner.size / 2]
            max(median * 2, GAP_PAUSE_MIN_MS)
        }
        return inner.any { it >= threshold }
    }

    companion object {
        /** 活动笔画最多同时叠写的字数。 */
        const val DEFAULT_MAX_SEGMENTS = 5
        const val DEFAULT_MAX_STROKES_PER_SEGMENT = 20

        private const val SEGMENT_PENALTY = 0.02f
        private const val SEGMENT_TOP_K = 8

        /** 笔间停顿达到此值视为「换字」切分点（加分）。 */
        const val GAP_SPLIT_MS = 500L

        /** 笔间间隔小于此值视为连笔（压制切分）。 */
        const val GAP_JOIN_MS = 150L

        private const val GAP_SPLIT_BONUS = 0.15f
        private const val GAP_JOIN_MALUS = -0.08f

        /** 合并段（全窗口单字识别）分数达此值视为「整体像一个字」。 */
        private const val MERGED_CHAR_MIN_SCORE = 0.35f

        /** 相对停顿检测的绝对下限（ms）。 */
        private const val GAP_PAUSE_MIN_MS = 250L
    }
}
