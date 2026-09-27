/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Kingz Cheung
 *
 * 移植自 Xime (https://github.com/ximeiorg/xime)，见仓库根 NOTICE.md。
 */
package com.kingzcheung.xime.plugin.core.runtime.loader

import com.kingzcheung.xime.plugin.core.lua.LuaScriptRuntime
import com.kingzcheung.xime.plugin.core.model.PluginInfo

data class LoadedPluginInfo(
    val pluginInfo: PluginInfo,
    val script: LuaScriptRuntime? = null
)
