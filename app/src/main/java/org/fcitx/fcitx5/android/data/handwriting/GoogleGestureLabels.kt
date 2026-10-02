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
 * | `strike` | 横向划线 | 删除划掉的文本 |
 * | `circle` | 圈选闭合环 | 选中圈住的内容 |
 * | `caret:above` / `caret:below` | 尖角（∧/∨） | 进入插入模式（插入点取尖角顶点） |
 * | `arch:above` / `arch:below` | 拱形（∩/∪） | 删除空格 |
 * | `verticalbar` | 竖线 | 插入空格（画在已有空白处则删除该空白） |
 * | `corner:downleft` | 左下角形（下行后向左收的 ⏎） | 插入换行 |
 * | `writing` | 普通书写 | **不是手势**（进文字识别） |
 *
 * ⚠️ 官方只定义**形状**、不定义每种形状对应的编辑动作；上表第三列是按形状语义映射到 AOSP
 * `HandwritingGesture` 的**工程选择**（`caret` 取「尖角指向插入点」= 插入模式、`arch` 取
 * 「合起来」= 去空格、`verticalbar` 取「立一根分隔」= 插空格、`corner:downleft` 取
 * 「下行后向左」= 换行）。编辑器不支持时由回落路径兜底。
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
            // 涂抹 / 划掉：都表示「删除这段文本」
            "scribble", "strike" -> HandwritingStrokeKind.Delete

            // 圈选：选中圈住的内容
            "circle" -> HandwritingStrokeKind.Select

            // 尖角（∧/∨）：进入插入模式，在顶点处插入
            "caret:above", "caret:below" -> HandwritingStrokeKind.InsertMode

            // 拱形：删除空格（与尖角互为逆操作）
            "arch:above", "arch:below" -> HandwritingStrokeKind.RemoveSpace

            // 竖线：插入空格（画在已有空白处则删除该空白）
            "verticalbar" -> HandwritingStrokeKind.InsertSpace

            // 左下角形（`corner:downleft`）：下行后向左收笔的 ⏎ 形 → 换行
            "corner:downleft" -> HandwritingStrokeKind.Newline

            // 明确是书写（以及一切未知标签）：按普通字符笔画处理
            else -> HandwritingStrokeKind.Character
        }
    }
}