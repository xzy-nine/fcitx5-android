/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.data.handwriting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DigitalInkModelCatalogTest {

    @Test
    fun `tags are unique and well formed`() {
        val tags = DigitalInkModelCatalog.languages.map { it.tag }
        assertEquals("重复的语言 tag 会让市场出现两张同 id 的卡片", tags.size, tags.toSet().size)
        tags.forEach { tag ->
            assertTrue("tag 不能为空: '$tag'", tag.isNotBlank())
            assertTrue("tag 不能含空白: '$tag'", tag.none { it.isWhitespace() })
        }
    }

    @Test
    fun `names are unique enough to be shown as a card title`() {
        DigitalInkModelCatalog.languages.forEach { language ->
            assertTrue("语言名不能为空: ${language.tag}", language.name.isNotBlank())
        }
    }

    @Test
    fun `catalog covers the official language table`() {
        // 官方「Supported languages」表约 361 项；明显变少说明生成脚本漏解析了行
        assertTrue(
            "语言数过少: ${DigitalInkModelCatalog.languages.size}",
            DigitalInkModelCatalog.languages.size >= 350,
        )
    }

    @Test
    fun `bundled chinese model is part of the catalog`() {
        val bundled = DigitalInkModelCatalog.find(DigitalInkModelCatalog.BUNDLED_TAG)
        assertNotNull("随包内置的中文模型必须能在市场清单里看到", bundled)
        assertEquals("zh-Hani", DigitalInkModelCatalog.BUNDLED_TAG)
    }

    @Test
    fun `unknown tag falls back to the tag itself`() {
        assertNull(DigitalInkModelCatalog.find("not-a-language"))
        assertEquals("not-a-language", DigitalInkModelCatalog.nameOf("not-a-language"))
        assertEquals(
            DigitalInkModelCatalog.find("en")!!.name,
            DigitalInkModelCatalog.nameOf("en"),
        )
    }
}