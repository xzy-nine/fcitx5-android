/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.handwriting

/** 手写画布上的一个采样点（坐标 + 时间戳，供字迹粗细与笔间停顿使用）。 */
data class StrokePoint(
    val x: Float,
    val y: Float,
    val timeMs: Long,
)

/** 单字识别候选。 */
data class HandwritingCandidate(
    val char: String,
    val score: Float,
)
