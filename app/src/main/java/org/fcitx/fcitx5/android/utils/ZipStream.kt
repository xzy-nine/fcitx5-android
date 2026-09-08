/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import java.io.File
import java.util.zip.ZipInputStream

/**
 * @return top-level files in zip file
 *
 * 安全约定：每个条目（含目录条目）经 [File] 合并并规范化后都必须位于 [destDir] 内部，
 * 拒绝 `..` 及任何可逃逸出解压目录的条目名（zip slip）；以 `/` 开头的条目在 UNIX 语义下
 * 会被 [File] 并入解压目录内，同样无法逃逸。越界条目直接抛 [SecurityException]。
 * 文件条目写入前会先创建父目录，不依赖 zip 中目录条目的顺序。
 */
fun ZipInputStream.extract(destDir: File): List<File> {
    val canonicalDest = destDir.canonicalPath
    val destPrefix = "$canonicalDest${File.separator}"
    var entry = nextEntry
    while (entry != null) {
        val target = File(destDir, entry.name).canonicalFile
        if (target.path != canonicalDest && !target.path.startsWith(destPrefix)) {
            throw SecurityException("zip entry escapes destination directory: ${entry.name}")
        }
        if (entry.isDirectory) {
            target.mkdirs()
        } else {
            target.parentFile?.mkdirs()
            copyTo(target.outputStream())
        }
        entry = nextEntry
    }
    return destDir.listFiles()?.toList() ?: emptyList()
}
