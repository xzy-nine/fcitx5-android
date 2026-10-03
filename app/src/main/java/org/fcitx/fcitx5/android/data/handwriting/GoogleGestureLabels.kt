/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 谷歌手势分类器输出 → 标准 `HandwritingGesture` 的**纯映射**（无 Android 依赖，可单测）。
 *
 * ML Kit 的数字墨水手势分类器（模型 tag = 文本 tag + `-x-gesture`）不返回类别枚举：
 * 它把识别结果编码进 `RecognitionCandidate#getText()`，取值为手势类名字符串。标签表**就在
 * 模型的 recospec 文件里**（`scribe.<script>.<date>.tfreco.recospec.local` 的 `tf_reco` 元数据），
 * 全表共 10 个标签、27 种文字中 25 种齐全（`devanagari`/`telugu` 少 `arch:*` 与 `corner:*`，
 * 只有 7 个）：
 *
 * | 类名 | 形状 | 本项目的编辑动作 |
 * |---|---|---|
 * | `scribble` | 来回涂抹 | 删除笔迹下的文本 |
 * | `strike` | 横向划线 | **不识别**（按普通书写处理） |
 * | `circle` | 圈选闭合环 | 选中圈住的内容 |
 * | `caret:above` / `caret:below` | 尖角（∧/∨） | 进入插入模式（插入点取尖角顶点） |
 * | `arch:above` / `arch:below` | 拱形（∩/∪） | 进入插入模式（与尖角同为「插入」） |
 * | `verticalbar` | 竖线 | 添加/移除空格（画在词间即插空格、画在已有空白处则删该空白） |
 * | `corner:downleft` | 左下角形（下行后向左收的 ⏎） | 插入换行 |
 * | `writing` | 普通书写 | **不是手势**（进文字识别） |
 *
 * `strike` 与 `writing` 同属地识别，不自作主张删文本：横线常是手写的一部分（如「一」、
 * 字母中的横），误判成删除的代价远大于漏判。
 *
 * 本对象只做「字符串 → [HandwritingStrokeKind]」的判定，几何与 AOSP 手势构造留在调用侧
 * （`StylusHandwritingController`），这样映射规则可以脱离设备单测。
 *
 * **宁缺勿滥**：认不出的标签一律回落到 [HandwritingStrokeKind.Character]（按普通字符笔画处理），
 * 避免把误分类的手势标签当成编辑操作把用户写好的文本改坏。
 */
package org.fcitx.fcitx5.android.data.handwriting

object GoogleGestureLabels {

    /** 手势分类器明确表示「这是书写」的标签。 */
    const val WRITING = "writing"

    /**
     * 分类器输出标签 → 本项目的手势类别；认不出/`writing` → [HandwritingStrokeKind.Character]。
     *
     * 大小写与空白容忍（分类器输出偶见首尾空白），内部标签按小写比对。
     */
    fun classify(label: String?): HandwritingStrokeKind {
        val token = label?.trim()?.lowercase() ?: return HandwritingStrokeKind.Character
        return when (token) {
            "scribble" -> HandwritingStrokeKind.Delete
            "circle" -> HandwritingStrokeKind.Select
            "caret:above", "caret:below",
            "arch:above", "arch:below",
            -> HandwritingStrokeKind.InsertMode
            "verticalbar" -> HandwritingStrokeKind.InsertSpace
            "corner:downleft" -> HandwritingStrokeKind.Newline
            // `strike`（横线）与 `writing` 一样按普通书写处理；未知标签同样保守回落
            else -> HandwritingStrokeKind.Character
        }
    }
}