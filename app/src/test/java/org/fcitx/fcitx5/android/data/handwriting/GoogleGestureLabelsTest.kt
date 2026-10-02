/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 谷歌手势分类器输出标签 → 手势类别的映射单测。
 *
 * 标签字符串取自**模型 recospec 内的 `tf_reco` 元数据**（见 `covers every label...`）。
 * 这里把「已知标签必须映射正确」与「未知标签必须保守地当字符」两条都钉住。
 */
package org.fcitx.fcitx5.android.data.handwriting

import org.junit.Assert.assertEquals
import org.junit.Test

class GoogleGestureLabelsTest {

    @Test
    fun `scribble maps to delete and strike is not a gesture`() {
        assertEquals(HandwritingStrokeKind.Delete, GoogleGestureLabels.classify("scribble"))
        // 横线常是手写的一部分（「一」、字母中的横），不当删除处理
        assertEquals(HandwritingStrokeKind.Character, GoogleGestureLabels.classify("strike"))
    }

    @Test
    fun `circle maps to select`() {
        assertEquals(HandwritingStrokeKind.Select, GoogleGestureLabels.classify("circle"))
    }

    @Test
    fun `caret and arch both map to insert mode`() {
        for (tag in listOf("caret:above", "caret:below", "arch:above", "arch:below")) {
            assertEquals(tag, HandwritingStrokeKind.InsertMode, GoogleGestureLabels.classify(tag))
        }
    }

    @Test
    fun `verticalbar maps to insert space and corner downleft maps to newline`() {
        assertEquals(HandwritingStrokeKind.InsertSpace, GoogleGestureLabels.classify("verticalbar"))
        assertEquals(HandwritingStrokeKind.Newline, GoogleGestureLabels.classify("corner:downleft"))
    }

    /**
     * 标签表**以模型 recospec 内的 `tf_reco` 元数据为准**：全表只有下列 10 个标签。
     *
     * 本测试把「实现必须覆盖全部真实标签」钉住——模型新增标签时这里会失败，提醒补映射。
     */
    @Test
    fun `covers every label present in the classifier metadata`() {
        val realLabels = listOf(
            "arch:above", "arch:below", "caret:above", "caret:below",
            "circle", "corner:downleft", "scribble", "strike", "verticalbar", "writing",
        )
        val expected = mapOf(
            "arch:above" to HandwritingStrokeKind.InsertMode,
            "arch:below" to HandwritingStrokeKind.InsertMode,
            "caret:above" to HandwritingStrokeKind.InsertMode,
            "caret:below" to HandwritingStrokeKind.InsertMode,
            "circle" to HandwritingStrokeKind.Select,
            "corner:downleft" to HandwritingStrokeKind.Newline,
            "scribble" to HandwritingStrokeKind.Delete,
            "strike" to HandwritingStrokeKind.Character,
            "verticalbar" to HandwritingStrokeKind.InsertSpace,
            "writing" to HandwritingStrokeKind.Character,
        )
        assertEquals("label set changed in model metadata", realLabels.toSet(), expected.keys)
        for (label in realLabels) {
            assertEquals(label, expected.getValue(label), GoogleGestureLabels.classify(label))
        }
    }

    /** 模型里**不存在**的角形变体（`corner:upleft` 等）必须保守回落，不能当手势。 */
    @Test
    fun `corner variants absent from the model fall back to character`() {
        for (tag in listOf("corner:downright", "corner:upleft", "corner:upright", "corner")) {
            assertEquals(
                "label=$tag",
                HandwritingStrokeKind.Character, GoogleGestureLabels.classify(tag),
            )
        }
    }

    @Test
    fun `writing is not a gesture`() {
        assertEquals(HandwritingStrokeKind.Character, GoogleGestureLabels.classify("writing"))
    }

    @Test
    fun `unknown and malformed labels fall back to character`() {
        // 宁缺勿滥：认不出的标签一律按普通字符笔画，避免把文字误当编辑操作
        for (tag in listOf(null, "", "   ", "handwriting", "scribble2", "caret", "corner", "CIRCLE?")) {
            assertEquals(
                "label=$tag",
                HandwritingStrokeKind.Character, GoogleGestureLabels.classify(tag),
            )
        }
    }

    @Test
    fun `label matching tolerates case and surrounding whitespace`() {
        assertEquals(HandwritingStrokeKind.Delete, GoogleGestureLabels.classify("  Scribble "))
        assertEquals(HandwritingStrokeKind.Select, GoogleGestureLabels.classify("CIRCLE"))
        assertEquals(HandwritingStrokeKind.Newline, GoogleGestureLabels.classify(" Corner:DownLeft\n"))
    }
}