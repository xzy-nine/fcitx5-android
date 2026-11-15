/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.data.market

import org.fcitx.fcitx5.android.data.voice.VoiceModelStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MarketArchiveTest {

    @Test
    fun `archive format is decided by extension`() {
        assertEquals(MarketArchive.Format.TarBz2, MarketArchive.formatOf("a.tar.bz2"))
        assertEquals(MarketArchive.Format.TarBz2, MarketArchive.formatOf("a.tbz2"))
        assertEquals(MarketArchive.Format.TarGz, MarketArchive.formatOf("a.tar.gz"))
        assertEquals(MarketArchive.Format.TarGz, MarketArchive.formatOf("a.tgz"))
        assertEquals(MarketArchive.Format.Tar, MarketArchive.formatOf("a.tar"))
        assertEquals(MarketArchive.Format.Zip, MarketArchive.formatOf("a.zip"))
        assertEquals(MarketArchive.Format.TarBz2, MarketArchive.formatOf("A.TAR.BZ2"))
        assertNull(MarketArchive.formatOf("encoder.onnx"))
    }

    @Test
    fun `first path segment is stripped so files land in the model dir`() {
        assertEquals(
            "encoder.int8.onnx",
            MarketArchive.stripFirstSegment("model-name/encoder.int8.onnx"),
        )
        assertEquals(
            "sub/decoder.onnx",
            MarketArchive.stripFirstSegment("model-name/sub/decoder.onnx"),
        )
        // 没有目录段时原样返回；前导斜杠不构成目录段
        assertEquals("tokens.txt", MarketArchive.stripFirstSegment("tokens.txt"))
        assertEquals("tokens.txt", MarketArchive.stripFirstSegment("/tokens.txt"))
        assertEquals(
            "tokens.txt",
            MarketArchive.stripFirstSegment("model-name\\tokens.txt"),
        )
    }

    @Test
    fun `safe target keeps entries inside the destination`() {
        val dest = Files.createTempDirectory("market-archive").toFile()
        assertEquals(
            File(dest, "encoder.onnx").canonicalFile,
            MarketArchive.safeTarget(dest, "encoder.onnx").canonicalFile,
        )
        assertEquals(
            File(dest, "sub/decoder.onnx").canonicalFile,
            MarketArchive.safeTarget(dest, "sub/decoder.onnx").canonicalFile,
        )
    }

    @Test
    fun `safe target rejects entries escaping the destination`() {
        val dest = Files.createTempDirectory("market-archive").toFile()
        // tar-slip / zip-slip：条目名不可信，越界一律拒绝
        for (evil in listOf("../escape.txt", "sub/../../escape.txt")) {
            val threw = try {
                MarketArchive.safeTarget(dest, evil)
                false
            } catch (e: SecurityException) {
                true
            }
            assertTrue("expected rejection for $evil", threw)
        }
    }

    @Test
    fun `model dirs skip staging and hidden entries`() {
        val root = Files.createTempDirectory("models").toFile()
        File(root, "ready-model").mkdirs()
        // 暂存目录带探针，绝不能当成可用模型列出来
        val staged = File(root, "model.tmp-1").apply { mkdirs() }
        File(staged, MarketArchive.STAGING_MARKER).createNewFile()
        File(root, ".hidden").mkdirs()
        File(root, "not-a-dir.txt").writeText("x")

        assertEquals(listOf("ready-model"), VoiceModelStore.modelDirs(root))
    }

    @Test
    fun `model without any source is not downloadable`() {
        // 手动导入的条目只有本地目录：不能下载，否则只会建出一个空目录
        val imported = MarketModel(id = "my-model", name = "my-model")
        assertFalse(imported.isDownloadable)

        val archived = MarketModel(id = "x", name = "x", archiveUrl = "https://example/x.tar.bz2")
        assertTrue(archived.isDownloadable)

        val listed = MarketModel(
            id = "y",
            name = "y",
            files = listOf(MarketModelFile("tokens.txt", "https://example/tokens.txt")),
        )
        assertTrue(listed.isDownloadable)
    }

    @Test
    fun `archive file name is taken from the url tail`() {
        assertEquals(
            "m.tar.bz2",
            ModelDownloader.fileNameOf("https://example.com/a/b/m.tar.bz2"),
        )
        assertEquals(
            "m.tar.bz2",
            ModelDownloader.fileNameOf("https://example.com/a/b/m.tar.bz2?download=1"),
        )
        assertEquals("m.zip", ModelDownloader.fileNameOf("https://example.com/m.zip#frag"))
    }
}
