/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.sync.webdav

import org.fcitx.fcitx5.android.utils.appContext
import java.io.File
import java.security.MessageDigest

/**
 * 设置类数据（偏好）的收集与内容指纹。
 *
 * 自动备份设置只包含 `shared_prefs/` 与 `external/config/`（fcitx 文本配置），
 * 不含 `databases/`：剪贴板历史等库频繁变动，会让备份体积与重传频率都失去意义；
 * 导入侧本就对缺失分区软容忍，恢复该备份不影响其余数据。
 *
 * 指纹取各文件内容的摘要而非 zip 字节摘要——zip 内含导出时间戳，每次打包都不同，
 * 无法用来判断“内容未变”。收集与指纹均为纯函数，便于单元测试。
 */
object PrefsCollector {

    /** 备份中设置分区的相对路径前缀 -> 本地目录。 */
    const val SHARED_PREFS_PARTITION = "shared_prefs"
    const val CONFIG_PARTITION = "external/config"

    /** 需要排除的临时/锁文件后缀。 */
    private val EXCLUDED_SUFFIXES = listOf(".tmp", ".lock", ".swp")

    /**
     * 不参与内容指纹的偏好键：这些键随进程/运行状态变化，与用户设置无关。
     *
     * `pid` 每次进程启动都会被写成当前进程号，若计入指纹，则每次冷启动都会判定
     * “设置已变化”而重复上传整份备份。
     */
    val VOLATILE_PREF_KEYS: Set<String> = setOf("pid", "last_crash_time")

    data class Entry(
        /** 相对应用数据根的路径（形如 shared_prefs/xxx_preferences.xml） */
        val relativePath: String,
        /** 文件内容摘要（偏好 XML 会先剔除易变键） */
        val digest: String
    )

    /** 自动备份设置的分区根目录（分区名 -> 本地目录）。 */
    fun settingsRoots(): List<Pair<String, File>> = listOf(
        SHARED_PREFS_PARTITION to File(appContext.applicationInfo.dataDir, "shared_prefs"),
        CONFIG_PARTITION to File(appContext.getExternalFilesDir(null)!!, "config")
    )

    /**
     * 纯函数：列出 [roots] 下所有可备份的设置文件。
     *
     * 规则：跳过以 `.` 开头的隐藏条目与临时/锁文件；目录不存在时视为空。
     */
    fun listSyncable(roots: List<Pair<String, File>>): List<Entry> {
        val result = mutableListOf<Entry>()
        roots.forEach { (partition, dir) ->
            if (!dir.isDirectory) return@forEach
            dir.walkTopDown()
                // 隐藏目录整棵跳过（walkTopDown 支持在 onEnter 阶段剪枝）
                .onEnter { it == dir || !it.name.startsWith(".") }
                .forEach { f ->
                    if (f == dir || !f.isFile) return@forEach
                    val name = f.name
                    if (name.startsWith(".")) return@forEach
                    if (EXCLUDED_SUFFIXES.any { name.endsWith(it, ignoreCase = true) }) return@forEach
                    val related = f.relativeTo(dir).path.replace(File.separatorChar, '/')
                    val rel = "$partition/$related"
                    result += Entry(rel, digestOf(f, rel))
                }
        }
        return result
    }

    /**
     * 计算参与指纹的摘要：偏好 XML 先剔除 [VOLATILE_PREF_KEYS] 再取摘要，
     * 其余文件直接取内容摘要。
     */
    private fun digestOf(file: File, relativePath: String): String {
        val text = if (relativePath.endsWith(".xml")) {
            runCatching { file.readText() }.getOrNull()
        } else null
        return if (text == null) digest(file) else digestOfText(stripVolatilePrefs(text))
    }

    /**
     * 从偏好 XML 文本中移除 [VOLATILE_PREF_KEYS] 对应的条目。
     *
     * SharedPreferences 的 XML 是扁平的 `<map><type name="key" .../></map>`，每个条目一行；
     * 只剔除“整行是一个以 `<` 开头的标签、且其 name 命中”的行，避免误伤字符串值里
     * 恰好出现同样文本的内容。匹配不到任何条目时原样返回。
     */
    fun stripVolatilePrefs(xml: String): String {
        if (VOLATILE_PREF_KEYS.isEmpty()) return xml
        val kept = xml.lineSequence().filterNot { line ->
            val trimmed = line.trimStart()
            if (!trimmed.startsWith("<")) return@filterNot false
            VOLATILE_PREF_KEYS.any { key -> trimmed.contains("name=\"$key\"") }
        }
        return kept.joinToString("\n")
    }

    private fun digestOfText(text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(text.toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 纯函数：由文件清单计算内容指纹（路径 + 内容摘要，按路径排序后做 SHA-256）。
     * 与遍历顺序无关；空清单也有稳定非空值。
     */
    fun fingerprint(entries: List<Entry>): String {
        val sb = StringBuilder()
        entries.sortedBy { it.relativePath }.forEach { e ->
            sb.append(e.relativePath).append(':').append(e.digest).append('\n')
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** 计算当前设置内容的指纹。 */
    fun currentFingerprint(): String = fingerprint(listSyncable(settingsRoots()))

    /**
     * 文件内容 SHA-256。流式读取，避免大文件整块进内存。
     *
     * 刻意不复用 [BackupZips.digest]：那个对象的字段初始化会读 `appContext`，
     * 会让本对象在 JVM 单测里不可用（收集与指纹必须保持纯函数）。
     */
    fun digest(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val read = input.read(buf)
                if (read < 0) break
                md.update(buf, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
