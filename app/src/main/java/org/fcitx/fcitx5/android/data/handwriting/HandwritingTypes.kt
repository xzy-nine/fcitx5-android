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

/**
 * 单笔手势类别（[Character] = 普通字符笔画）。
 *
 * 由谷歌手势分类器（`-x-gesture` 模型，见 `GoogleGestureLabels`）或系统内置引擎判定；
 * 判定结果用于构造标准 AOSP `HandwritingGesture` 交给编辑器执行
 * （`InputConnection#performHandwritingGesture`，坐标一律屏幕坐标）。
 */
enum class HandwritingStrokeKind {
    /** 普通字符笔画：进识别窗口。 */
    Character,

    /** 涂改（`scribble`）→ 删除笔迹下的文本。 */
    Delete,

    /** 圈选（`circle`）→ 选中圈住的内容。 */
    Select,

    /** 换行（`corner:downleft`，下行后向左收的 ⏎ 形）→ 插入换行。 */
    Newline,

    /** 插入（`caret:*` 尖角 ∧/∨ 与 `arch:*` 拱形 ∩/∪）→ 进入插入模式。 */
    InsertMode,

    /** 竖线（`verticalbar`）→ 添加/移除空格（画在已有空白处则删除该空白）。 */
    InsertSpace,
}
