/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.broadcast.BroadcastBackupFilter
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.errorRuntime
import org.fcitx.fcitx5.android.utils.extract
import org.fcitx.fcitx5.android.utils.withTempDir
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 用户数据导入兼容层（custom 分支专用）。
 *
 * 背景：早期 debug 构建带 `applicationIdSuffix = ".debug"`，因此其导出的 zip 里
 * - metadata.json 的 packageName 为 `<base>.debug`；
 * - shared_prefs 下的主偏好文件名为 `<base>.debug_preferences.xml`（SharedPreferences 文件名带包名）。
 *
 * 现在 debug 与 release 统一使用 `<base>` 包名。若直接走 [UserDataManager.import]，
 * 一是 metadata 的 packageName 硬校验会把历史 debug 备份全部拒掉，
 * 二是即便放行，旧名字的偏好文件也不会被本机读取（等于偏好没恢复）。
 *
 * 这里在导入前做两步转换：
 * 1. 包名归一化比较（去掉 `.debug` 后缀后再校验），使 debug/release 备份可以互换；
 * 2. 把 shared_prefs 下属于历史 debug 包名的文件改名为当前包名，使偏好真正生效。
 *
 * 分区结构与 [UserDataManager] 完全一致（metadata 硬校验 + 分区缺失软容忍），
 * 因此偏好 zip / 词库 zip / 完整 zip 都能走这里导入。
 */
object UserDataImportCompat {

    private const val TAG = "UserDataImportCompat"

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /** 当前包名去掉历史 `.debug` 后缀后的基准包名，作为“同一应用”的判定基准。 */
    private val basePackageName: String
        get() = BuildConfig.APPLICATION_ID.removeSuffix(".debug")

    /** 历史 debug 构建使用的包名；与当前包名相同时无需迁移。 */
    private val legacyDebugPackageName: String
        get() = "$basePackageName.debug"

    private val sharedPrefsDir = File(appContext.applicationInfo.dataDir, "shared_prefs")
    private val dataBasesDir = File(appContext.applicationInfo.dataDir, "databases")
    private val externalDir = appContext.getExternalFilesDir(null)!!
    private val recentlyUsedDir = appContext.filesDir.resolve(RecentlyUsed.DIR_NAME)

    /**
     * 导入用户数据 zip。与 [UserDataManager.import] 行为一致，但兼容历史 debug 包名。
     * 返回 zip 内的元数据（含导出时间）。
     */
    fun import(src: InputStream): Result<UserDataManager.Metadata> = runCatching {
        ZipInputStream(src).use { zipStream ->
            withTempDir { tempDir ->
                val extracted = zipStream.extract(tempDir)
                val metadataFile = extracted.find { it.name == "metadata.json" }
                    ?: errorRuntime(R.string.exception_user_data_metadata)
                val metadata = json.decodeFromString<UserDataManager.Metadata>(
                    metadataFile.readText()
                )
                if (metadata.packageName.removeSuffix(".debug") != basePackageName)
                    errorRuntime(R.string.exception_user_data_package_name_mismatch)
                migrateLegacySharedPrefs(File(tempDir, "shared_prefs"))
                copyDir(File(tempDir, "shared_prefs"), sharedPrefsDir)
                // custom: 忽略备份 zip 内携带的广播“已配对应用”库（配对密钥在本机 Keystore，
                // 不可迁移），只丢弃不解入，本机已有的有效配对保持不变
                val tempDatabases = File(tempDir, "databases")
                BroadcastBackupFilter.deleteBroadcastDatabaseFiles(tempDatabases)
                copyDir(tempDatabases, dataBasesDir)
                copyDir(File(tempDir, "external"), externalDir)
                // keep importing recently_used for backwards compatibility
                copyDir(File(tempDir, "recently_used"), recentlyUsedDir)
                metadata
            }
        }
    }

    /**
     * 把历史 debug 包名的 shared_prefs 文件改名为当前包名，
     * 例如 `org.fcitx.fcitx5.android.debug_preferences.xml`
     *   -> `org.fcitx.fcitx5.android_preferences.xml`。
     */
    private fun migrateLegacySharedPrefs(dir: File) {
        if (!dir.isDirectory) return
        val legacy = legacyDebugPackageName
        val current = BuildConfig.APPLICATION_ID
        if (legacy == current) return
        dir.listFiles()?.forEach { f ->
            if (!f.isFile || !f.name.startsWith(legacy)) return@forEach
            val target = File(dir, f.name.replaceFirst(legacy, current))
            if (target.exists() && !target.delete()) {
                Timber.w("$TAG cannot remove existing ${target.name}")
            }
            if (f.renameTo(target)) {
                Timber.i("$TAG migrated shared_prefs ${f.name} -> ${target.name}")
            } else {
                Timber.w("$TAG failed to migrate shared_prefs ${f.name}")
            }
        }
    }

    private fun copyDir(source: File, target: File) {
        val exists = source.exists()
        val isDir = source.isDirectory
        if (exists && isDir) {
            source.copyRecursively(target, overwrite = true)
        } else {
            Timber.w("$TAG skip partition: path='${source.path}', exists=$exists, isDir=$isDir")
        }
    }
}
