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
import org.fcitx.fcitx5.android.sync.webdav.WebDavSyncConfig.Companion.DICT_DIR_NAME
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.sync.webdav.WebDavSyncConfig.Companion.PREFS_FILE_PREFIX
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.File
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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

    private const val TAG = "WebDavSync"

    /** 取字符串资源（进度/结果提示等用户可见文案跟随系统语言）。 */
    private fun text(id: Int): String = appContext.getString(id)

    private fun text(id: Int, vararg args: Any): String = appContext.getString(id, *args)

    private val externalDir = appContext.getExternalFilesDir(null)!!

    /** 云端词库目录：fcitx5xzy/dict（逐文件同步，不再打整包 zip） */
    fun dictDirUrl(cfg: WebDavSyncConfig): String =
        joinUrl(cloudDirUrl(cfg), DICT_DIR_NAME)

    /** data/pinyin/user.dict -> pinyin/user.dict */
    private fun relativeUnderData(relativePath: String): String =
        relativePath.removePrefix("${DictCollector.DATA_DIR_NAME}/")

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
                Result.success(text(R.string.webdav_connection_ok, entries.size))
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

    /**
     * 上传文件到远端目录（PUT 覆盖）。[fileName] 允许包含子目录（如 `pinyin/user.dict`），
     * 会先确保中间目录存在。
     */
    suspend fun uploadFile(cfg: WebDavSyncConfig, remoteDirUrl: String, fileName: String, file: File) {
        val parent = fileName.substringBeforeLast('/', "")
        val dir = if (parent.isEmpty()) remoteDirUrl else joinUrl(remoteDirUrl, parent)
        ensureDirChain(cfg, dir)
        webDav(cfg, joinUrl(dir, fileName.substringAfterLast('/'))).upload(file)
    }

    suspend fun remoteEntryOrNull(cfg: WebDavSyncConfig, dirUrl: String, name: String): RemoteEntry? =
        listRemoteDir(cfg, dirUrl)
            .also { list ->
                Timber.i(
                    "$TAG remoteEntryOrNull want='$name' in $dirUrl, entries(${list.size}): " +
                        list.joinToString { "${it.name}(dir=${it.isDir},size=${it.size})" }
                )
            }
            .firstOrNull { it.name == name && !it.isDir }
            .also { Timber.i("$TAG remoteEntryOrNull matched=${it?.url}") }

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
                onProgress(text(R.string.webdav_packing_prefs))
                BackupZips.buildPrefsZip(zipFile).getOrThrow()
                val digest = BackupZips.digest(zipFile)
                val stored = persistedConfig(cfg)
                if (stored.lastPrefsUploadName == name && stored.lastPrefsUploadDigest == digest) {
                    Result.success(text(R.string.webdav_prefs_unchanged_skip))
                } else {
                    onProgress(text(R.string.webdav_uploading_file, name))
                    uploadFile(cfg, dirUrl, name, zipFile)
                    commitConfig(stored) {
                        it.copy(
                            lastPrefsUploadName = name,
                            lastPrefsUploadDigest = digest,
                            lastSyncDescription = text(R.string.webdav_last_prefs_uploaded, name)
                        )
                    }
                    Result.success(text(R.string.webdav_prefs_uploaded, name))
                }
            } catch (e: Exception) {
                Timber.e(e, "uploadPrefs failed: ${e.javaClass.simpleName}")
                Result.failure(e)
            }
        }
    }

    /**
     * 上传词库文件（逐文件 PUT 到 `fcitx5xzy/dict/<相对 data/ 的路径>`）。
     *
     * 文件级增量：本地内容摘要与已上传摘要一致、且远端快照存在时跳过该文件（不重复覆盖）。
     * 每处理一个文件都通过 [onProgress] 上报“文件名 + 进度(第几个/共几个)”，
     * 跳过未变文件时也会上报，便于界面展示“已避免的冗余上传”。
     * 上传完成后立刻回读远端 size/mtime 记入快照，避免下次把刚传上去的内容又下载回来。
     */
    suspend fun uploadDictFiles(
        cfg: WebDavSyncConfig,
        onProgress: suspend (String) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            try {
                val dirUrl = dictDirUrl(cfg)
                ensureDirChain(cfg, dirUrl)
                val stored = persistedConfig(cfg)
                val digests = stored.dictFileDigests.toMutableMap()
                val snapshot = stored.dictRemoteSnapshot.toMutableMap()
                val files = DictCollector.listSyncable(DictCollector.dataRoot())
                    .sortedBy { it.relativePath }
                val total = files.size
                var uploaded = 0
                var skipped = 0
                files.forEachIndexed { index, entry ->
                    val rel = relativeUnderData(entry.relativePath)
                    val file = File(externalDir, entry.relativePath)
                    if (!file.isFile) return@forEachIndexed
                    val digest = BackupZips.digest(file)
                    val pos = "${index + 1}/$total"
                    if (digests[rel] == digest && snapshot.containsKey(rel)) {
                        // 本地内容未变、且云端已知有相同内容：无需重复上传
                        skipped++
                        onProgress(text(R.string.webdav_skip_unchanged, rel, pos))
                        return@forEachIndexed
                    }
                    onProgress(text(R.string.webdav_uploading_dict_file, rel, pos))
                    uploadFile(cfg, dirUrl, rel, file)
                    remoteFileOrNull(cfg, dirUrl, rel)?.let {
                        snapshot[rel] = "${it.size}:${it.lastModify}"
                    }
                    digests[rel] = digest
                    uploaded++
                }
                commitConfig(stored) {
                    it.copy(
                        dictFileDigests = digests,
                        dictRemoteSnapshot = snapshot,
                        lastSyncDescription = text(R.string.webdav_last_dict_uploaded, todayStr)
                    )
                }
                val msg = when {
                    total == 0 -> text(R.string.webdav_dict_no_files)
                    uploaded == 0 -> text(R.string.webdav_dict_unchanged_skip, skipped)
                    skipped == 0 -> text(R.string.webdav_dict_uploaded_count, uploaded)
                    else -> text(R.string.webdav_dict_uploaded_partial, uploaded, skipped)
                }
                Timber.i("$TAG uploadDictFiles: $msg")
                Result.success(msg)
            } catch (e: Exception) {
                Timber.e(
                    "$TAG uploadDictFiles failed: ${e.javaClass.name}, message=${e.message}, " +
                        "trace=${e.stackTraceToString()}"
                )
                Result.failure(e)
            }
        }
    }

    /** 词库下载结果：[downloaded] 为实际更新文件数，[kinds] 供 [DictReload] 决定如何生效。 */
    data class DictDownloadOutcome(
        val downloaded: Int,
        val kinds: Set<DictReload.Kind>
    )

    /**
     * 下载词库文件（逐文件 GET，覆盖本地同名文件）。
     *
     * 避免不必要的重复覆盖（[force]=false 时）：仅当远端 size+mtime 与本地快照一致才跳过，
     * 即“远端确实没变”才不下载。不能用文件大小相等来代表内容相同（同大小完全可能内容不同，
     * 误跳会丢同步）；也不在首同步时为省一次下载而凭大小猜内容。
     * 每处理一个文件都通过 [onProgress] 上报“文件名 + 进度(第几个/共几个)”。
     * 只有全部写盘成功后才提交快照，失败时保留旧状态、下次自动同步会重试。
     */
    suspend fun downloadDictFiles(
        cfg: WebDavSyncConfig,
        force: Boolean = false,
        onProgress: suspend (String) -> Unit = {}
    ): Result<DictDownloadOutcome> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            // 需要更新的文件一律先进入暂存目录，全部下载成功后再统一搬入 data/，
            // 避免中途失败留下“半套已覆盖、半套未覆盖”的本地词库（随后被上传误用）。
            val stagingDir = File(tempDir, "download_stage").apply {
                if (exists()) deleteRecursively()
                mkdirs()
            }
            try {
                val dirUrl = dictDirUrl(cfg)
                // 云端还没有 dict/ 目录时视为“暂无词库备份”，而不是失败
                val remoteFiles = runCatching { listRemoteDictFiles(cfg, dirUrl) }
                    .onFailure {
                        Timber.w("$TAG listRemoteDictFiles failed: ${it.javaClass.name}: ${it.message}")
                    }
                    .getOrDefault(emptyList())
                val stored = persistedConfig(cfg)
                val digests = stored.dictFileDigests.toMutableMap()
                val snapshot = stored.dictRemoteSnapshot.toMutableMap()
                val kinds = mutableSetOf<DictReload.Kind>()
                val total = remoteFiles.size
                var downloaded = 0
                var skipped = 0
                // 阶段 1：把需要更新的文件全部下载到暂存目录，期间不动本地任何文件
                data class Staged(
                    val rel: String,
                    val current: String,
                    val stagedFile: File,
                    val local: File
                )
                val staged = mutableListOf<Staged>()
                remoteFiles.forEachIndexed { index, (rel, entry) ->
                    val current = "${entry.size}:${entry.lastModify}"
                    val pos = "${index + 1}/$total"
                    if (!isSafeRelUnderData(rel)) {
                        // 兜底：即便列表阶段有漏网，也绝不允许任何越界写入
                        Timber.w("$TAG skip unsafe remote rel: '$rel'")
                        skipped++
                        return@forEachIndexed
                    }
                    val local = File(externalDir, "${DictCollector.DATA_DIR_NAME}/$rel")
                    if (!force) {
                        // 远端 size+mtime 未变化：本地已是最新，跳过下载（不重复覆盖）。
                        // 不能用“本地大小 == 远端大小”来判定内容相同——相等大小完全可能
                        // 内容不同，那样会错误地跳过一次真实更新，反而丢同步。
                        if (snapshot[rel] == current) {
                            skipped++
                            onProgress(text(R.string.webdav_skip_unchanged, rel, pos))
                            return@forEachIndexed
                        }
                    }
                    val stagedFile = File(stagingDir, rel)
                    stagedFile.parentFile?.mkdirs()
                    onProgress(text(R.string.webdav_downloading_dict_file, rel, pos))
                    webDav(cfg, entry.url).downloadTo(stagedFile.absolutePath, replaceExisting = true)
                    kinds += DictReload.kindOf(rel)
                    staged += Staged(rel, current, stagedFile, local)
                    downloaded++
                }
                // 阶段 2：全部下载成功后才统一替换到最终位置
                staged.forEach { (rel, current, stagedFile, local) ->
                    local.parentFile?.mkdirs()
                    Files.move(stagedFile.toPath(), local.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    snapshot[rel] = current
                    digests[rel] = BackupZips.digest(local)
                }
                commitConfig(stored) {
                    it.copy(
                        dictFileDigests = digests,
                        dictRemoteSnapshot = snapshot,
                        lastSyncDescription = if (downloaded == 0) {
                            it.lastSyncDescription
                        } else {
                            text(R.string.webdav_last_dict_downloaded, todayStr)
                        }
                    )
                }
                Timber.i("$TAG downloadDictFiles: downloaded=$downloaded skipped=$skipped kinds=$kinds")
                Result.success(DictDownloadOutcome(downloaded, kinds))
            } catch (e: Exception) {
                Timber.e(
                    "$TAG downloadDictFiles failed: ${e.javaClass.name}, message=${e.message}, " +
                        "trace=${e.stackTraceToString()}"
                )
                Result.failure(e)
            } finally {
                // 无论成败都清理暂存文件；失败时本地词库未被改动，下次自动同步会重试
                if (stagingDir.exists()) stagingDir.deleteRecursively()
            }
        }
    }

    /** 远端单段路径名必须非空、不含路径分隔符与 `..`（防服务器返回恶意名字越界写）。 */
    private fun isSafePathSegment(segment: String): Boolean =
        segment.isNotBlank() && segment != "." && segment != ".." &&
            !segment.contains('/') && !segment.contains('\\')

    /** 相对 data/ 的 rel 必须逐段合法；下载落盘前由此兜底拦截。 */
    private fun isSafeRelUnderData(rel: String): Boolean =
        rel.isNotBlank() && rel.split('/').all { isSafePathSegment(it) }

    /** 递归列出 dict 目录下的所有文件，返回“相对 data/ 的路径 -> 远端条目”。 */
    private suspend fun listRemoteDictFiles(
        cfg: WebDavSyncConfig,
        dirUrl: String,
        prefix: String = ""
    ): List<Pair<String, RemoteEntry>> {
        val result = mutableListOf<Pair<String, RemoteEntry>>()
        listRemoteDir(cfg, dirUrl).forEach { entry ->
            if (!isSafePathSegment(entry.name)) {
                Timber.w("$TAG skip unsafe remote entry name: '${entry.name}'")
                return@forEach
            }
            val rel = if (prefix.isEmpty()) entry.name else "$prefix/${entry.name}"
            if (entry.isDir) result += listRemoteDictFiles(cfg, entry.url, rel)
            else result += rel to entry
        }
        return result
    }

    /** 按多级相对路径查找远端文件：先定位父目录，再按末段名字匹配。 */
    private suspend fun remoteFileOrNull(
        cfg: WebDavSyncConfig,
        dirUrl: String,
        relativePath: String
    ): RemoteEntry? {
        val parent = relativePath.substringBeforeLast('/', "")
        val name = relativePath.substringAfterLast('/')
        val parentUrl = if (parent.isEmpty()) dirUrl else joinUrl(dirUrl, parent)
        return remoteEntryOrNull(cfg, parentUrl, name)
    }

    /** 列出云端偏好 zip 列表（按名称倒序）。 */
    suspend fun listRemotePrefs(cfg: WebDavSyncConfig): List<RemoteEntry> =
        withContext(Dispatchers.IO) {
            val dirUrl = cloudDirUrl(cfg)
            try {
                val all = listRemoteDir(cfg, dirUrl)
                Timber.i(
                    "$TAG listRemotePrefs dir=$dirUrl entries(${
                        all.size
                    }): ${all.joinToString { "${it.name}(dir=${it.isDir},size=${it.size})" }}"
                )
                all.filter { it.name.startsWith(PREFS_FILE_PREFIX) && it.name.endsWith(".zip") }
                    .sortedByDescending { it.name }
                    .also { Timber.i("$TAG listRemotePrefs matched ${it.size}") }
            } catch (e: Exception) {
                Timber.e(
                    "$TAG listRemotePrefs failed: ${e.javaClass.name}, message=${e.message}, " +
                        "trace=${e.stackTraceToString()}"
                )
                emptyList()
            }
        }

    /**
     * 下载指定远端 zip 到本地文件（偏好 zip，供恢复）。
     *
     * 必须切到 [Dispatchers.IO]：底层 OkHttp 使用同步 `execute()`，
     * 在调用方（Compose 主线程）直接执行会抛 NetworkOnMainThreadException。
     */
    suspend fun downloadRemoteZip(cfg: WebDavSyncConfig, entry: RemoteEntry, dest: File) =
        withContext(Dispatchers.IO) {
            Timber.i(
                "$TAG downloadRemoteZip: name=${entry.name}, url=${entry.url}, " +
                    "remoteSize=${entry.size} -> ${dest.absolutePath}"
            )
            dest.parentFile?.mkdirs()
            val start = System.currentTimeMillis()
            try {
                webDav(cfg, entry.url).downloadTo(dest.absolutePath, replaceExisting = true)
                Timber.i(
                    "$TAG downloadRemoteZip done in ${System.currentTimeMillis() - start}ms: " +
                        "exists=${dest.exists()}, size=${dest.length()}"
                )
            } catch (e: Exception) {
                Timber.e(
                    "$TAG downloadRemoteZip failed: ${e.javaClass.name}, message=${e.message}, " +
                        "trace=${e.stackTraceToString()}"
                )
                throw e
            }
        }

}
