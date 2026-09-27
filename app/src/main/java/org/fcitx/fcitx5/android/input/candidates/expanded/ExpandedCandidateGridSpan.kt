/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.expanded

import kotlin.math.ceil

/**
 * 「展开候选」表格形态的列数计算（纯函数，可单测）。
 *
 * **为什么要对齐 View 侧 [SpanHelper]**：Compose 的 `LazyVerticalGrid` 只有**整表统一的列数**
 * （`GridCells.Fixed`），没有逐项 `spanSize`，无法像 `GridLayoutManager.SpanSizeLookup` 那样
 * 「按文字宽度逐项跨列 + 末项拉伸填满整行」。若简单按「可用宽度里能摆下几条」取整表列数，
 * 短词（1–2 个汉字）会把列数顶满到偏好上限（默认 6）：一行词数是 View 实现的两倍，
 * 每帧要合成/测量的格子随之翻倍，滚动就会掉帧。
 *
 * **本函数的取法**：照搬 [SpanHelper] 的「单条候选占几个列单位」口径，再反推整表列数：
 * - 单条候选占用 `s = ceil(宽度em / 1.5)` 个列单位（[SpanHelper] 同款：1 个汉字 ≈ 1em 占 1 列，
 *   3 个西文字符 ≈ 1.5em 占 1 列）；
 * - 整表列数 = `列数上限 / 最宽候选的 s`。
 *
 * 于是最宽候选与 View 实现占同样宽的比例（例：上限 6、2 字词 `s = 2` → 3 列，View 侧同样一行 3 个），
 * 后续短词自然排满整行。过长时由 UI 层的自动缩放字号兜底，不做跨列、不做逐行填充。
 *
 * 复杂度 O(K) 且无副作用，可安全地在每帧重组里调用。
 */

/**
 * 参与「最宽候选」估算的前段采样条数。
 *
 * 只取首屏够用的条数：整个列表是分页流式的，采样更多条不会改变结论，反而会让列数随滚动抖动。
 */
const val ExpandedCandidateLeadingSampleCount = 10

/**
 * 一个「列单位」对应的候选文本宽度（单位 em），与 [SpanHelper] 的 1.5 对齐。
 */
private const val ExpandedCandidateSpanUnitEm = 1.5f

/**
 * 计算表格列数。
 *
 * @param leadingTextWidthsEm 列表前段若干条候选的**实测文本宽度**（单位 em，
 *        即像素宽度除以候选字号；含注释与其间隔，与 View 侧 `textWithComment()` 同口径）。
 *        数据未就绪时可为空，此时返回 [maxSpan]（首屏还没 item，不会看到跳变）。
 * @param maxSpan 列数上限，来自 `expandedCandidateGridSpanCount[Landscape]` 偏好。
 * @param minSpan 列数下限，默认 2：最宽候选的列数需求超过上限时，整表若退化成单列会把长词压得太小。
 * @return 列数，恒落在 `[max(1, minSpan), max(minSpan, maxSpan)]` 内。
 */
fun computeGridSpanCount(
    leadingTextWidthsEm: List<Float>,
    maxSpan: Int,
    minSpan: Int = 2,
): Int {
    // 下限至少 1 列；上限不得小于下限（偏好异常时不崩、不返回 0）
    val low = minSpan.coerceAtLeast(1)
    val high = maxSpan.coerceAtLeast(low)
    // 过滤 NaN / Infinity / 负值（字体测量异常或占位数据）
    val widestEm = leadingTextWidthsEm
        .filter { it.isFinite() && it > 0f }
        .maxOrNull()
        ?: return high
    // 最宽候选需要的列单位数：至少 1 个，超过上限时按上限处理
    val spanDemand = ceil(widestEm / ExpandedCandidateSpanUnitEm).toInt().coerceIn(1, high)
    // 整表列数 = 上限 / 最宽候选占用的列单位；再钳回 [low, high]
    return (high / spanDemand).coerceIn(low, high)
}
