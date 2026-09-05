/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.sync.webdav

import org.fcitx.fcitx5.android.utils.appContext
import java.io.File
import java.security.MessageDigest

/**
 * 词库收集与变更指纹。
 *
 * 用户词库（含导入词典、native 自学习词库）全部落在
 * `getExternalFilesDir(null)/data`（即 XDG_DATA_HOME）下，
 * 其中 `data/libime` 是系统只读模型目录，不属于用户词库。
 * 收集逻辑保持为纯函数，便于单元测试。
 */
object DictCollector {

    const val DATA_DIR_NAME = "data"

    /** 需要从词库备份中排除的顶层/任意级子目录名。 */
    val DEFAULT_EXCLUDED_DIRS: Set<String> = setOf("libime")

    data class DictEntry(
        /** 相对 external 根目录的路径（形如 data/pinyin/...） */
        val relativePath: String,
        val length: Long,
        val modified: Long
    )

    fun dataRoot(): File = File(appContext.getExternalFilesDir(null)!!, DATA_DIR_NAME)

    /**
     * 纯函数：列出 [dataDir] 下所有可同步词库文件。
     * 规则：跳过以 `.` 开头的隐藏目录、跳过 [excludedDirs] 中任意同名目录；
     * 不按大小过滤（词库需完整备份），但排除临时/锁文件。
     */
    fun listSyncable(
        dataDir: File,
        excludedDirs: Set<String> = DEFAULT_EXCLUDED_DIRS,
        parentRelative: String = DATA_DIR_NAME
    ): List<DictEntry> {
        if (!dataDir.isDirectory) return emptyList()
        val result = mutableListOf<DictEntry>()
        dataDir.listFiles()?.forEach { f ->
            val rel = "$parentRelative/${f.name}"
            if (f.isDirectory) {
                if (f.name.startsWith(".") || f.name in excludedDirs) return@forEach
                result += listSyncable(f, excludedDirs, rel)
            } else if (f.isFile) {
                val name = f.name.lowercase()
                if (f.name.startsWith(".")) return@forEach
                if (name.endsWith(".tmp") || name.endsWith(".lock") || name.endsWith(".swp")) {
                    return@forEach
                }
                result += DictEntry(rel, f.length(), f.lastModified())
            }
        }
        return result
    }

    /**
     * 纯函数：由词库文件相对路径推导需要写入 zip 的目录条目（不含尾斜杠）。
     *
     * 结果包含 [rootDirs] 并按字典序去重：zip 不允许重复条目，而根条目
     * （external/data）同时也会由中间目录推导产生，必须在这里一并去重。
     */
    fun dirEntriesFor(
        relativePaths: Iterable<String>,
        rootDirs: Iterable<String> = listOf("external", "external/$DATA_DIR_NAME")
    ): List<String> {
        val result = sortedSetOf<String>()
        rootDirs.forEach { result.add(it.trimEnd('/')) }
        relativePaths.forEach { rel ->
            var cur = rel.substringBeforeLast('/', "")
            while (cur.isNotEmpty()) {
                result.add("external/${cur.trimEnd('/')}")
                cur = cur.substringBeforeLast('/', "")
            }
        }
        return result.toList()
    }

    /** 计算词库清单指纹：路径+大小+修改时间排序后做 SHA-256 摘要。 */
    fun fingerprint(entries: List<DictEntry>): String {
        val sb = StringBuilder()
        entries.sortedBy { it.relativePath }.forEach { e ->
            sb.append(e.relativePath).append(':')
                .append(e.length).append(':')
                .append(e.modified).append('\n')
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
