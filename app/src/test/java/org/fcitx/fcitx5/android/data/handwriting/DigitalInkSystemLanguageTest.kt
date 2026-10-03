/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.data.handwriting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DigitalInkSystemLanguageTest {

    @Test
    fun `chinese maps to the han model regardless of script`() {
        assertEquals("zh-Hani", DigitalInkSystemLanguage.baseLanguageTag("zh"))
        assertEquals("zh-Hani", DigitalInkSystemLanguage.baseLanguageTag("ZH"))
        // 繁体/简体共用 Han 模型：候选不拼脚本子标签，只加地区
        assertEquals(
            listOf("zh-Hani-CN", "zh-Hani"),
            DigitalInkSystemLanguage.candidateTags("zh", "Hans", "CN"),
        )
        assertEquals(
            listOf("zh-Hani-TW", "zh-Hani"),
            DigitalInkSystemLanguage.candidateTags("zh", "Hant", "TW"),
        )
    }

    @Test
    fun `non chinese candidates go from most to least specific`() {
        assertEquals(
            listOf("sr-Latn-RS", "sr-RS", "sr-Latn", "sr"),
            DigitalInkSystemLanguage.candidateTags("sr", "Latn", "RS"),
        )
        assertEquals(
            listOf("en-NZ", "en"),
            DigitalInkSystemLanguage.candidateTags("en", "", "NZ"),
        )
        assertEquals(
            listOf("en"),
            DigitalInkSystemLanguage.candidateTags("en", null, null),
        )
        assertEquals(emptyList<String>(), DigitalInkSystemLanguage.candidateTags("", "Latn", "RS"))
    }

    @Test
    fun `candidates are de-duplicated`() {
        val tags = DigitalInkSystemLanguage.candidateTags("zh", "Hans", null)
        assertEquals(tags.distinct(), tags)
    }

    @Test
    fun `canonical tag picks the first resolvable candidate`() {
        // ML Kit 不做回落：en-NZ 直接解析失败，必须靠候选链回落到 en
        val canonical = DigitalInkSystemLanguage.canonicalTag(listOf("en-NZ", "en")) { tag ->
            if (tag == "en") "en" else null
        }
        assertEquals("en", canonical)
        // 整条链都不支持 → null（市场不该给「直接下载」死卡片）
        assertNull(DigitalInkSystemLanguage.canonicalTag(listOf("xx", "xx-YY")) { null })
    }

    @Test
    fun `canonical tag walks the whole chain for script variants`() {
        // sr-Latn-RS / sr-RS 都不在 ML Kit 表里，最后回落到 sr
        val supported = setOf("sr")
        val candidates = DigitalInkSystemLanguage.candidateTags("sr", "Latn", "RS")
        assertEquals(
            "sr",
            DigitalInkSystemLanguage.canonicalTag(candidates) { if (it in supported) it else null },
        )
    }
}