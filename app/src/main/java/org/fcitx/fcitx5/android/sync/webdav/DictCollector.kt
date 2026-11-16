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
     * 旧格式词库的相对路径（相对 `data/`）。
     *
     * 这些文件的内容已被迁移成 Rime 用户词库文本，云端只保留迁移后的格式：
     * 旧格式既无法被其他设备直接使用，又会诱发重复迁移，因此不参与同步。
     */
    val LEGACY_DICT_PATHS: Set<String> = setOf("pinyin/user.dict")

    /** 相对 `data/` 的路径是否为旧格式词库。 */
    fun isLegacyDict(relativeUnderData: String): Boolean =
        relativeUnderData in LEGACY_DICT_PATHS ||
            (relativeUnderData.startsWith("pinyin/dictionaries/") &&
                relativeUnderData.endsWith(".dict"))

    /**
     * 删除本机残留的旧格式词库，返回删除的文件数。
     *
     * 词条已迁移进 Rime 用户词库，旧格式仅在与旧设备同步时才有意义；
     * 用户不再需要旧设备时可清理本机副本。删除后下一次同步不会再上传它们。
     */
    fun removeLegacyDicts(dataDir: File = dataRoot()): Int {
        if (!dataDir.isDirectory) return 0
        var removed = 0
        dataDir.walkTopDown().filter { it.isFile }.forEach { file ->
            val rel = file.relativeTo(dataDir).invariantSeparatorsPath
            if (!isLegacyDict(rel)) return@forEach
            if (file.delete()) removed++
        }
        return removed
    }

    /**
     * Rime 用户目录根部需要同步的文件名。
     *
     * `user.txt` 是可读的用户词库文本；引擎实际读取的是 `*.userdb`，
     * 二者都在本机生效，但跨设备复用走下面的同步快照。
     */
    val RIME_SYNCABLE_NAMES: Set<String> = setOf("user.txt")

    /**
     * Rime 用户数据同步快照的目录名。
     *
     * `sync/<installation_id>/` 由引擎的 sync_user_data 产出，内容是各词库的
     * `*.userdb.txt` 文本快照；它是跨设备合并的唯一入口，引擎按其中的 tick 自行增量合并。
     */
    const val RIME_SYNC_DIR = "sync"

    /** 同步快照目录下的文件后缀。 */
    const val RIME_SNAPSHOT_SUFFIX = ".userdb.txt"

    /**
     * 相对 `data/` 的路径是否属于 Rime 用户目录且需要同步。
     *
     * 只同步两处：根部的用户词库文本，以及 `sync/<installation_id>/` 下的快照文本。
     * 编译产物（build/）与 LevelDB 二进制（*.userdb/）属于本机状态，不参与同步。
     */
    fun isSyncableRimeFile(relativeUnderData: String): Boolean {
        val dir = relativeUnderData.substringBeforeLast('/', "")
        val name = relativeUnderData.substringAfterLast('/')
        val inRime = dir == "rime" || dir.endsWith("/rime") || dir.contains("/rime/")
        if (!inRime) return false
        if (name in RIME_SYNCABLE_NAMES) return true
        // sync/<installation_id>/<db>.userdb.txt
        val segments = relativeUnderData.split('/')
        val syncIndex = segments.indexOf(RIME_SYNC_DIR)
        if (syncIndex < 0 || syncIndex + 2 != segments.size - 1) return false
        return name.endsWith(RIME_SNAPSHOT_SUFFIX)
    }

    /**
     * 纯函数：列出 [dataDir] 下所有可同步词库文件。
     * 规则：跳过以 `.` 开头的隐藏目录、跳过 [excludedDirs] 中任意同名目录、
     * 跳过已被迁移取代的旧格式词库（见 [LEGACY_DICT_PATHS]）；
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
                val underData = rel.removePrefix("$DATA_DIR_NAME/")
                // 旧格式词库已迁移为 Rime 文本，不再上传
                if (isLegacyDict(underData)) return@forEach
                // Rime 用户目录只同步词库文本，编译产物与 LevelDB 词库属于本机状态
                if (underData.substringBefore('/') == "rime" && !isSyncableRimeFile(underData)) {
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
