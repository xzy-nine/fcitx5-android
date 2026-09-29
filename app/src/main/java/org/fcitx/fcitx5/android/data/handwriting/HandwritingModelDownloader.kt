/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 手写模型下载（逐文件 + sha256 校验）。
 *
 * 与语音下载器的差异：手写模型是「若干独立文件」而不是 tar.bz2 整包，因此只需要
 * 逐文件下载 + 临时文件原子改名 + 可选校验（索引里带 sha256 时）。
 */
package org.fcitx.fcitx5.android.data.handwriting

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object HandwritingModelDownloader {

    private const val TAG = "HandwritingModelDownloader"
    private const val BUFFER_SIZE = 64 * 1024
    private const val MAX_RETRIES = 2

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * 下载模型到 `filesDir/models/<id>/`。
     *
     * @param onProgress 进度回调（在 IO 线程调用）
     */
    suspend fun download(
        context: Context,
        model: HandwritingModelInfo,
        onProgress: (HandwritingModelState) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val targetDir = HandwritingModelStore.modelDir(context, model.id)
        targetDir.mkdirs()
        try {
            val totalBytes = model.files.size.toLong()
            model.files.forEachIndexed { index, file ->
                downloadOne(file, targetDir) { written, expected ->
                    val inFile = if (expected > 0) written.toFloat() / expected else 0f
                    val progress = ((index + inFile) / totalBytes).coerceIn(0f, 1f)
                    onProgress(
                        HandwritingModelState.Downloading(
                            progress = progress,
                            bytesDownloaded = index.toLong(),
                            totalBytes = totalBytes,
                        )
                    )
                }
            }
            onProgress(HandwritingModelState.Complete)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "$TAG: download failed for ${model.id}")
            val message = e.message ?: "下载失败"
            onProgress(HandwritingModelState.Error(message))
            Result.failure(e)
        }
    }

    private fun downloadOne(
        file: HandwritingModelFile,
        targetDir: File,
        onProgress: (written: Long, expected: Long) -> Unit,
    ) {
        val target = File(targetDir, file.name)
        val temp = File(targetDir, "${file.name}.download")
        var lastError: Exception? = null
        repeat(MAX_RETRIES + 1) { attempt ->
            try {
                temp.delete()
                val request = Request.Builder().url(file.url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("HTTP ${response.code}")
                    }
                    val body = response.body ?: throw IllegalStateException("响应为空")
                    val expected = body.contentLength()
                    body.byteStream().use { input ->
                        FileOutputStream(temp).use { output ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var written = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                written += read
                                onProgress(written, expected)
                            }
                        }
                    }
                }
                // sha256 校验（索引里带校验值时才做）
                if (file.sha256.isNotBlank()) {
                    val actual = sha256Of(temp)
                    if (!actual.equals(file.sha256, ignoreCase = true)) {
                        throw IllegalStateException("校验失败：${file.name}")
                    }
                }
                if (target.exists()) target.delete()
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Timber.w(e, "$TAG: attempt ${attempt + 1} failed for ${file.name}")
            }
        }
        throw lastError ?: IllegalStateException("下载失败")
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
}
