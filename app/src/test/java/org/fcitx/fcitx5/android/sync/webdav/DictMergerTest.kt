/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.sync.webdav

import org.fcitx.fcitx5.android.sync.webdav.DictMerger.Strategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DictMergerTest {

    private fun tempDir(): File = File.createTempFile("merge", "test").apply {
        delete()
        mkdirs()
    }

    // ---- strategyFor：只有有可靠文本格式的词库才叠加 ----

    @Test
    fun strategyMergesOnlyTextRepresentableDicts() {
        assertEquals(Strategy.MERGE_PINYIN, DictMerger.strategyFor("pinyin/user.dict"))
        assertEquals(
            Strategy.MERGE_PINYIN,
            DictMerger.strategyFor("pinyin/dictionaries/my.dict")
        )
        assertEquals(Strategy.MERGE_TEXT, DictMerger.strategyFor("pinyin/customphrase"))
        assertEquals(
            Strategy.MERGE_TEXT,
            DictMerger.strategyFor("data/quickphrase.d/email.mb")
        )
    }

    @Test
    fun strategyOverwritesNonMergeableFiles() {
        // 只有二进制格式，无法并集
        assertEquals(Strategy.OVERWRITE, DictMerger.strategyFor("pinyin/user.history"))
        // 分段结构 + 命中计数不可比
        assertEquals(Strategy.OVERWRITE, DictMerger.strategyFor("table/wubi.main.dict"))
        assertEquals(Strategy.OVERWRITE, DictMerger.strategyFor("table/wubi.user.dict"))
        // 定义文件必须整体替换
        assertEquals(Strategy.OVERWRITE, DictMerger.strategyFor("inputmethod/wubi.conf"))
        // 未知路径保守覆盖
        assertEquals(Strategy.OVERWRITE, DictMerger.strategyFor("something/else.bin"))
    }

    // ---- mergeLines：并集语义 ----

    @Test
    fun mergeLinesIsUnionKeepingBothSides() {
        val merged = DictMerger.mergeLines(
            remote = listOf("a 1", "b 2"),
            local = listOf("c 3")
        )
        assertEquals(listOf("a 1", "b 2", "c 3"), merged)
    }

    @Test
    fun mergeLinesDeduplicatesIdenticalLines() {
        val merged = DictMerger.mergeLines(
            remote = listOf("a 1", "shared 9"),
            local = listOf("shared 9", "b 2")
        )
        assertEquals(listOf("a 1", "shared 9", "b 2"), merged)
    }

    @Test
    fun mergeLinesKeepsLocalLastSoLocalWinsOnKeyConflict() {
        // 同一拼音不同成本：本地在后 → 本地值覆盖远端
        val merged = DictMerger.mergeLines(
            remote = listOf("词 pin 1.0"),
            local = listOf("词 pin 2.0")
        )
        assertEquals(listOf("词 pin 1.0", "词 pin 2.0"), merged)
        assertEquals("本地值必须在最后（后者覆盖前者）", "词 pin 2.0", merged.last())
    }

    @Test
    fun mergeLinesIgnoresBlankLines() {
        val merged = DictMerger.mergeLines(
            remote = listOf("", "a 1", "   "),
            local = listOf("", "b 2")
        )
        assertEquals(listOf("a 1", "b 2"), merged)
    }

    @Test
    fun mergeLinesWithEmptySideYieldsOtherSide() {
        assertEquals(listOf("a 1"), DictMerger.mergeLines(emptyList(), listOf("a 1")))
        assertEquals(listOf("a 1"), DictMerger.mergeLines(listOf("a 1"), emptyList()))
        assertTrue(DictMerger.mergeLines(emptyList(), emptyList()).isEmpty())
    }

    // ---- merge：文本类实际落盘 ----

    @Test
    fun mergeTextWritesUnionToLocalFile() {
        val root = tempDir()
        try {
            val work = File(root, "work").apply { mkdirs() }
            val remote = File(root, "remote").apply { writeText("a 1\nb 2\n") }
            val local = File(root, "local").apply { writeText("c 3\n") }

            assertTrue(
                DictMerger.merge(remote, local, Strategy.MERGE_TEXT, work)
            )
            val lines = local.readLines().filter { it.isNotBlank() }
            assertEquals(listOf("a 1", "b 2", "c 3"), lines)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun mergeTextPreservesLocalOnlyEntries() {
        val root = tempDir()
        try {
            val work = File(root, "work").apply { mkdirs() }
            val remote = File(root, "remote").apply { writeText("remote_only 1\n") }
            val local = File(root, "local").apply { writeText("local_only 2\n") }

            DictMerger.merge(remote, local, Strategy.MERGE_TEXT, work)

            val text = local.readText()
            assertTrue("远端独有条目必须保留", text.contains("remote_only 1"))
            assertTrue("本地独有条目必须保留（不能丢本地数据）", text.contains("local_only 2"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun overwriteStrategyIsRejectedByMerge() {
        val root = tempDir()
        try {
            val work = File(root, "work").apply { mkdirs() }
            val remote = File(root, "remote").apply { writeText("remote\n") }
            val local = File(root, "local").apply { writeText("local\n") }

            // OVERWRITE 不走叠加路径：返回 false 让调用方走原覆盖逻辑
            assertFalse(DictMerger.merge(remote, local, Strategy.OVERWRITE, work))
            assertEquals("local\n", local.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    // ---- merge：拼音二进制走转换器 ----

    @Test
    fun mergePinyinUsesConverterAndKeepsBothSides() {
        val root = tempDir()
        try {
            val work = File(root, "work").apply { mkdirs() }
            // 假二进制：内容即文本，仅用于验证转换调用与并集落盘
            val remote = File(root, "remote.dict").apply { writeText("remote 1\n") }
            val local = File(root, "local.dict").apply { writeText("local 2\n") }
            val calls = mutableListOf<String>()
            val converter = object : DictMerger.PinyinTextConverter {
                override fun toText(binary: File, text: File) {
                    calls += "toText:${binary.name}"
                    text.writeText(binary.readText())
                }

                override fun toBinary(text: File, binary: File) {
                    calls += "toBinary:${text.name}"
                    binary.writeText(text.readText())
                }
            }

            assertTrue(
                DictMerger.merge(remote, local, Strategy.MERGE_PINYIN, work, converter)
            )
            assertTrue("必须先把远端转文本", calls.any { it == "toText:remote.dict" })
            assertTrue("必须先把本地转文本", calls.any { it == "toText:local.dict" })
            assertTrue("必须把并集转回二进制", calls.any { it.startsWith("toBinary:") })
            val out = local.readText()
            assertTrue("远端条目必须保留", out.contains("remote 1"))
            assertTrue("本地条目必须保留", out.contains("local 2"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun mergePinyinFailsWithoutTouchingLocalWhenConversionFails() {
        val root = tempDir()
        try {
            val work = File(root, "work").apply { mkdirs() }
            val remote = File(root, "remote.dict").apply { writeText("remote 1\n") }
            val local = File(root, "local.dict").apply { writeText("local 2\n") }
            val converter = object : DictMerger.PinyinTextConverter {
                override fun toText(binary: File, text: File) = Unit // 不产出文件，模拟转换失败
                override fun toBinary(text: File, binary: File) = Unit
            }

            assertFalse(
                "转换失败必须返回 false，交由调用方回滚/重试",
                DictMerger.merge(remote, local, Strategy.MERGE_PINYIN, work, converter)
            )
            assertEquals("失败时绝不能改动本地词库", "local 2\n", local.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun mergePinyinFailsWhenBinaryConversionProducesNothing() {
        val root = tempDir()
        try {
            val work = File(root, "work").apply { mkdirs() }
            val remote = File(root, "remote.dict").apply { writeText("remote 1\n") }
            val local = File(root, "local.dict").apply { writeText("local 2\n") }
            val converter = object : DictMerger.PinyinTextConverter {
                override fun toText(binary: File, text: File) {
                    text.writeText(binary.readText())
                }

                override fun toBinary(text: File, binary: File) = Unit // 不产出二进制
            }

            assertFalse(DictMerger.merge(remote, local, Strategy.MERGE_PINYIN, work, converter))
            assertEquals("失败时绝不能改动本地词库", "local 2\n", local.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun mergeLeavesNoTemporaryFilesBehindOnSuccess() {
        val root = tempDir()
        try {
            val work = File(root, "work").apply { mkdirs() }
            val remote = File(root, "remote").apply { writeText("a 1\n") }
            val local = File(root, "local").apply { writeText("b 2\n") }

            DictMerger.merge(remote, local, Strategy.MERGE_TEXT, work)

            assertTrue(
                "叠加过程不应在 workDir 留下临时文件",
                work.listFiles().orEmpty().none { it.name.startsWith("merge_") }
            )
        } finally {
            root.deleteRecursively()
        }
    }

    // ---- 引擎自持词库：不能在运行时就地叠加 ----

    @Test
    fun onlyUserDictIsEngineOwned() {
        // user.dict 由引擎读入内存并在保存时整体覆写磁盘，运行时叠加会被抹掉
        assertTrue(DictMerger.isEngineOwned("pinyin/user.dict"))
        // 其余可叠加词库不归引擎所有，可就地叠加
        assertFalse(DictMerger.isEngineOwned("pinyin/dictionaries/my.dict"))
        assertFalse(DictMerger.isEngineOwned("pinyin/customphrase"))
        assertFalse(DictMerger.isEngineOwned("data/quickphrase.d/email.mb"))
        assertFalse(DictMerger.isEngineOwned("table/wubi.main.dict"))
    }

    @Test
    fun pendingFileNameIsSingleSegment() {
        val name = DictMerger.pendingFileNameFor("pinyin/user.dict")
        assertFalse("暂存副本名不能含路径分隔符", name.contains('/'))
        assertEquals("pinyin_user.dict", name)
    }

    // ---- applyPending：引擎停机后的延后叠加 ----

    @Test
    fun applyPendingMergesAndDeletesStagedCopy() {
        val root = tempDir()
        try {
            val dataRoot = File(root, "data").apply { mkdirs() }
            val staging = File(root, "stage").apply { mkdirs() }
            val work = File(root, "work").apply { mkdirs() }
            val rel = "pinyin/user.dict"
            val local = File(dataRoot, rel).apply {
                parentFile?.mkdirs()
                writeText("local 2\n")
            }
            val staged = File(staging, "pinyin_user.dict").apply { writeText("remote 1\n") }

            val done = DictMerger.applyPending(
                pending = mapOf(rel to staged),
                dataRoot = dataRoot,
                stagingDir = staging,
                workDir = work,
                converter = textConverter()
            )

            assertEquals(setOf(rel), done)
            val out = local.readText()
            assertTrue("远端条目必须并入", out.contains("remote 1"))
            assertTrue("本地条目必须保留", out.contains("local 2"))
            assertFalse("处理成功后应删除暂存副本", staged.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun applyPendingKeepsStagedCopyWhenMergeFails() {
        val root = tempDir()
        try {
            val dataRoot = File(root, "data").apply { mkdirs() }
            val staging = File(root, "stage").apply { mkdirs() }
            val work = File(root, "work").apply { mkdirs() }
            val rel = "pinyin/user.dict"
            val local = File(dataRoot, rel).apply {
                parentFile?.mkdirs()
                writeText("local 2\n")
            }
            val staged = File(staging, "pinyin_user.dict").apply { writeText("remote 1\n") }
            val failing = object : DictMerger.PinyinTextConverter {
                override fun toText(binary: File, text: File) = Unit
                override fun toBinary(text: File, binary: File) = Unit
            }

            val done = DictMerger.applyPending(
                pending = mapOf(rel to staged),
                dataRoot = dataRoot,
                stagingDir = staging,
                workDir = work,
                converter = failing
            )

            assertTrue("失败时不应报告成功", done.isEmpty())
            assertEquals("失败时绝不能改动本地词库", "local 2\n", local.readText())
            assertTrue("失败时应保留暂存副本以便重试", staged.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun applyPendingAdoptsCloudCopyWhenLocalMissing() {
        val root = tempDir()
        try {
            val dataRoot = File(root, "data").apply { mkdirs() }
            val staging = File(root, "stage").apply { mkdirs() }
            val work = File(root, "work").apply { mkdirs() }
            val rel = "pinyin/user.dict"
            val staged = File(staging, "pinyin_user.dict").apply { writeText("remote 1\n") }

            val done = DictMerger.applyPending(
                pending = mapOf(rel to staged),
                dataRoot = dataRoot,
                stagingDir = staging,
                workDir = work,
                converter = textConverter()
            )

            assertEquals(setOf(rel), done)
            assertEquals("本地缺失时应直接采用云端副本", "remote 1\n", File(dataRoot, rel).readText())
        } finally {
            root.deleteRecursively()
        }
    }

    /** 测试用转换器：把“二进制”当文本处理，仅验证叠加流程本身。 */
    private fun textConverter() = object : DictMerger.PinyinTextConverter {
        override fun toText(binary: File, text: File) {
            text.writeText(binary.readText())
        }

        override fun toBinary(text: File, binary: File) {
            binary.writeText(text.readText())
        }
    }
}
