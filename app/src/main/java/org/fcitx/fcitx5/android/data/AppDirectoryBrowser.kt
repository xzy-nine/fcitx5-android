/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.data

import java.io.File

/**
 * 应用内目录选择的纯逻辑：把候选目录列表、路径换算与越界校验从界面剥离，
 * 供 Compose 页面直接使用。
 */
object AppDirectoryBrowser {

    /** 目录浏览列表中的一项。 */
    data class Entry(val label: String, val directory: File)

    /** 应用可写入的根目录。 */
    fun root(appFilesDir: File): File = appFilesDir.canonicalFile

    /** 规范化相对路径：统一分隔符、去掉首尾与重复的分隔符。 */
    fun normalizeRelative(path: String): String =
        path.replace('\\', '/').trim().trim('/').replace(Regex("/+"), "/")

    /** [target] 是否位于 [root] 之内（含 root 自身）。 */
    fun isInside(root: File, target: File): Boolean =
        target == root || target.path.startsWith(root.path + File.separator)

    /** 把相对路径解析为目录，越界或形态不符时返回 null。 */
    fun resolve(root: File, relativePath: String): File? {
        val normalized = normalizeRelative(relativePath)
        val directory = if (normalized.isBlank()) root else File(root, normalized)
        val canonical = runCatching { directory.canonicalFile }.getOrNull() ?: return null
        if (!isInside(root.canonicalFile, canonical)) return null
        if (canonical.exists() && !canonical.isDirectory) return null
        return canonical
    }

    /** 相对根目录的路径；位于根目录时返回空串。 */
    fun relativeOf(root: File, target: File): String {
        val canonicalRoot = root.canonicalFile
        val canonicalTarget = target.canonicalFile
        return if (canonicalTarget == canonicalRoot) "" else
            canonicalTarget.relativeTo(canonicalRoot).invariantSeparatorsPath
    }

    /** 列出一级子目录，首项为返回上级。 */
    fun entries(root: File, current: File): List<Entry> {
        val canonicalRoot = root.canonicalFile
        val result = mutableListOf<Entry>()
        if (current != canonicalRoot) {
            val parent = current.parentFile?.canonicalFile
                ?.takeIf { isInside(canonicalRoot, it) }
                ?: canonicalRoot
            result += Entry("../", parent)
        }
        current.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name.lowercase() }
            ?.forEach { child ->
                val canonicalChild = runCatching { child.canonicalFile }.getOrNull() ?: return@forEach
                if (isInside(canonicalRoot, canonicalChild)) {
                    result += Entry("${child.name}/", canonicalChild)
                }
            }
        return result
    }

    /** 展示用的目录路径，根目录显示为 `/`。 */
    fun displayPath(relative: String): String = if (relative.isBlank()) "/" else "/$relative"
}