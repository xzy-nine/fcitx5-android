/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.broadcast.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface BroadcastDao {
    @Insert
    suspend fun insert(pairedApp: PairedAppEntity): Long

    @Query("SELECT * FROM ${PairedAppEntity.TABLE_NAME}")
    suspend fun getAll(): List<PairedAppEntity>

    @Query("SELECT * FROM ${PairedAppEntity.TABLE_NAME} WHERE packageName=:packageName LIMIT 1")
    suspend fun findByPackageName(packageName: String): PairedAppEntity?

    @Query("SELECT * FROM ${PairedAppEntity.TABLE_NAME} WHERE id=:id LIMIT 1")
    suspend fun getById(id: Int): PairedAppEntity?

    @Query("DELETE FROM ${PairedAppEntity.TABLE_NAME} WHERE packageName=:packageName")
    suspend fun deleteByPackageName(packageName: String): Int

    @Query("DELETE FROM ${PairedAppEntity.TABLE_NAME} WHERE id=:id")
    suspend fun deleteById(id: Int): Int

    @Query("SELECT COUNT(*) FROM ${PairedAppEntity.TABLE_NAME}")
    suspend fun count(): Int
}
