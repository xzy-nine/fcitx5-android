/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 模型下载器（分类无关）。
 *
 * 只负责「把一个 [MarketModel] 落到 `filesDir/models/<id>/`」：
 * - 有 [MarketModel.archiveUrl] → 下载归档并解压（剥掉首层目录，格式见 [MarketArchive]）；
 * - 否则按 [MarketModel.files] 逐个下载（带 sha256 校验与原子改名）。
 *
 * 各分类自己的「就绪判定」仍留在各自 store，因此这里不需要知道任何分类语义。
 */
package org.fcitx.fcitx5.android.data.market

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
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
            // 探针：市场页扫描模型根目录时据此排除尚未晋升的暂存目录
            File(stagingDir, MarketArchive.STAGING_MARKER).createNewFile()
            val archiveUrl = model.archiveUrl
            if (!archiveUrl.isNullOrBlank()) {
                downloadArchive(archiveUrl, stagingDir) { throttle.emit(it) }
            } else {
                downloadFiles(model.files, stagingDir) { throttle.emit(it) }
            }
            // 晋升：旧目录先改名留作备份，新目录就位后再删备份；
            // rename 失败则恢复旧目录，绝不丢掉原本可用的模型
            MarketArchive.promote(stagingDir, targetDir)
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
        val fileName = fileNameOf(url)
        // 归档格式由地址决定；不认识的扩展名直接失败，避免下载完才发现解不开
        val format = MarketArchive.formatOf(fileName)
            ?: throw IllegalStateException("不支持的归档格式：$fileName")
        val archive = File(targetDir, "download.$fileName")
        // 解压是长耗时 CPU 操作：把当前协程的 Job 传进去，让解压循环能检查取消
        val coroutineCtx = coroutineContext
        try {
            downloadToFile(url, archive) { read, total ->
                onProgress(MarketDownloadState.Downloading(0f, read, total))
            }
            onProgress(MarketDownloadState.Extracting(0f))
            FileInputStream(archive).use { input ->
                MarketArchive.extract(
                    input = input,
                    format = format,
                    targetDir = targetDir,
                    totalBytes = archive.length(),
                    checkActive = { coroutineCtx.ensureActive() },
                    onProgress = { onProgress(MarketDownloadState.Extracting(it)) },
                )
            }
        } finally {
            archive.delete()
        }
    }

    /** 取 URL 最后一段作为归档文件名（去掉查询串）。 */
    internal fun fileNameOf(url: String): String =
        url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')

    private suspend fun downloadFiles(
        files: List<MarketModelFile>,
        targetDir: File,
        onProgress: (MarketDownloadState) -> Unit,
    ) {
        val total = files.size.toLong()
        files.forEachIndexed { index, file ->
            val target = MarketArchive.safeTarget(targetDir, file.name)
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

    /** 下载到本地临时文件（带重试）。 */
    private suspend fun downloadToFile(
        url: String,
        target: File,
        onProgress: (read: Long, total: Long) -> Unit,
    ) {
        var lastError: Exception? = null
        repeat(MAX_RETRIES + 1) { attempt ->
            // 上一轮若是因取消而中断（call.cancel() 抛 IOException），不得再发起新请求
            coroutineContext.ensureActive()
            val call = client.newCall(Request.Builder().url(url).build())
            // 每次尝试挂一个子 Job：父协程被取消时子 Job 当场完成，下面的回调随即触发并断开
            // OkHttp —— 挂在父协程 Job 上的回调要等协程体真正退出才触发，那时读循环早已跑完。
            // 尝试结束（成功返回或转下一次重试）即 complete 掉子 Job，回调随之注销不留残余。
            val attemptJob = Job(coroutineContext[Job])
            attemptJob.invokeOnCompletion { call.cancel() }
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
                                // 定期挂起检查取消；真正的中断靠上面取消回调里的 call.cancel()
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
            } finally {
                // 本次尝试结束：子 Job 一并完成，取消回调随之注销
                attemptJob.complete()
            }
        }
        throw lastError ?: IllegalStateException("下载失败")
    }

    /** 计算文件 sha256（十六进制小写），用于校验清单里给出的摘要。 */
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
