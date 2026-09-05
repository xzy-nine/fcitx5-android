/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.sync.webdav

import github.xzynine.webdav.Authorization
import github.xzynine.webdav.WebDav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.sync.webdav.WebDavSyncConfig.Companion.CLOUD_DIR_NAME
import org.fcitx.fcitx5.android.sync.webdav.WebDavSyncConfig.Companion.DICT_FILE_NAME
import org.fcitx.fcitx5.android.sync.webdav.WebDavSyncConfig.Companion.PREFS_FILE_PREFIX
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.File
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * WebDAV 引擎：基于 github.xzynine.webdav 客户端封装测试连接/目录浏览/建目录/上传下载。
 *
 * 云端备份始终固定在服务器根目录下的 `fcitx5xzy/`（自动创建）：
 * - 偏好 zip：`fcitx5-settings_<yyyy-MM-dd>-<device>.zip`（多份，同日覆盖）
 * - 词库 zip：`fcitx5-dict.zip`（单份固定名）
 *
 * “内容未变则跳过传输”由 [WebDavSyncConfig] 中记录的指纹/远端 mtime 判定。
 */
object WebDavSyncEngine {

    /** 词库 zip 确实下载完成时返回的文案（供自动同步判断）。 */
    const val DICT_DOWNLOADED_MSG = "词库备份已下载"

    data class RemoteEntry(
        val name: String,
        val url: String,
        val isDir: Boolean,
        val size: Long,
        val lastModify: Long
    )

    private val tempDir: File by lazy { File(appContext.cacheDir, "webdav").apply { mkdirs() } }

    private fun auth(cfg: WebDavSyncConfig) = Authorization(cfg.username, cfg.password)

    private fun normalizeBase(url: String): String {
        var u = url.trim()
        if (u.isBlank()) return u
        if (!u.startsWith("http://") && !u.startsWith("https://") &&
            !u.startsWith("dav://") && !u.startsWith("davs://")
        ) {
            u = "https://$u"
        }
        return u.trimEnd('/')
    }

    private fun encodeSegment(segment: String): String =
        URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")

    private fun encodeRelativePath(relative: String): String =
        relative.split('/').filter { it.isNotBlank() }.joinToString("/") { encodeSegment(it) }

    /** 组合 base 与相对路径生成完整 URL（相对段逐个编码）。 */
    fun joinUrl(baseUrl: String, vararg relative: String): String {
        val segments = relative.filter { it.isNotBlank() }
        if (segments.isEmpty()) return normalizeBase(baseUrl)
        val suffix = segments.joinToString("/") { encodeRelativePath(it.trim('/')) }
        return "${normalizeBase(baseUrl)}/$suffix"
    }

    /** 云端备份目录 URL：服务器根目录下的 fcitx5xzy。 */
    fun cloudDirUrl(cfg: WebDavSyncConfig): String =
        joinUrl(cfg.serverUrl, CLOUD_DIR_NAME)

    private fun webDav(cfg: WebDavSyncConfig, url: String) = WebDav(url, auth(cfg))

    suspend fun testConnection(cfg: WebDavSyncConfig): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val wd = webDav(cfg, normalizeBase(cfg.serverUrl))
                val entries = wd.listFiles()
                Result.success("连接成功（可访问 ${entries.size} 个项目）")
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** 列出某远端目录（单层）。 */
    suspend fun listRemoteDir(cfg: WebDavSyncConfig, url: String): List<RemoteEntry> =
        withContext(Dispatchers.IO) {
            webDav(cfg, url).listFiles().map { f ->
                RemoteEntry(
                    name = f.displayName.ifBlank { f.urlName.trim('/').substringAfterLast('/') },
                    url = f.path,
                    isDir = f.isDir,
                    size = f.size,
                    lastModify = f.lastModify
                )
            }
        }

    /** 确保目录链存在：从 serverUrl 出发逐级 MKCOL。 */
    private suspend fun ensureDirChain(cfg: WebDavSyncConfig, fullUrl: String) {
        val base = normalizeBase(cfg.serverUrl)
        val tail = fullUrl.removePrefix(base).trim('/')
        if (tail.isEmpty()) return
        val created = mutableSetOf<String>()
        var current = base
        for (segment in tail.split('/')) {
            current += "/$segment"
            if (created.add(current)) {
                webDav(cfg, current).makeAsDir()
            }
        }
    }

