/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Kingz Cheung
 *
 * 参考 Xime (https://github.com/ximeiorg/xime) 的 speech/AsrBackendFactory.kt 与
 * plugin/ExtensionManager.kt 的 ASR 部分重写，见仓库根 NOTICE.md。
 *
 * 存在意义：把「在线 ASR 插件」抽象为 speech 包可见的最小接口，避免 speech 包
 * 反向依赖 plugin-core 的 Lua 插件框架（也与 Xime 中由 ExtensionManager 直接注入的做法等价）。
 * 宿主侧（app）在插件框架接入后设置 [AsrPluginHostRegistry.provider]；
 * 未设置时 [AsrPluginHostRegistry.enabledAsrPlugins] 返回空列表，引擎装配自动退回本地离线后端。
 */
package com.kingzcheung.xime.speech

import android.content.Context
import android.util.Log

/** 在线 ASR 插件在「引擎装配」视角下的抽象。 */
interface AsrPluginHost {

    /** 插件 id（与 SettingsPreferences 中选中的 voice_online_plugin_id 对应）。 */
    val pluginId: String

    /** 展示名（设置页与语音面板显示）。 */
    val displayName: String

    /** 是否已完成配置（例如 API Key 已填写）。 */
    fun isConfigured(context: Context): Boolean

    /** 为一次会话创建后端。 */
    fun createBackend(context: Context): AsrBackend
}

object AsrPluginHostRegistry {

    private const val TAG = "AsrPluginHostRegistry"

    @Volatile
    var provider: ((Context) -> List<AsrPluginHost>)? = null

    fun enabledAsrPlugins(context: Context): List<AsrPluginHost> =
        try {
            provider?.invoke(context) ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "enabledAsrPlugins failed", e)
            emptyList()
        }

    /** 选中插件：优先用户选择，其次第一个已配置的插件，最后第一个插件。 */
    fun selectPlugin(
        context: Context,
        plugins: List<AsrPluginHost> = enabledAsrPlugins(context),
        selectedId: String
    ): AsrPluginHost? =
        plugins.firstOrNull { it.pluginId == selectedId }
            ?: plugins.firstOrNull { it.isConfigured(context) }
            ?: plugins.firstOrNull()
}
