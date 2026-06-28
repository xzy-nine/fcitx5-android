/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.broadcast.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = PairedAppEntity.TABLE_NAME)
data class PairedAppEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val packageName: String,
    @ColumnInfo(defaultValue = "")
    val appName: String = "",
    val pairedAt: Long = System.currentTimeMillis(),
    val keyAlias: String
) {
    companion object {
        const val TABLE_NAME = "paired_apps"
    }
}
