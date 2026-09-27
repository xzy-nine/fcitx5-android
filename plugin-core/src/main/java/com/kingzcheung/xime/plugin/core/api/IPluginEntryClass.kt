/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Kingz Cheung
 *
 * 移植自 Xime (https://github.com/ximeiorg/xime)，见仓库根 NOTICE.md。
 */
package com.kingzcheung.xime.plugin.core.api

import android.content.Context
import androidx.compose.runtime.Composable
import com.kingzcheung.xime.plugin.core.model.PluginContext

interface IPluginEntryClass {

    fun onLoad(context: PluginContext)

    fun onUnload()

    @Composable
    fun Content() {}

    fun hasSettings(): Boolean = false

    fun openSettings(context: Context) {}

    /**
     * 插件可选的图标。返回 null 时由宿主展示默认图标。
     */
    fun getIcon(): PluginIcon? = null

    fun providesService(): List<Class<out Any>> = emptyList()

    fun <T : Any> getService(serviceClass: Class<T>): T? = null
}