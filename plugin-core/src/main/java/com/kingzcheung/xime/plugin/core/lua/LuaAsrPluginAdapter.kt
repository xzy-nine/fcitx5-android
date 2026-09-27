/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Kingz Cheung
 *
 * 移植自 Xime (https://github.com/ximeiorg/xime)，见仓库根 NOTICE.md。
 */
package com.kingzcheung.xime.plugin.core.lua

import android.content.Context
import com.kingzcheung.xime.plugin.core.api.AsrPlugin
import com.kingzcheung.xime.plugin.core.api.AsrPluginBackend
import com.kingzcheung.xime.plugin.core.lua.asr.LuaAsrBackend
import com.kingzcheung.xime.plugin.core.model.PluginCapabilities
import com.kingzcheung.xime.plugin.core.model.PluginContext

/**
 * speech 类型 Lua 插件的宿主侧适配器：实现 [AsrPlugin] 接口。
 *
 * 能力声明（getCapabilities）来自 manifest 元数据，Lua 侧不再提供 getProviderId/
 * getDisplayName/getCapabilities/isConfigured——名称/能力/配置就绪均由宿主按元数据判定。
 */
class LuaAsrPluginAdapter(
    runtime: LuaScriptRuntime,
    pluginContext: PluginContext
) : LuaPluginAdapter(runtime, pluginContext), AsrPlugin {

    override fun getCapabilities(): PluginCapabilities.SpeechCapabilities =
        pluginContext.pluginInfo.capabilities?.speech ?: PluginCapabilities.SpeechCapabilities()

    override fun isConfigured(): Boolean = super.isConfigured()

    override fun createBackend(context: Context): AsrPluginBackend = LuaAsrBackend(runtime)
}