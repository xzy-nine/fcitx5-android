/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Kingz Cheung
 *
 * 移植自 Xime (https://github.com/ximeiorg/xime) 的 plugin/PluginNetworkAuthHelper.kt，
 * 见仓库根 NOTICE.md。
 *
 * 与上游的差异：上游被拒时会拉起 Xime 的 MainActivity 插件授权页；本移植没有该页面，
 * 改为只记录待授权域名 + Toast 提示，并在「语音输入设置页」集中展示待授权域名供一键授权
 * （避免为一个授权页再造 Activity/导航分支）。
 */
package com.kingzcheung.xime.plugin

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.settings.SettingsPreferences

/**
 * 插件联网授权引导：
 * - [ensureAuthorized]：使用前检测——插件声明/配置了网络域名但未授权时，提示用户去设置页授权；
 * - [onNetworkDenied]：运行时兜底——被拒时记录待授权域名并提示原因。
 */
object PluginNetworkAuthHelper {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 使用前检测：返回 true 放行；false 表示存在未授权域名，已提示用户。 */
    fun ensureAuthorized(context: Context, pluginId: String): Boolean {
        val info = PluginManager.getAllInstallPlugins().firstOrNull { it.id == pluginId } ?: return true
        val unauthorized = ExtensionManager.getUnauthorizedHosts(context, pluginId)
        if (unauthorized.isEmpty()) return true

        val host = unauthorized.first()
        SettingsPreferences.addPluginPendingHost(context, pluginId, host)
        mainHandler.post {
            Toast.makeText(
                context,
                "插件「${info.name}」需要授权后才能联网访问 $host\n请在「语音输入 → 插件网络授权」中授权后重试",
                Toast.LENGTH_LONG
            ).show()
        }
        return false
    }

    fun onNetworkDenied(
        context: Context,
        pluginId: String,
        pluginName: String?,
        host: String?,
        reason: String
    ) {
        if (host == null) return
        SettingsPreferences.addPluginPendingHost(context, pluginId, host)
        mainHandler.post {
            val name = pluginName ?: pluginId
            Toast.makeText(
                context,
                "插件「$name」联网被拒绝：$reason\n请在「语音输入 → 插件网络授权」中授权后重试",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
