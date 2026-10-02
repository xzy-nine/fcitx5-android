/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 模型下载器（分类无关）。
 *
 * 只负责「把一个 [MarketModel] 落到 `filesDir/models/<id>/`」：
 * - 有 [MarketModel.archiveUrl] → 下载归档并解压（剥掉首层目录）；
 * - 否则按 [MarketModel.files] 逐个下载（带 sha256 校验与原子改名）。
 *
 * 各分类自己的「就绪判定」仍留在各自 store，因此这里不需要知道任何分类语义。
 */
package org.fcitx.fcitx5.android.data.market

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object ModelDownloader {

    private const val TAG = "ModelDownloader"
    private const val MAX_RETRIES = 2
    private const val BUFFER_SIZE = 64 * 1024

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** 下载到 [targetDir]；进度通过 [onProgress] 上报（在 IO 线程调用）。 */
    suspend fun download(
        context: Context,
        model: MarketModel,
        targetDir: File,
        onProgress: (MarketDownloadState) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        // 全部写入临时目录，成功后才晋升为 targetDir：
        // 中途失败/取消不会留下残缺文件，避免「看起来已就绪但引擎解析不出模型文件」
        val stagingDir =
            File(targetDir.parentFile, "${targetDir.name}.tmp-${System.currentTimeMillis()}")
        val throttle = ProgressThrottle(onProgress)
        try {
            targetDir.parentFile?.mkdirs()
            stagingDir.deleteRecursively()
            stagingDir.mkdirs()
            val archiveUrl = model.archiveUrl
            if (!archiveUrl.isNullOrBlank()) {
                downloadArchive(archiveUrl, stagingDir) { throttle.emit(it) }
            } else {
                downloadFiles(model.files, stagingDir) { throttle.emit(it) }
            }
            // 晋升：旧目录先改名留作备份，新目录就位后再删备份；
            // rename 失败则恢复旧目录，绝不丢掉原本可用的模型
            val backupDir =
                File(targetDir.parentFile, "${targetDir.name}.old-${System.currentTimeMillis()}")
            val hadOld = targetDir.exists() && targetDir.renameTo(backupDir)
            if (!stagingDir.renameTo(targetDir)) {
                if (hadOld) backupDir.renameTo(targetDir)
                throw IOException("无法保存模型文件")
            }
            if (hadOld) backupDir.deleteRecursively()
            throttle.emit(MarketDownloadState.Complete, force = true)
            Result.success(Unit)
        } catch (e: CancellationException) {
            // 取消同样要清理半成品（旧目标目录在晋升前不受影响）
            stagingDir.deleteRecursively()
            throw e
        } catch (e: Exception) {
            Timber.e(e, "$TAG: download failed for ${model.id}")
            stagingDir.deleteRecursively()
            throttle.emit(MarketDownloadState.Error(e.message ?: "下载失败"), force = true)
            Result.failure(e)
        }
    }

    private suspend fun downloadArchive(
        url: String,
        targetDir: File,
        onProgress: (MarketDownloadState) -> Unit,
    ) {
        val archive = File(targetDir, "download.tar.bz2")
        try {
            downloadToFile(url, archive) { read, total ->
                onProgress(MarketDownloadState.Downloading(0f, read, total))
            }
            onProgress(MarketDownloadState.Extracting(0f))
            extractTarBz2(archive, targetDir, onProgress)
            onProgress(MarketDownloadState.Extracting(1f))
        } finally {
            archive.delete()
        }
    }

    private suspend fun downloadFiles(
        files: List<MarketModelFile>,
        targetDir: File,
        onProgress: (MarketDownloadState) -> Unit,
    ) {
        val total = files.size.toLong()
        files.forEachIndexed { index, file ->
            val target = safeTarget(targetDir, file.name)
            val temp = File(targetDir, "${file.name}.download")
            // name 含子路径（如 subdir/vocab.txt）时父目录可能还不存在：先建好再写
            temp.parentFile?.mkdirs()
            downloadToFile(file.url, temp) { read, expected ->
                val inFile = if (expected > 0) read.toFloat() / expected else 0f
                val progress = ((index + inFile) / total).coerceIn(0f, 1f)
                onProgress(MarketDownloadState.Downloading(progress, index.toLong(), total))
            }
            if (file.sha256.isNotBlank()) {
                val actual = sha256Of(temp)
                if (!actual.equals(file.sha256, ignoreCase = true)) {
                    temp.delete()
                    throw IllegalStateException("校验失败：${file.name}")
                }
            }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        }
    }

    /**
     * 归档/清单里的相对路径 → [targetDir] 内的落点，并校验解析结果**不出目标目录**。
     *
     * 远程索引与压缩包内的条目名是不可信数据：`../` 段或绝对路径可能把文件写到
     * 目标目录之外（zip-slip / tar-slip），一律拒绝。
     */
    private fun safeTarget(targetDir: File, relative: String): File {
        val candidate = File(targetDir, relative)
        val canonicalDir = targetDir.canonicalFile
        val canonical = candidate.canonicalFile
        if (canonical != canonicalDir && !canonical.startsWith(canonicalDir)) {
            throw IllegalStateException("非法路径：$relative")
        }
        return candidate
    }

    /** 下载到本地临时文件（带重试）。 */
    private suspend fun downloadToFile(
        url: String,
        target: File,
        onProgress: (read: Long, total: Long) -> Unit,
    ) {
        var lastError: Exception? = null
        repeat(MAX_RETRIES + 1) { attempt ->
            // 取消时经 invokeOnCompletion 断开 OkHttp 连接：仅靠 yield() 查标志，
            // 阻塞在 input.read(buffer) 上的 socket 读永远不会返回
            val call = client.newCall(Request.Builder().url(url).build())
            coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
            try {
                target.delete()
                call.execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                    val body = response.body ?: throw IllegalStateException("响应为空")
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        FileOutputStream(target).use { output ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var read = 0L
                            while (true) {
                                // 定期挂起检查取消；真正的中断靠上面 invokeOnCompletion 的 call.cancel()
                                yield()
                                val count = input.read(buffer)
                                if (count <= 0) break
                                output.write(buffer, 0, count)
                                read += count
                                onProgress(read, total)
                            }
                        }
                    }
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Timber.w(e, "$TAG: attempt ${attempt + 1} failed for $url")
            }
        }
        throw lastError ?: IllegalStateException("下载失败")
    }

    /**
     * 解压 tar.bz2，**剥掉首层目录**（官方 release 包形如 `<模型名>/encoder-….onnx`），
     * 使文件直接落在 [targetDir] 下，与索引里给出的清单形状一致。
     */
    private suspend fun extractTarBz2(
        archive: File,
        targetDir: File,
        onProgress: (MarketDownloadState) -> Unit,
    ) {
        val totalBytes = archive.length().coerceAtLeast(1L)
        FileInputStream(archive).use { fileInput ->
            BZip2CompressorInputStream(fileInput, true).use { bzInput ->
                TarArchiveInputStream(bzInput).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (!entry.isDirectory && !name.startsWith("__MACOSX")) {
                            val relative = stripFirstSegment(name)
                            if (relative.isNotBlank()) {
                                // 剥完首段后仍须校验落点在目标目录内（tar 条目名不可信）
                                val outFile = safeTarget(targetDir, relative)
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { output ->
                                    val buffer = ByteArray(BUFFER_SIZE)
                                    while (true) {
                                        yield()
                                        val count = tar.read(buffer)
                                        if (count <= 0) break
                                        output.write(buffer, 0, count)
                                    }
                                }
                            }
                        }
                        onProgress(
                            MarketDownloadState.Extracting(
                                (fileInput.channel.position().toFloat() / totalBytes)
                                    .coerceIn(0f, 1f)
                            )
                        )
                        entry = tar.nextEntry
                    }
                }
            }
        }
    }

    private fun stripFirstSegment(path: String): String {
        val normalized = path.replace('\\', '/').trimStart('/')
        val index = normalized.indexOf('/')
        return if (index >= 0) normalized.substring(index + 1) else normalized
    }

    /** 计算文件 sha256（十六进制小写），用于校验索引里给出的摘要。 */
    internal fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 进度节流：解压与下载都是高频回调，逐次推状态会让市场页每秒重组几十次。
     * 仅在「进度变化足够大」或「跨状态」时才上报，并保证 Complete/Error 立即上报。
     */
    private class ProgressThrottle(
        private val sink: (MarketDownloadState) -> Unit,
        private val minIntervalMs: Long = 100L,
        private val minProgressDelta: Float = 0.005f,
    ) {
        private var lastTime = 0L
        private var last: MarketDownloadState? = null

        fun emit(state: MarketDownloadState, force: Boolean = false) {
            val now = System.currentTimeMillis()
            val previous = last
            val changedEnough = when {
                previous == null -> true
                state::class != previous::class -> true
                state is MarketDownloadState.Downloading && previous is MarketDownloadState.Downloading ->
                    state.progress - previous.progress >= minProgressDelta

                state is MarketDownloadState.Extracting && previous is MarketDownloadState.Extracting ->
                    state.progress - previous.progress >= minProgressDelta

                else -> true
            }
            if (changedEnough && (force || now - lastTime >= minIntervalMs ||
                        state is MarketDownloadState.Complete)
            ) {
                last = state
                lastTime = now
                sink(state)
            }
        }
    }
}
