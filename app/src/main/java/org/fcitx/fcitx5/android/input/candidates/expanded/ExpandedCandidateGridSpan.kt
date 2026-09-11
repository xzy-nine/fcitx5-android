/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.expanded

import kotlin.math.floor

/**
 * 「展开候选」表格形态的列数计算（纯函数，可单测）。
 *
 * **为什么不移植 [SpanHelper]**：View 版用 `GridLayoutManager.SpanSizeLookup` 做「按文字宽度逐项跨列 +
 * 末项拉伸填满整行」。Compose 的 `LazyVerticalGrid` 只有**整表统一的列数**（`GridCells.Fixed`），
 * 没有逐项 `spanSize`。逐行自绘虽然可行，但要重写约 80 行行填充算法，收益只是少数几行的观感。
 *
 * **本函数的取法**：候选列表的特点（见用户拍板）是「只有前面几条是长候选词（词组/句子），
 * 后面都是短词」。所以用**前段候选里最宽的一条**当作格子宽度的下界，反推整表列数：
 * 长候选能整条放进格子，后续短词自然排满整行。过长时由 UI 层的自动缩放字号兜底
 * （[org.fcitx.fcitx5.android.input.keyboard.ComposeKey] 同款 `TextAutoSize.StepBased`），
 * 不做跨列、不做逐行填充。
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
 * 计算表格列数。
 *
 * @param availableWidthPx 候选区可用宽度（px，已扣除右侧键盘列与分隔线）。
 * @param leadingTextWidthsPx 列表前段若干条候选的**实测文本宽度**（px，未含格子内边距）。
 *        数据未就绪时可为空，此时返回 [maxSpan]（首屏还没 item，不会看到跳变）。
 * @param itemHorizontalPaddingPx 单个格子的左右内边距合计（px）。
 * @param minSpan 列数下限，默认 2：短候选至少两列，避免整表退化成单列。
 * @param maxSpan 列数上限，来自 `expandedCandidateGridSpanCount[Landscape]` 偏好。
 * @return 列数，恒落在 `[max(1, minSpan), max(minSpan, maxSpan)]` 内。
 */
fun computeGridSpanCount(
    availableWidthPx: Float,
    leadingTextWidthsPx: List<Float>,
    itemHorizontalPaddingPx: Float,
    minSpan: Int = 2,
    maxSpan: Int = 12,
): Int {
    // 下限至少 1 列；上限不得小于下限（偏好异常时不崩、不返回 0）
    val low = minSpan.coerceAtLeast(1)
    val high = maxSpan.coerceAtLeast(low)
    // 宽度未知/非法：退回下限，宁可窄也不返回 0
    if (!availableWidthPx.isFinite() || availableWidthPx <= 0f) return low
    val padding = if (itemHorizontalPaddingPx.isFinite()) {
        itemHorizontalPaddingPx.coerceAtLeast(0f)
    } else {
        0f
    }
    // 过滤 NaN / Infinity / 负值（字体测量异常或占位数据）
    val widest = leadingTextWidthsPx
        .filter { it.isFinite() && it > 0f }
        .maxOrNull()
        ?: return high
    val cellWidthPx = widest + padding
    if (cellWidthPx <= 0f) return high
    // 最宽的候选要能整条放进一个格子；放不下（0 列）时钳到下限
    val fits = floor(availableWidthPx / cellWidthPx).toInt()
    return fits.coerceIn(low, high)
}
