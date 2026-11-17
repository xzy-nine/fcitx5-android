/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.settings.im

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.engine.ImportedRimeEngine
import org.fcitx.fcitx5.android.core.engine.PluginRimeEngineAuthorization
import org.fcitx.fcitx5.android.core.engine.RimeEngineElf
import org.fcitx.fcitx5.android.core.engine.RimeEnginePlugins
import org.fcitx.fcitx5.android.core.engine.RimeEngineRuntimeInfo
import org.fcitx.fcitx5.android.core.engine.RimeEngineStaticInfo
import org.fcitx.fcitx5.android.core.engine.RimeEngineRepository
import org.fcitx.fcitx5.android.core.engine.RimePluginEngineCandidate
import org.fcitx.fcitx5.android.core.engine.RimeEngineStore
import org.fcitx.fcitx5.android.utils.FileUtil
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/** 引擎导入、探测与授权，与界面无关。 */
internal object RimeEngineActions {

    /** 引擎文件的暂存根目录；按会话令牌隔离。 */
    fun importRoot(context: Context): File = File(context.codeCacheDir, "rime-engine-import")

    /** 探测副本的暂存根目录；按会话令牌隔离。 */
    fun probeRoot(context: Context): File = File(context.codeCacheDir, "rime-engine-probe")

    /** 清理不属于任何活跃会话的暂存目录。 */
    fun cleanupOrphaned(context: Context, activeTokens: Set<String>) {
        listOf(importRoot(context), probeRoot(context)).forEach { root ->
            root.listFiles().orEmpty().forEach { entry ->
                if (entry.name !in activeTokens) FileUtil.removeFile(entry)
            }
        }
    }

    /** 把所选文档复制到暂存目录并校验 ELF；返回暂存文件与静态信息。 */
    suspend fun stage(
        context: Context,
        uri: Uri,
        token: String,
    ): Pair<File, RimeEngineStaticInfo> = withContext(Dispatchers.IO) {
        val directory = File(importRoot(context), token).apply { check(isDirectory || mkdirs()) }
        val target = File.createTempFile("engine-", ".so", directory)
        try {
            val sourceName = displayName(context, uri)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= RimeEngineElf.MAX_ENGINE_BYTES) {
                            context.getString(R.string.rime_engine_import_failed, "size limit")
                        }
                        output.write(buffer, 0, count)
                    }
                    runCatching { output.fd.sync() }
                }
            } ?: error("unreadable document")
            target to RimeEngineElf.inspect(target, sourceName)
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
    }

    /** 为插件引擎生成只读探测副本，并校验内容未变化。 */
    suspend fun stageProbeCopy(
        context: Context,
        source: File,
        expected: RimeEngineStaticInfo,
        token: String,
    ): File = withContext(Dispatchers.IO) {
        val directory = File(probeRoot(context), token).apply { check(isDirectory || mkdirs()) }
        val target = File.createTempFile("probe-", ".so", directory)
        try {
            source.inputStream().use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= RimeEngineElf.MAX_ENGINE_BYTES) {
                            context.getString(R.string.rime_engine_import_failed)
                        }
                        output.write(buffer, 0, count)
                    }
                    runCatching { output.fd.sync() }
                }
            }
            require(RimeEngineElf.inspect(target, expected.sourceName).sha256 == expected.sha256) {
                context.getString(R.string.rime_engine_import_failed, context.getString(R.string.rime_engine_platform_mismatch))
            }
            check(target.setReadOnly()) { "cannot protect probe copy" }
            target
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
    }

    /** 安装导入的引擎；文件内容需与静态信息一致。 */
    suspend fun install(
        context: Context,
        repository: RimeEngineRepository,
        file: File,
        name: String,
        info: RimeEngineStaticInfo,
        runtime: RimeEngineRuntimeInfo,
        profile: String,
    ) = withContext(Dispatchers.IO) {
        require(RimeEngineElf.inspect(file, info.sourceName).sha256 == info.sha256) {
            context.getString(R.string.rime_engine_import_failed, "content changed")
        }
        repository.installImported(file, name, info, runtime, profile)
    }

    /** 授权插件引擎；授权前重新校验包与库未变化。 */
    suspend fun authorize(
        context: Context,
        repository: RimeEngineRepository,
        candidate: RimePluginEngineCandidate,
        runtime: RimeEngineRuntimeInfo,
        profile: String,
    ) = withContext(Dispatchers.IO) {
        val verified = RimeEnginePlugins.requireCurrent(context, candidate)
        repository.authorizePlugin(
            PluginRimeEngineAuthorization(
                packageName = verified.packageName,
                displayName = verified.displayName,
                providerId = verified.providerId,
                certificateSha256 = verified.certificateSha256,
                payloadSha256 = verified.staticInfo.sha256,
                soname = verified.soname,
                staticInfo = verified.staticInfo,
                runtimeInfo = runtime,
                profile = profile,
                authorizedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** 静态信息的多行摘要，用于导入前确认。 */
    fun staticDetails(context: Context, info: RimeEngineStaticInfo, plugin: RimePluginEngineCandidate?): String =
        buildString {
            append(context.getString(R.string.rime_engine_file_value, info.sourceName)).append('\n')
            append(context.getString(R.string.rime_engine_abi_value, info.abi, info.bits)).append('\n')
            append(
                context.getString(
                    R.string.rime_engine_size_value,
                    String.format(Locale.ROOT, "%.2f", info.size / 1024.0 / 1024.0),
                ),
            ).append('\n')
            append("SHA-256: ").append(info.sha256).append('\n')
            append("SONAME: ").append(info.soname ?: context.getString(R.string.none)).append('\n')
            append("Build ID: ").append(info.buildId ?: context.getString(R.string.none)).append('\n')
            append(
                context.getString(
                    R.string.rime_engine_dependencies_value,
                    info.neededLibraries.joinToString().ifBlank { context.getString(R.string.none) },
                ),
            )
            plugin?.let {
                append("\n\n").append(context.getString(R.string.rime_engine_plugin_package, it.packageName))
                append('\n').append(
                    context.getString(R.string.rime_engine_plugin_certificate, it.certificateSha256),
                )
            }
        }

    /** 运行期信息的多行摘要。 */
    fun runtimeDetails(context: Context, info: RimeEngineRuntimeInfo): String = buildString {
        append(context.getString(R.string.rime_engine_version_value, info.librimeVersion)).append('\n')
        append(context.getString(R.string.rime_engine_api_size_value, info.apiDataSize)).append('\n')
        append(
            context.getString(
                R.string.rime_engine_capabilities_value,
                info.capabilities.joinToString().ifBlank { context.getString(R.string.none) },
            ),
        )
    }

    /** 所选文档的显示名。 */
    fun displayName(context: Context, uri: Uri): String {
        var cursor: android.database.Cursor? = null
        return try {
            cursor = context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
            )
            if (cursor?.moveToFirst() == true) cursor.getString(0) ?: "librime.so"
            else uri.lastPathSegment?.substringAfterLast('/') ?: "librime.so"
        } catch (_: Exception) {
            uri.lastPathSegment?.substringAfterLast('/') ?: "librime.so"
        } finally {
            cursor?.close()
        }
    }
}