    /** 上传文件到远端目录（PUT 覆盖）。 */
    suspend fun uploadFile(cfg: WebDavSyncConfig, remoteDirUrl: String, fileName: String, file: File) {
        ensureDirChain(cfg, remoteDirUrl)
        webDav(cfg, joinUrl(remoteDirUrl, fileName)).upload(file)
    }

    suspend fun remoteEntryOrNull(cfg: WebDavSyncConfig, dirUrl: String, name: String): RemoteEntry? =
        listRemoteDir(cfg, dirUrl).firstOrNull { it.name == name && !it.isDir }

    fun sanitizeFileNamePart(s: String): String =
        s.replace(Regex("[^0-9A-Za-z._-]"), "-").ifBlank { "android" }

    private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private val todayStr: String get() = LocalDate.now().format(dateFormatter)

    /** 偏好备份文件名：fcitx5-settings_<yyyy-MM-dd>-<device>.zip */
    fun prefsFileName(cfg: WebDavSyncConfig): String =
        "$PREFS_FILE_PREFIX$todayStr-${sanitizeFileNamePart(cfg.deviceName)}.zip"

    /**
     * 串行化所有读写同步状态的操作（手动与自动各一条链路），覆盖
     * “读配置 → 网络传输 → 回写配置”全过程，避免并发下的读-改-写互相覆盖。
     */
    private val syncMutex = Mutex()

    /** 以已持久化配置为准读取同步状态；读取失败时退回调用方传入的配置。 */
    private fun persistedConfig(fallback: WebDavSyncConfig): WebDavSyncConfig =
        runCatching { WebDavSyncConfig.load() }.getOrElse { fallback }

    /**
     * 重新读取已持久化配置，只合并本次操作变更的字段后原子写回，
     * 避免用调用方传入的（可能缺少同步状态的）配置整体覆盖其它字段。
     */
    private fun commitConfig(
        fallback: WebDavSyncConfig,
        update: (WebDavSyncConfig) -> WebDavSyncConfig
    ): Boolean = update(persistedConfig(fallback)).save().isSuccess

