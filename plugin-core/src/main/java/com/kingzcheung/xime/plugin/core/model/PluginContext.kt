/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Kingz Cheung
 *
 * 移植自 Xime (https://github.com/ximeiorg/xime)，见仓库根 NOTICE.md。
 */
package com.kingzcheung.xime.plugin.core.model

import android.app.Application
import com.kingzcheung.xime.plugin.core.config.NoopPluginConfigStore
import com.kingzcheung.xime.plugin.core.config.PluginConfigStore

data class PluginContext(
    val application: Application,
    val pluginInfo: PluginInfo,
    val pluginId: String = pluginInfo.id,
    val configStore: PluginConfigStore = NoopPluginConfigStore
)
