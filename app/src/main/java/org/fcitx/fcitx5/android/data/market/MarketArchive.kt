/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 模型归档解压（下载解压与手动导入共用）。
 *
 * 支持的容器：`.tar.bz2` / `.tar.gz` / `.tgz` / `.tar` / `.zip`。
 * 官方 release 包形如 `<模型名>/encoder.onnx`，故统一**剥掉首层目录**，
 * 使文件直接落在目标目录下（与 [VoiceModelStore] 的解析口径一致）。
 *
 * 安全约定：归档条目名是不可信数据。剥首层后的相对路径必须规范化在目标目录之内，
 * 否则抛 [SecurityException]（tar-slip / zip-slip）。
 */
package org.fcitx.fcitx5.android.data.market

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

object MarketArchive {

    private const val BUFFER_SIZE = 64 * 1024

    /** 归档格式。 */
    enum class Format { TarBz2, TarGz, Tar, Zip }

    /** 按文件名判断格式；不认识的扩展名返回 null。 */
    fun formatOf(name: String): Format? {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") -> Format.TarBz2
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> Format.TarGz
            lower.endsWith(".tar") -> Format.Tar
            lower.endsWith(".zip") -> Format.Zip
            else -> null
        }
    }

    /**
     * 把 [input] 解压到 [targetDir]（剥首层目录）。
     *
     * @param totalBytes 归档总字节数（0 = 未知，此时进度恒为 0）
     * @param checkActive 每个数据块调用一次，供调用方在解压途中检查协程取消
     * @param onProgress 已解压比例回调（与调用方同线程）
     */
    fun extract(
        input: InputStream,
        format: Format,
        targetDir: File,
        totalBytes: Long = 0L,
        checkActive: () -> Unit = {},
        onProgress: (Float) -> Unit = {},
    ) {
        targetDir.mkdirs()
        val counting = CountingInputStream(input)
        val context = ExtractContext(targetDir, totalBytes, counting, checkActive, onProgress)
        when (format) {
            Format.TarBz2 -> BZip2CompressorInputStream(counting, true).use { tar(it, context) }
            Format.TarGz -> GzipCompressorInputStream.builder()
                .setInputStream(counting)
                .setDecompressConcatenated(true)
                .get()
                .use { tar(it, context) }
            Format.Tar -> tar(counting, context)
            Format.Zip -> ZipInputStream(BufferedInputStream(counting)).use { zip(it, context) }
        }
        onProgress(1f)
    }

    /**
     * 从任意输入流安装一个归档到 [targetDir]：先解压到同级暂存目录，成功后才晋升。
     *
     * 中途失败/取消不会留下残缺文件，也不会破坏原本可用的模型目录
     * （旧目录先改名备份，新目录就位后再删；rename 失败则恢复旧目录）。
     */
    fun installFromStream(
        input: InputStream,
        format: Format,
        targetDir: File,
        totalBytes: Long = 0L,
        checkActive: () -> Unit = {},
        onProgress: (Float) -> Unit = {},
    ) {
        val parent = parentOf(targetDir)
        val stagingDir = File(parent, "${targetDir.name}.tmp-${System.currentTimeMillis()}")
        try {
            parent.mkdirs()
            stagingDir.deleteRecursively()
            stagingDir.mkdirs()
            // 探针：市场页扫描模型根目录时据此排除尚未晋升的暂存目录
            File(stagingDir, STAGING_MARKER).createNewFile()
            extract(input, format, stagingDir, totalBytes, checkActive, onProgress)
            promote(stagingDir, targetDir)
        } catch (e: Throwable) {
            stagingDir.deleteRecursively()
            throw e
        }
    }

    /** 暂存目录里的探针文件名：市场页扫描模型根目录时据此排除尚未晋升的目录。 */
    const val STAGING_MARKER = ".staging"

    /**
     * 把 [stagingDir] 晋升为 [targetDir]：旧目录先改名备份，新目录就位后再删备份；
     * rename 失败则恢复旧目录，绝不丢掉原本可用的模型。
     */
    fun promote(stagingDir: File, targetDir: File) {
        val parent = parentOf(targetDir)
        val backupDir = File(parent, "${targetDir.name}.old-${System.currentTimeMillis()}")
        val hadOld = targetDir.exists() && targetDir.renameTo(backupDir)
        if (!stagingDir.renameTo(targetDir)) {
            if (hadOld) backupDir.renameTo(targetDir)
            throw IOException("无法保存模型文件")
        }
        if (hadOld) backupDir.deleteRecursively()
        // 晋升完成才摘掉探针：在此之前目录形态一律不算「可用的模型」
        File(targetDir, STAGING_MARKER).delete()
    }

    private fun parentOf(targetDir: File): File =
        targetDir.parentFile ?: throw IOException("目标目录没有父目录：$targetDir")

    /** 一次解压的共享上下文，避免每个解压函数都拖一长串参数。 */
    private class ExtractContext(
        val targetDir: File,
        val totalBytes: Long,
        val counting: CountingInputStream,
        val checkActive: () -> Unit,
        val onProgress: (Float) -> Unit,
    )

    private fun tar(decompressed: InputStream, ctx: ExtractContext) {
        TarArchiveInputStream(decompressed).use { tar ->
            var entry: TarArchiveEntry? = tar.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory && !name.startsWith("__MACOSX")) {
                    val relative = stripFirstSegment(name)
                    if (relative.isNotBlank()) {
                        val outFile = safeTarget(ctx.targetDir, relative)
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { output -> copy(tar, output, ctx) }
                    }
                }
                entry = tar.nextEntry
            }
        }
    }

    private fun zip(zip: ZipInputStream, ctx: ExtractContext) {
        var entry = zip.nextEntry
        while (entry != null) {
            val name = entry.name
            if (!name.startsWith("__MACOSX")) {
                val relative = stripFirstSegment(name)
                if (relative.isNotBlank()) {
                    val outFile = safeTarget(ctx.targetDir, relative)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { output -> copy(zip, output, ctx) }
                    }
                }
            }
            entry = zip.nextEntry
        }
    }

    private fun copy(input: InputStream, output: FileOutputStream, ctx: ExtractContext) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            // bzip2 解压是长耗时纯 CPU 操作：每个数据块给调用方一次取消检查的机会
            ctx.checkActive()
            val count = input.read(buffer)
            if (count <= 0) break
            output.write(buffer, 0, count)
            if (ctx.totalBytes > 0) {
                ctx.onProgress((ctx.counting.read.toFloat() / ctx.totalBytes).coerceIn(0f, 1f))
            }
        }
    }

    fun stripFirstSegment(path: String): String {
        val normalized = path.replace('\\', '/').trimStart('/')
        val index = normalized.indexOf('/')
        return if (index >= 0) normalized.substring(index + 1) else normalized
    }

    /**
     * 归档里的相对路径 → [targetDir] 内的落点，并校验解析结果**不出目标目录**。
     *
     * 条目名是不可信数据：`../` 段或绝对路径可能把文件写到目标目录之外，一律拒绝。
     */
    fun safeTarget(targetDir: File, relative: String): File {
        val candidate = File(targetDir, relative)
        val canonicalDir = targetDir.canonicalFile
        val canonical = candidate.canonicalFile
        if (canonical != canonicalDir && !canonical.startsWith(canonicalDir)) {
            throw SecurityException("归档条目越出目标目录：$relative")
        }
        return candidate
    }

    /** 统计已读字节，用于按归档总大小换算进度（bzip2 解压耗时长，需要有反馈）。 */
    private class CountingInputStream(private val delegate: InputStream) : InputStream() {

        var read: Long = 0L
            private set

        override fun read(): Int {
            val b = delegate.read()
            if (b >= 0) read += 1
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val count = delegate.read(b, off, len)
            if (count > 0) read += count
            return count
        }

        override fun skip(n: Long): Long = delegate.skip(n).also { read += it }

        override fun available(): Int = delegate.available()

        override fun close() {
            delegate.close()
        }
    }

    /** 目录总字节数（用于展示导入模型体积；失败返回 0）。 */
    fun sizeOf(dir: File): Long = runCatching {
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    /** 人类可读体积（一位小数）。 */
    fun readableSize(bytes: Long): String = when {
        bytes <= 0L -> ""
        bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
        else -> "%.1f MB".format(bytes / 1024.0 / 1024)
    }

    /** 供导入失败时给出一句人话（[IOException] 的 message 可能为空）。 */
    fun messageOf(e: Throwable): String =
        e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName
}
