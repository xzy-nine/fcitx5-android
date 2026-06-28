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
}
