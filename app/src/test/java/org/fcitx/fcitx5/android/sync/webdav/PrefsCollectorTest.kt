/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.sync.webdav

import org.fcitx.fcitx5.android.sync.webdav.PrefsCollector.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PrefsCollectorTest {

    private fun File.writeTextWith(rel: String, text: String): File {
        val f = File(this, rel)
        f.parentFile?.mkdirs()
        f.writeText(text)
        return f
    }

    private fun tempDir(): File = File.createTempFile("prefs", "test").apply {
        delete()
        mkdirs()
    }

    @Test
    fun collectIncludesSharedPrefsAndConfigWithPartitionPrefixes() {
        val root = tempDir()
        try {
            root.writeTextWith("shared_prefs/pkg_preferences.xml", "<map/>")
            root.writeTextWith("config/pinyin.conf", "[Pinyin]")
            val entries = PrefsCollector.listSyncable(
                listOf(
                    PrefsCollector.SHARED_PREFS_PARTITION to File(root, "shared_prefs"),
                    PrefsCollector.CONFIG_PARTITION to File(root, "config")
                )
            )
            val rel = entries.map { it.relativePath }.toSet()
            assertTrue("shared_prefs 文件必须带分区前缀", "shared_prefs/pkg_preferences.xml" in rel)
            assertTrue("config 文件必须带 external/config 前缀", "external/config/pinyin.conf" in rel)
            assertEquals(2, entries.size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun collectSkipsHiddenAndTempFiles() {
        val root = tempDir()
        try {
            val prefs = File(root, "shared_prefs")
            prefs.writeTextWith("keep.xml", "keep")
            prefs.writeTextWith("draft.tmp", "tmp")
            prefs.writeTextWith("lock.lock", "lock")
            prefs.writeTextWith(".hidden.xml", "hidden")
            // 隐藏目录整棵子树都应跳过
            prefs.writeTextWith(".hidden_dir/inner.xml", "inner")

            val entries = PrefsCollector.listSyncable(
                listOf(PrefsCollector.SHARED_PREFS_PARTITION to prefs)
            )

            val rel = entries.map { it.relativePath }
            assertEquals(listOf("shared_prefs/keep.xml"), rel)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingRootsYieldEmptyListAndStableFingerprint() {
        val missing = File(tempDir(), "does-not-exist")
        val entries = PrefsCollector.listSyncable(
            listOf(PrefsCollector.SHARED_PREFS_PARTITION to missing)
        )
        assertTrue("目录不存在时视为空", entries.isEmpty())
        assertTrue("空清单指纹必须非空", PrefsCollector.fingerprint(entries).isNotBlank())
        assertEquals(
            "空清单指纹必须稳定",
            PrefsCollector.fingerprint(entries),
            PrefsCollector.fingerprint(emptyList())
        )
    }

    @Test
    fun fingerprintIsStableAndOrderIndependent() {
        val a = Entry("shared_prefs/a.xml", "aaa")
        val b = Entry("external/config/b.conf", "bbb")
        val fp1 = PrefsCollector.fingerprint(listOf(a, b))
        assertEquals("顺序无关", fp1, PrefsCollector.fingerprint(listOf(b, a)))
        assertEquals("同内容稳定", fp1, PrefsCollector.fingerprint(listOf(a, b)))
    }

    @Test
    fun fingerprintChangesOnContentAndOnPath() {
        val base = PrefsCollector.fingerprint(listOf(Entry("shared_prefs/a.xml", "aaa")))
        assertNotEquals(
            "内容变化必须改变指纹",
            base,
            PrefsCollector.fingerprint(listOf(Entry("shared_prefs/a.xml", "bbb")))
        )
        assertNotEquals(
            "路径变化必须改变指纹",
            base,
            PrefsCollector.fingerprint(listOf(Entry("shared_prefs/c.xml", "aaa")))
        )
        assertNotEquals(
            "增删文件必须改变指纹",
            base,
            PrefsCollector.fingerprint(
                listOf(Entry("shared_prefs/a.xml", "aaa"), Entry("shared_prefs/b.xml", "bbb"))
            )
        )
    }

    @Test
    fun fingerprintReflectsRealFileContentChange() {
        val root = tempDir()
        try {
            val prefs = File(root, "shared_prefs")
            val roots = listOf(PrefsCollector.SHARED_PREFS_PARTITION to prefs)
            val f = prefs.writeTextWith("pkg_preferences.xml", "one")
            val before = PrefsCollector.fingerprint(PrefsCollector.listSyncable(roots))
            assertEquals(
                "同一磁盘内容重复计算结果必须一致",
                before,
                PrefsCollector.fingerprint(PrefsCollector.listSyncable(roots))
            )
            f.writeText("two")
            val after = PrefsCollector.fingerprint(PrefsCollector.listSyncable(roots))
            assertNotEquals("磁盘内容变化必须改变指纹", before, after)
            assertFalse(before.isBlank())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun volatilePrefKeysAreStrippedFromFingerprint() {
        val root = tempDir()
        try {
            val prefs = File(root, "shared_prefs")
            val roots = listOf(PrefsCollector.SHARED_PREFS_PARTITION to prefs)
            val f = prefs.writeTextWith(
                "pkg_preferences.xml",
                """
                <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
                <map>
                    <int name="pid" value="1234" />
                    <boolean name="verbose_log" value="true" />
                </map>
                """.trimIndent()
            )
            val before = PrefsCollector.fingerprint(PrefsCollector.listSyncable(roots))
            // 仅进程号变化：指纹必须保持不变，否则每次冷启动都会重传整份备份
            f.writeText(
                """
                <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
                <map>
                    <int name="pid" value="9999" />
                    <boolean name="verbose_log" value="true" />
                </map>
                """.trimIndent()
            )
            assertEquals(
                "仅易变键变化时指纹必须稳定",
                before,
                PrefsCollector.fingerprint(PrefsCollector.listSyncable(roots))
            )
            // 真实设置变化仍必须被检出
            f.writeText(
                """
                <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
                <map>
                    <int name="pid" value="9999" />
                    <boolean name="verbose_log" value="false" />
                </map>
                """.trimIndent()
            )
            assertNotEquals(
                "真实设置变化必须改变指纹",
                before,
                PrefsCollector.fingerprint(PrefsCollector.listSyncable(roots))
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun stripVolatilePrefsRemovesOnlyTagLines() {
        val xml = """
            <map>
                <int name="pid" value="42" />
                <string name="note">pid</string>
            </map>
        """.trimIndent()
        val stripped = PrefsCollector.stripVolatilePrefs(xml)
        assertFalse("pid 条目必须被移除", stripped.contains("name=\"pid\""))
        assertTrue("字符串值里的 pid 不能误伤", stripped.contains("<string name=\"note\">pid</string>"))
        assertTrue("其它条目必须保留", stripped.contains("<map>"))
    }
}