    /** 上传偏好 zip（同日同名且内容未变时跳过）。返回说明文案。 */
    suspend fun uploadPrefs(
        cfg: WebDavSyncConfig,
        onProgress: suspend (String) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            try {
                val dirUrl = cloudDirUrl(cfg)
                val name = prefsFileName(cfg)
                val zipFile = File(tempDir, name)
                onProgress("正在打包偏好设置…")
                BackupZips.buildPrefsZip(zipFile).getOrThrow()
                val digest = BackupZips.digest(zipFile)
                val stored = persistedConfig(cfg)
                if (stored.lastPrefsUploadName == name && stored.lastPrefsUploadDigest == digest) {
                    Result.success("偏好设置未发生变化，已跳过上传")
                } else {
                    onProgress("正在上传 $name …")
                    uploadFile(cfg, dirUrl, name, zipFile)
                    commitConfig(stored) {
                        it.copy(
                            lastPrefsUploadName = name,
                            lastPrefsUploadDigest = digest,
                            lastSyncDescription = "偏好已上传：$name"
                        )
                    }
                    Result.success("偏好设置已上传：$name")
                }
            } catch (e: Exception) {
                Timber.e(e, "uploadPrefs failed: ${e.javaClass.simpleName}")
                Result.failure(e)
            }
        }
    }

    /** 上传词库 zip（固定名；内容指纹未变则跳过）。返回说明文案。 */
    suspend fun uploadDict(
        cfg: WebDavSyncConfig,
        onProgress: suspend (String) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            try {
                val dirUrl = cloudDirUrl(cfg)
                val zipFile = File(tempDir, DICT_FILE_NAME)
                onProgress("正在打包词库…")
                BackupZips.buildDictZip(zipFile).getOrThrow()
                val digest = dictFingerprintOf(zipFile)
                val stored = persistedConfig(cfg)
                if (stored.dictUploadFingerprint == digest) {
                    Result.success("词库未发生变化，已跳过上传")
                } else {
                    onProgress("正在上传词库…")
                    uploadFile(cfg, dirUrl, DICT_FILE_NAME, zipFile)
                    // 记录上传后的远端 mtime，供自动下载方向“未变则跳过”使用
                    val remoteMtime = remoteEntryOrNull(cfg, dirUrl, DICT_FILE_NAME)?.lastModify ?: 0L
                    commitConfig(stored) {
                        it.copy(
                            dictUploadFingerprint = digest,
                            dictRemoteMtime = remoteMtime,
                            lastSyncDescription = "词库已上传：$todayStr"
                        )
                    }
                    Result.success("词库已上传")
                }
            } catch (e: Exception) {
                Timber.e(e, "uploadDict failed: ${e.javaClass.simpleName}")
                Result.failure(e)
            }
        }
    }

    /** 下载词库的结果：[message] 等于 [DICT_DOWNLOADED_MSG] 时才需要恢复并调用 [commitDictDownloaded]。 */
    data class DictDownloadResult(
        val message: String,
        /** 本次下载到的远端文件 mtime（未下载时为 0） */
        val remoteMtime: Long
    )

    /**
     * 下载词库 zip 到 [dest]（远端 mtime 未变则跳过）。
     *
     * 注意：此处不写 [WebDavSyncConfig.dictRemoteMtime]，只有恢复导入成功后才由
     * [commitDictDownloaded] 提交，保证失败时保留旧状态、下次自动同步能重试。
     */
    suspend fun downloadDictIfNewer(
        cfg: WebDavSyncConfig,
        dest: File,
        onProgress: suspend (String) -> Unit = {}
    ): Result<DictDownloadResult> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            try {
                val dirUrl = cloudDirUrl(cfg)
                val remote = remoteEntryOrNull(cfg, dirUrl, DICT_FILE_NAME)
                    ?: throw IllegalStateException("云端不存在词库备份：$DICT_FILE_NAME")
                val stored = persistedConfig(cfg)
                if (remote.lastModify == stored.dictRemoteMtime) {
                    Result.success(
                        DictDownloadResult("词库云端无更新，已跳过下载", remote.lastModify)
                    )
                } else {
                    onProgress("正在下载词库备份…")
                    webDav(cfg, remote.url).downloadTo(dest.absolutePath, replaceExisting = true)
                    Result.success(DictDownloadResult(DICT_DOWNLOADED_MSG, remote.lastModify))
                }
            } catch (e: Exception) {
                Timber.e(e, "downloadDictIfNewer failed: ${e.javaClass.simpleName}")
                Result.failure(e)
            }
        }
    }

    /** 词库 zip 恢复成功后提交远端 mtime，使后续自动同步跳过该文件。 */
    suspend fun commitDictDownloaded(remoteMtime: Long): Boolean =
        withContext(Dispatchers.IO) {
            if (remoteMtime <= 0L) return@withContext false
            syncMutex.withLock {
                // 读不到已持久化配置时不写盘，避免丢失连接信息
                runCatching { WebDavSyncConfig.load() }.getOrNull()
                    ?.copy(
                        dictRemoteMtime = remoteMtime,
                        lastSyncDescription = "词库已从云端下载：$todayStr"
                    )
                    ?.save()
                    ?.isSuccess == true
            }
        }

    /** 列出云端偏好 zip 列表（按名称倒序）。 */
    suspend fun listRemotePrefs(cfg: WebDavSyncConfig): List<RemoteEntry> =
        withContext(Dispatchers.IO) {
            try {
                listRemoteDir(cfg, cloudDirUrl(cfg))
                    .filter { it.name.startsWith(PREFS_FILE_PREFIX) && it.name.endsWith(".zip") }
                    .sortedByDescending { it.name }
            } catch (e: Exception) {
                Timber.e(e, "listRemotePrefs failed: ${e.javaClass.simpleName}")
                emptyList()
            }
        }

    /** 下载指定远端 zip 到本地文件（偏好 zip，供恢复）。 */
    suspend fun downloadRemoteZip(cfg: WebDavSyncConfig, entry: RemoteEntry, dest: File) {
        webDav(cfg, entry.url).downloadTo(dest.absolutePath, replaceExisting = true)
    }

    /**
     * 词库指纹：跳过 metadata.json 中的 exportTime 等易变元数据，
     * 只摘要稳定的词库内容，保证内容未变时指纹不变（内容未变则跳过上传）。
     */
    private fun dictFingerprintOf(dictZip: File): String = BackupZips.dictContentDigest(dictZip)
}
