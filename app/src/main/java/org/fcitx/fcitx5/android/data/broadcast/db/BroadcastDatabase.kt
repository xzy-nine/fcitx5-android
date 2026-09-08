/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.broadcast.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [PairedAppEntity::class],
    version = 1
)
abstract class BroadcastDatabase : RoomDatabase() {
    abstract fun broadcastDao(): BroadcastDao

    companion object {
        /** Room 数据库文件名（位于 filesDir/databases/，含 -wal/-shm 附属文件）。 */
        const val DATABASE_NAME = "broadcast_db"
    }
}
