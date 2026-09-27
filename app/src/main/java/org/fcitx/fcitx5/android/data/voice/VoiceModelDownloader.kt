/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 模型下载（tar.bz2 归档整包，或按清单逐文件）。
 *
 * 与上一版（Xime）的差异：
 *  - 归档解压时剥掉首层目录（官方 release 包形如 `<模型名>/encoder-...onnx`），
 *    解压后文件落在 `filesDir/models/<id>/` 下；
 *  - 只依赖 okhttp + commons-compress（Apache-2.0），不引入第三方模型管理器。
 */
package org.fcitx.fcitx5.android.data.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

object VoiceModelDownloader {

    private const val TAG = "VoiceModelDownloader"
    private const val MAX_RETRIES = 2
    private const val BUFFER_SIZE = 64 * 1024

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * 下载并解压一个模型到 `filesDir/models/<id>/`。
     *
     * @param onProgress 进度回调（在 IO 线程调用）
     */
    suspend fun download(
        context: Context,
        model: VoiceModelInfo,
        onProgress: (VoiceModelDownloadState) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val targetDir = VoiceModelStore.modelDir(context, model.id)
        targetDir.mkdirs()
        val archiveUrl = model.archiveUrl
        try {
            if (!archiveUrl.isNullOrBlank()) {
                downloadArchive(context, archiveUrl, targetDir, onProgress)
            } else {
                downloadFiles(model.files, targetDir, onProgress)
            }
            onProgress(VoiceModelDownloadState.Complete)
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: download failed for ${model.id}")
            val message = e.message ?: "下载失败"
            onProgress(VoiceModelDownloadState.Error(message))
            Result.failure(e)
        }
    }

    private suspend fun downloadArchive(
        context: Context,
        url: String,
        targetDir: File,
        onProgress: (VoiceModelDownloadState) -> Unit,
    ) {
        val tmp = File(context.cacheDir, "voice-model-${url.hashCode()}.tar.bz2")
        var lastError: Exception? = null
        for (attempt in 1..MAX_RETRIES) {
            try {
                if (attempt > 1) {
                    tmp.delete()
                    onProgress(VoiceModelDownloadState.Downloading(0f, 0L, -1L))
                    delay(1000L * attempt)
                }
                downloadTo(url, tmp, onProgress)
                extractTarBz2(tmp, targetDir)
                tmp.delete()
                return
            } catch (e: Exception) {
                lastError = e
                Timber.w(e, "$TAG: attempt $attempt/$MAX_RETRIES failed")
            } finally {
                if (!tmp.exists()) tmp.delete()
            }
        }
        tmp.delete()
        throw lastError ?: IOException("下载失败")
    }

    private fun downloadFiles(
        files: List<VoiceModelFile>,
        targetDir: File,
        onProgress: (VoiceModelDownloadState) -> Unit,
    ) {
        if (files.isEmpty()) throw IOException("模型清单为空")
        files.forEachIndexed { index, file ->
            downloadTo(file.url, File(targetDir, file.name)) { state ->
                if (state is VoiceModelDownloadState.Downloading) {
                    val overall = (index + state.progress) / files.size
                    onProgress(state.copy(progress = overall))
                }
            }
        }
    }

    private fun downloadTo(
        url: String,
        dest: File,
        onProgress: (VoiceModelDownloadState) -> Unit,
    ) {
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        response.use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("响应为空")
            val total = body.contentLength()
            var read = 0L
            dest.parentFile?.mkdirs()
            body.byteStream().use { input ->
                FileOutputStream(dest).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        output.write(buffer, 0, n)
                        read += n
                        val progress = if (total > 0) read.toFloat() / total else 0f
                        onProgress(VoiceModelDownloadState.Downloading(progress, read, total))
                    }
                }
            }
            if (read == 0L) throw IOException("下载内容为空")
            if (total > 0 && read != total) {
                dest.delete()
                throw IOException("下载不完整：$read/$total")
            }
        }
    }

    /** 解压 tar.bz2，剥掉首层目录（官方包形如 `<模型名>/...`）。 */
    private fun extractTarBz2(archive: File, targetDir: File) {
        FileInputStream(archive).use { fis ->
            BZip2CompressorInputStream(fis).use { bz ->
                TarArchiveInputStream(bz).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        val raw = entry.name
                        val parts = raw.split("/", limit = 2)
                        val name = if (parts.size > 1) parts[1] else raw
                        if (name.isNotEmpty() && !entry.isDirectory) {
                            val out = File(targetDir, name)
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { os ->
                                val buffer = ByteArray(BUFFER_SIZE)
                                while (true) {
                                    val n = tar.read(buffer)
                                    if (n <= 0) break
                                    os.write(buffer, 0, n)
                                }
                            }
                        }
                        entry = tar.nextEntry
                    }
                }
            }
        }
    }
}
