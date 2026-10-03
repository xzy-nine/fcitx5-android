/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.data.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelIndexParseTest {

    private val yaml = """
        models:
          - id: en
            name: English
            category: asr
            versions:
              - version: v1
                size: 6.7 MB
                files:
                  - name: encoder.onnx
                    url: https://x/en/encoder.onnx
                    sha256: abc123
          - id: ja
            name: Japanese
            category: asr
          - id: bad/id
            category: asr
          - id: zh
            category: digitalink
    """.trimIndent()

    @Test
    fun `only matching category entries are kept`() {
        val asr = ModelIndex.parse(yaml, "asr")
        assertEquals(listOf("en", "ja"), asr.map { it.id })
    }

    @Test
    fun `illegal ids are skipped`() {
        val parsed = ModelIndex.parse(yaml, "asr")
        assertTrue(parsed.none { it.id == "bad/id" })
    }

    @Test
    fun `files with sha256 are extracted`() {
        val en = ModelIndex.parse(yaml, "asr").single { it.id == "en" }
        assertEquals("English", en.name)
        assertEquals("v1", en.version)
        assertEquals(1, en.files.size)
        assertEquals("abc123", en.files.first().sha256)
    }

    @Test
    fun `missing versions block yields empty version and files`() {
        val ja = ModelIndex.parse(yaml, "asr").single { it.id == "ja" }
        assertEquals("Japanese", ja.name)
        assertEquals("", ja.version)
        assertTrue(ja.files.isEmpty())
    }
}