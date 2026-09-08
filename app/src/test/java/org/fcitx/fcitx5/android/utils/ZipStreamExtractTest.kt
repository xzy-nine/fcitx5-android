/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ZipStreamExtractTest {

    private fun zipOf(vararg names: String): ByteArray =
        ByteArrayOutputStream().use { bos ->
            ZipOutputStream(bos).use { zos ->
                names.forEach { name -> zos.putNextEntry(ZipEntry(name)) }
            }
            bos.toByteArray()
        }

    private fun newDest(prefix: String): File {
        val parent = File.createTempFile(prefix, "zip-parent").apply {
            delete()
            mkdirs()
        }
        val dest = File(parent, "dest").apply { mkdirs() }
        return dest
    }

    private fun assertThrowsSecurity(block: () -> Unit) {
        try {
            block()
        } catch (e: SecurityException) {
            return
        }
        throw AssertionError("expected SecurityException")
    }

    @Test
    fun extractsFilesAndCreatesMissingParents() {
        val dest = newDest("extract-ok")
        try {
            ZipInputStream(ByteArrayInputStream(zipOf("metadata.json", "shared_prefs/a.xml", "empty-dir/"))).use {
                it.extract(dest)
            }
            val top = dest.listFiles()!!.map { it.name }.sorted()
            assertEquals(listOf("empty-dir", "metadata.json", "shared_prefs"), top)
            assertTrue("文件条目应正常写出", File(dest, "shared_prefs/a.xml").isFile)
            assertTrue("无目录条目时父目录应自动创建", File(dest, "shared_prefs").isDirectory)
            assertTrue("目录条目应被创建", File(dest, "empty-dir").isDirectory)
        } finally {
            dest.parentFile!!.deleteRecursively()
        }
    }

    @Test
    fun rejectsParentTraversalEntry() {
        val dest = newDest("extract-traversal")
        try {
            assertThrowsSecurity {
                ZipInputStream(ByteArrayInputStream(zipOf("../escape.txt"))).use { it.extract(dest) }
            }
            assertFalse("越界文件不得被写入", File(dest.parentFile, "escape.txt").exists())
        } finally {
            dest.parentFile!!.deleteRecursively()
        }
    }

    @Test
    fun rejectsPrefixSiblingTraversalEntry() {
        // 经典前缀绕过：目标规范化为 sibling 目录 <dest>-evil 下时，
        // 旧的 startsWith(dest) 校验会误放行
        val dest = newDest("extract-prefix")
        try {
            assertThrowsSecurity {
                ZipInputStream(
                    ByteArrayInputStream(zipOf("../${dest.name}-evil/evil.txt"))
                ).use { it.extract(dest) }
            }
            assertFalse("同级目录越界文件不得被写入", File(dest.parentFile, "${dest.name}-evil/evil.txt").exists())
        } finally {
            dest.parentFile!!.deleteRecursively()
        }
    }

    @Test
    fun rejectsDirectoryTraversalEntry() {
        val dest = newDest("extract-dirtrav")
        try {
            assertThrowsSecurity {
                ZipInputStream(ByteArrayInputStream(zipOf("../evil/"))).use { it.extract(dest) }
            }
            assertFalse("越界目录不得被创建", File(dest.parentFile, "evil").exists())
        } finally {
            dest.parentFile!!.deleteRecursively()
        }
    }

    @Test
    fun keepsAbsoluteEntryInsideDestination() {
        val dest = newDest("extract-abs")
        try {
            // File(parent, name) 在 UNIX 语义下把以 / 开头的条目并入解压目录，而非当作根路径
            ZipInputStream(ByteArrayInputStream(zipOf("/absolute-evil.txt"))).use { it.extract(dest) }
            assertFalse("绝对路径条目不得写到文件系统根路径", File("/absolute-evil.txt").exists())
        } finally {
            dest.parentFile!!.deleteRecursively()
        }
    }
}
