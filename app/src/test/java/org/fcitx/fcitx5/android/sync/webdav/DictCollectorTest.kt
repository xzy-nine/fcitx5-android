/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.sync.webdav

import org.fcitx.fcitx5.android.sync.webdav.DictCollector.DictEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DictCollectorTest {

    private fun File.writeTextWith(rel: String, text: String): File {
        val f = File(this, rel)
        f.parentFile?.mkdirs()
        f.writeText(text)
        return f
    }

    @Test
    fun collectSkipsLibimeHiddenAndTemp() {
        val dataRoot = File.createTempFile("dict", "test").apply {
            delete()
            mkdirs()
        }
        try {
            dataRoot.writeTextWith("pinyin/dictionaries/a.dict", "a")
            dataRoot.writeTextWith("pinyin/customphrase/x.txt", "x")
            dataRoot.writeTextWith("table/y.dict", "y")
            dataRoot.writeTextWith("libime/model.bin", "model")
            dataRoot.writeTextWith(".hidden/h.bin", "h")
            dataRoot.writeTextWith("pinyin/z.tmp", "tmp")

            val entries = DictCollector.listSyncable(dataRoot)

            val rel = entries.map { it.relativePath }.toSet()
            assertTrue("pinyin user dict should be collected", "data/pinyin/dictionaries/a.dict" in rel)
            assertTrue("customphrase should be collected", "data/pinyin/customphrase/x.txt" in rel)
            assertTrue("table dict should be collected", "data/table/y.dict" in rel)
            assertFalse("libime read-only models must be excluded", rel.any { it.contains("libime") })
            assertFalse("hidden dir must be excluded", rel.any { it.contains(".hidden") })
            assertFalse("tmp file must be excluded", rel.any { it.endsWith(".tmp") })
        } finally {
            dataRoot.deleteRecursively()
        }
    }

    @Test
    fun fingerprintIsStableAndSensitiveToContentChange() {
        val dataRoot = File.createTempFile("dict", "test").apply {
            delete()
            mkdirs()
        }
        try {
            val f = dataRoot.writeTextWith("pinyin/dictionaries/a.dict", "alpha")
            dataRoot.writeTextWith("table/y.dict", "beta")
            f.setLastModified(1_700_000_000_000)

            val entries1 = DictCollector.listSyncable(dataRoot)
            val fp1 = DictCollector.fingerprint(entries1)
            val fp2 = DictCollector.fingerprint(DictCollector.listSyncable(dataRoot))
            assertEquals("fingerprint must be deterministic", fp1, fp2)

            f.writeText("alpha2")
            f.setLastModified(1_700_000_000_001)
            val fp3 = DictCollector.fingerprint(DictCollector.listSyncable(dataRoot))
            assertFalse("content change must change fingerprint", fp1 == fp3)

            // 空表指纹也是稳定值
            assertTrue(DictCollector.fingerprint(emptyList()).isNotBlank())
        } finally {
            dataRoot.deleteRecursively()
        }
    }

    @Test
    fun fingerprintSortsByRelativePath() {
        val entries = listOf(
            DictEntry("data/b/1", 2L, 10L),
            DictEntry("data/a/2", 1L, 20L)
        )
        // 顺序无关
        val reversed = entries.reversed()
        assertEquals(DictCollector.fingerprint(entries), DictCollector.fingerprint(reversed))
    }
}
