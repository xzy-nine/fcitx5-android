/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.broadcast

import org.fcitx.fcitx5.android.data.broadcast.db.BroadcastDatabase
import java.io.File

/**
 * 剪切板广播“已配对应用”数据的备份/恢复过滤。
 *
 * 已配对应用（[PairedAppEntity]，存于 Room 库 [BroadcastDatabase.DATABASE_NAME]）里的
 * keyAlias 指向本机 Android Keystore 中的配对密钥——该密钥通过配对码交互建立、不可导出，
 * 无法随备份迁移到其它设备。因此偏好设置 zip 的备份不应打包该数据库；
 * 恢复时也只应忽略 zip 里携带的配对数据，绝不能覆盖/清空本机已有的有效配对
 * （本机密钥仍在 Keystore 中，可正常工作，无需重新配对）。
 */
object BroadcastBackupFilter {

    /**
     * [databases] 分区下某个文件名是否属于广播配对数据库
     * （主文件 + Room 附属文件，如 broadcast_db / broadcast_db-wal / broadcast_db-shm）。
     */
    fun isBroadcastDatabaseFile(fileName: String): Boolean {
        val name = fileName.substringAfterLast('/').substringAfterLast('\\')
        val base = BroadcastDatabase.DATABASE_NAME
        return name == base || name.startsWith("$base-")
    }

    /**
     * 删除 [dir]（一般为 zip 解压出的临时 databases 目录）中的广播配对库文件，
     * 使后续复制分区时不会把备份内携带的配对数据覆盖到本机，从而保留本机已有配对。
     */
    fun deleteBroadcastDatabaseFiles(dir: File) {
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach { f ->
            if (f.isFile && isBroadcastDatabaseFile(f.name)) {
                f.delete()
            }
        }
    }
}
