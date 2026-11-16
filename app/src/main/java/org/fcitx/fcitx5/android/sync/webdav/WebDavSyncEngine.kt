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
import java.io.IOException
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

    // 让引擎导出或合并用户数据同步快照（sync/<installation_id>/*.userdb.txt）。
    private suspend fun exportUserDataSnapshots() {
        val connection = org.fcitx.fcitx5.android.daemon.FcitxDaemon.getFirstConnectionOrNull() ?: return
        try {
            // 引擎把用户数据同步命令挂在 addon 子配置的 sync 入口上。
            connection.runOnReady<Unit> {
                setAddonSubConfig("rime", "sync", org.fcitx.fcitx5.android.core.RawConfig())
            }
        } catch (e: Exception) {
            Timber.w(e, "$TAG 用户数据同步失败")
        }
    }

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

    private fun webDav(cfg: WebDavSyncConfig, url: String): WebDav {
        // 所有远端访问的唯一入口：Android 17 起局域网需先取得「本地网络」权限，
        // 缺失时在此快速失败并给出可操作文案（界面入口另有权限门主动申请）
        LocalNetworkGuard.require(url)
        return WebDav(url, auth(cfg))
    }

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
     * 自动设置备份文件名：`fcitx5-settings_auto-<device>.zip`（固定名，同日/跨日都覆盖同一份）。
     *
     * 仍以 [PREFS_FILE_PREFIX] 开头，因此会出现在手动恢复列表里可被手动恢复；
     * 设备名进文件名使各设备的备份天然互不冲突。
     */
    fun settingsFileName(cfg: WebDavSyncConfig): String =
        "${PREFS_FILE_PREFIX}auto-${sanitizeFileNamePart(cfg.deviceName)}.zip"

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
     * 上传“仅设置”备份（自动备份链路，单向上传，绝不下载/恢复）。
     *
     * 内容为 shared_prefs + external/config（不含 databases），文件名固定为
     * [settingsFileName]（按设备名区分，同名覆盖）。以内容指纹判断是否变化：
     * 指纹一致且上次上传的就是该文件名时跳过，避免重复传输。
     *
     * 不能用 zip 字节摘要判重：zip 内含导出时间戳，每次打包都不同。
     */
    suspend fun uploadSettingsBackup(
        cfg: WebDavSyncConfig,
        onProgress: suspend (String) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            try {
                val dirUrl = cloudDirUrl(cfg)
                val name = settingsFileName(cfg)
                val fingerprint = PrefsCollector.currentFingerprint()
                val stored = persistedConfig(cfg)
                if (stored.lastSettingsUploadName == name &&
                    stored.lastSettingsFingerprint == fingerprint
                ) {
                    Result.success(text(R.string.webdav_settings_unchanged_skip))
                } else {
                    val zipFile = File(tempDir, name)
                    onProgress(text(R.string.webdav_packing_settings))
                    BackupZips.buildSettingsZip(zipFile).getOrThrow()
                    onProgress(text(R.string.webdav_uploading_file, name))
                    uploadFile(cfg, dirUrl, name, zipFile)
                    commitConfig(stored) {
                        it.copy(
                            lastSettingsUploadName = name,
                            lastSettingsFingerprint = fingerprint,
                            lastSyncDescription = text(R.string.webdav_last_settings_uploaded, name)
                        )
                    }
                    Result.success(text(R.string.webdav_settings_uploaded, name))
                }
            } catch (e: Exception) {
                Timber.e(e, "$TAG uploadSettingsBackup failed: ${e.javaClass.simpleName}")
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
                // 让引擎把本机用户词库导出为同步快照，使本轮上传包含最新词条
                exportUserDataSnapshots()
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

    /**
     * 词库下载结果。
     *
     * [downloaded] 为实际更新文件数；[kinds] 供 [DictReload] 决定如何生效；
     * [mergedRels] 为本轮做过「组合叠加」的文件（相对 data/ 的路径）——其本地内容
     * 已比云端多出条目，调用方应随后上传以把并集推回云端；
     * [deferredRels] 为本轮**刻意跳过**、需等引擎停机后再叠加的文件（见
     * [DictReload.Kind.USER_DICT]）：引擎在保存时用内存内容覆写这些文件，
     * 运行中叠加会被随即覆盖，故必须延后处理。
     */
    data class DictDownloadOutcome(
        val downloaded: Int,
        val kinds: Set<DictReload.Kind>,
        val mergedRels: Set<String> = emptySet(),
        val deferredRels: Set<String> = emptySet()
    )

    /**
     * 云端词库目录下的文件总数（递归）；目录不存在时视为 0，读取失败返回 null。
     *
     * 供自动同步判断“云端是否已有词库备份”：全新设备本地没有同步记录时，
     * 只有确认云端为空才敢上传，否则会用本地默认词库覆盖云端备份。
     */
    suspend fun remoteDictFileCount(cfg: WebDavSyncConfig): Int? = withContext(Dispatchers.IO) {
        try {
            listRemoteDictFiles(cfg, dictDirUrl(cfg)).size
        } catch (e: LocalNetworkPermissionRequiredException) {
            // 权限缺失不能当作“云端没有备份”，否则会被误判为可以安全上传
            throw e
        } catch (e: Exception) {
            Timber.w("$TAG remoteDictFileCount failed: ${e.javaClass.name}: ${e.message}")
            null
        }
    }

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
                        // 权限缺失不能当作“没有备份”，否则会静默跳过并显示为已是最新
                        if (it is LocalNetworkPermissionRequiredException) throw it
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
                // 阶段 2：全部下载成功后才统一替换到最终位置；任一替换失败则回滚已替换的文件，
                // 避免 data/ 停留在“半套新版 + 半套旧版”的混合状态（随后会被上传误用）。
                val backupDir = File(tempDir, "download_backup").apply {
                    if (exists()) deleteRecursively()
                    mkdirs()
                }
                // 每替换一个本地文件前先备份原内容；中途失败时按相反顺序还原
                val replaced = mutableListOf<Pair<File, File?>>()
                // 叠加用的工作目录：与暂存目录分开，避免与待落盘文件互相覆盖
                val mergeWorkDir = File(tempDir, "merge_work").apply {
                    if (exists()) deleteRecursively()
                    mkdirs()
                }
                // 本轮发生叠加（并集 ≠ 云端内容）的文件：本地已比云端多出条目，
                // 必须让随后的上传重新推送，否则并集永远只停留在本机
                val mergedRels = mutableSetOf<String>()
                // 引擎自持词库（user.dict）：运行期间叠加会被引擎的保存抹掉，改为暂存待延后处理
                val deferredRels = mutableSetOf<String>()
                try {
                    staged.forEach { (rel, current, stagedFile, local) ->
                        // 引擎自持文件：只暂存云端副本并登记，等引擎停机后再叠加。
                        // 绝不能在引擎运行时写本地——内存内容会在下次保存时覆盖它。
                        if (DictMerger.isEngineOwned(rel)) {
                            if (PendingDictMerge.stage(rel, stagedFile)) {
                                deferredRels += rel
                                snapshot[rel] = current
                                // 记录当前本地摘要：叠加完成前不上传本地内容，
                                // 避免把尚未合并的 user.dict 抢先推到云端。
                                // 叠加成功后 PendingDictMerge 会清掉该摘要，令并集被推送。
                                // 本地缺失时不记摘要（无内容可推），叠加阶段会直接采用云端副本。
                                if (local.isFile) digests[rel] = BackupZips.digest(local)
                            }
                            return@forEach
                        }
                        local.parentFile?.mkdirs()
                        val backup = if (local.exists()) {
                            File.createTempFile("dict_bak_", ".tmp", backupDir).also {
                                Files.copy(local.toPath(), it.toPath(), StandardCopyOption.REPLACE_EXISTING)
                            }
                        } else null
                        replaced += local to backup
                        // 叠加策略与 force 无关：force 只表示“忽略本地快照、重新下载”，
                        // 落盘一律走叠加（并集），任何路径都不会丢弃本地独有条目
                        val strategy = DictMerger.strategyFor(rel)
                        // 本地不存在时无可叠加，直接落盘；否则按策略叠加或覆盖
                        val applied = if (local.exists() &&
                            strategy != DictMerger.Strategy.OVERWRITE
                        ) {
                            val merged = DictMerger.merge(stagedFile, local, strategy, mergeWorkDir)
                            if (merged) mergedRels += rel
                            merged
                        } else {
                            Files.move(
                                stagedFile.toPath(), local.toPath(),
                                StandardCopyOption.REPLACE_EXISTING
                            )
                            true
                        }
                        // 叠加失败时保持本地原文件（绝不用云端内容覆盖本地，否则会丢本地词条），
                        // 交由回滚还原后下次重试
                        if (!applied) throw IOException("Failed to merge dictionary: $rel")
                        snapshot[rel] = current
                        digests[rel] = BackupZips.digest(local)
                    }
                } catch (e: Exception) {
                    // 回滚：把已替换的文件恢复为原内容（原本不存在的文件则删除），保持 data/ 一致性
                    replaced.asReversed().forEach { (local, backup) ->
                        runCatching {
                            if (backup == null) local.delete()
                            else Files.move(backup.toPath(), local.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        }.onFailure {
                            Timber.w("$TAG rollback failed for ${local.path}: ${it.javaClass.name}: ${it.message}")
                        }
                    }
                    throw e
                } finally {
                    if (mergeWorkDir.exists()) mergeWorkDir.deleteRecursively()
                    if (backupDir.exists()) backupDir.deleteRecursively()
                }
                // 叠加过的文件：清掉本地内容摘要，使下一次上传必定推送并集。
                // 若保留摘要，上传会认为“内容未变”而跳过，云端将永远停留在旧内容，
                // 并集只存在于本机（换设备即丢失）。
                mergedRels.forEach { digests.remove(it) }
                commitConfig(stored) {
                    it.copy(
                        dictFileDigests = digests,
                        dictRemoteSnapshot = snapshot,
                        // 登记延后叠加项：引擎停机后由 PendingDictMerge 完成并上传并集
                        pendingDictMerges = it.pendingDictMerges + deferredRels.associateWith { rel ->
                            DictMerger.pendingFileNameFor(rel)
                        },
                        lastSyncDescription = if (downloaded == 0) {
                            it.lastSyncDescription
                        } else {
                            text(R.string.webdav_last_dict_downloaded, todayStr)
                        }
                    )
                }
                // 云端快照已落盘，让引擎把它合并回本机词库
                exportUserDataSnapshots()
                Timber.i(
                    "$TAG downloadDictFiles: downloaded=$downloaded skipped=$skipped " +
                        "kinds=$kinds merged=$mergedRels deferred=$deferredRels"
                )
                Result.success(DictDownloadOutcome(downloaded, kinds, mergedRels, deferredRels))
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
            } catch (e: LocalNetworkPermissionRequiredException) {
                // 权限缺失不是“云端没有备份”，必须抛给调用方而不是返回空列表
                throw e
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
