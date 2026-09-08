/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.sync.webdav

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.data.UserDataManager
import org.fcitx.fcitx5.android.data.broadcast.BroadcastBackupFilter
import org.fcitx.fcitx5.android.utils.Const
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.versionCodeCompat
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 偏好 zip 打包器。
 *
 * 产物沿用 [UserDataManager] 的 zip 结构约定：根含 metadata.json（packageName/versionCode/
 * versionName/exportTime 硬校验）与顶层分区目录（shared_prefs/databases/external）。因此
 * 该 zip 能被 [UserDataManager.import] 直接导入（缺失分区软容忍，天然支持分区级部分恢复）。
 *
 * 偏好 zip：shared_prefs/ + databases/ + external/config/（fcitx 文本配置，设置类数据）。
 *
 * 词库同步走逐文件 [WebDavSyncEngine.uploadDictFiles]/[WebDavSyncEngine.downloadDictFiles]，
 * 不再打整包 zip。
 */
object BackupZips {

    private const val METADATA_ENTRY_NAME = "metadata.json"
    private const val BUFFER_SIZE = 8192

    private val json = Json { prettyPrint = true }

    @Serializable
    data class BackupMetadata(
        val packageName: String,
        val versionCode: Long,
        val versionName: String,
        val exportTime: Long
    )

    private val sharedPrefsDir = File(appContext.applicationInfo.dataDir, "shared_prefs")
    private val databasesDir = File(appContext.applicationInfo.dataDir, "databases")
    private val externalDir = appContext.getExternalFilesDir(null)!!
    private val externalConfigDir = File(externalDir, "config")

    private fun ZipOutputStream.writeMetadata(timestamp: Long) {
        val pkgInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        val metadata = BackupMetadata(
            packageName = pkgInfo.packageName,
            versionCode = pkgInfo.versionCodeCompat,
            versionName = Const.versionName,
            exportTime = timestamp
        )
        putNextEntry(ZipEntry(METADATA_ENTRY_NAME))
        write(json.encodeToString(metadata).toByteArray())
        closeEntry()
    }

    private fun ZipOutputStream.addDirEntry(path: String) {
        putNextEntry(ZipEntry(path.trimEnd('/') + "/"))
        closeEntry()
    }

    /** 递归把 [srcDir] 整棵子树写入 zip（目录含尾斜杠条目），与 UserDataManager.writeFileTree 行为一致。 */
    private fun ZipOutputStream.zipTree(
        srcDir: File,
        prefix: String,
        excludeFile: (File) -> Boolean = { false }
    ) {
        if (!srcDir.exists()) return
        addDirEntry(prefix)
        srcDir.walkTopDown().forEach { f ->
            val related = f.relativeTo(srcDir).path
            if (related.isEmpty()) return@forEach
            if (f.isDirectory) {
                addDirEntry("$prefix/${related.trimEnd('/')}")
            } else if (f.isFile && !excludeFile(f)) {
                putNextEntry(ZipEntry("$prefix/$related"))
                f.inputStream().use { it.copyTo(this) }
                closeEntry()
            }
        }
    }

    fun buildPrefsZip(dest: File, timestamp: Long = System.currentTimeMillis()): Result<File> =
        runCatching {
            dest.parentFile?.mkdirs()
            ZipOutputStream(dest.outputStream().buffered()).use { zip ->
                zip.zipTree(sharedPrefsDir, "shared_prefs")
                // databases（剔除剪切板广播“已配对应用”库：配对密钥在本机 Keystore，不随备份迁移）
                zip.zipTree(databasesDir, "databases") { f ->
                    BroadcastBackupFilter.isBroadcastDatabaseFile(f.name)
                }
                if (externalConfigDir.exists()) {
                    zip.addDirEntry("external")
                    zip.zipTree(externalConfigDir, "external/config")
                }
                zip.writeMetadata(timestamp)
            }
            dest
        }

    /** 文件内容 SHA-256 摘要，用于“内容未变则跳过上传”。流式读取，避免整文件进内存。 */
    fun digest(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input -> md.updateFrom(input) }
        return md.digest().hex()
    }

    private fun MessageDigest.updateFrom(input: java.io.InputStream) {
        val buf = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buf)
            if (read < 0) break
            update(buf, 0, read)
        }
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}
