/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 手势分类器 tag 的构造规则单测。
 *
 * 谷歌手势分类器不是独立 API，而是「文字模型 tag + `-x-gesture`」这一个**模型**；
 * tag 拼错就等于模型不存在（分类器静默不可用），故把规则钉死在单测里。
 */
package org.fcitx.fcitx5.android.data.handwriting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GoogleDigitalInkGestureTagTest {

    @Test
    fun `gesture tag appends gesture suffix`() {
        assertEquals("zh-Hani-x-gesture", GoogleDigitalInkEngine.gestureTag("zh-Hani"))
        assertEquals("en-x-gesture", GoogleDigitalInkEngine.gestureTag("en"))
        assertEquals("ja-x-gesture", GoogleDigitalInkEngine.gestureTag("ja"))
    }

    @Test
    fun `gesture tag keeps regional variants intact`() {
        // 地区变体也是官方表里独立的一档（zh-Hani-CN / zh-Hani-HK / zh-Hani-TW 各有手势模型）
        assertEquals("zh-Hani-CN-x-gesture", GoogleDigitalInkEngine.gestureTag("zh-Hani-CN"))
        assertEquals("sr-Latn-x-gesture", GoogleDigitalInkEngine.gestureTag("sr-Latn"))
    }

    @Test
    fun `gesture tag differs from text tag`() {
        // 同一语言的手势模型与文字模型是两个不同模型，tag 不能相同
        assertNotEquals(GoogleDigitalInkEngine.gestureTag("en"), "en")
    }
}