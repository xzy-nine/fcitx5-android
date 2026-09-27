/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Kingz Cheung
 *
 * 移植自 Xime (https://github.com/ximeiorg/xime) 的 settings/SettingsPreferences.kt，
 * 见仓库根 NOTICE.md。
 *
 * 与上游的差异（为适配 fcitx5-android）：
 *  - 上游 SettingsPreferences 是一个 667 行的全局偏好聚合器（主题/键盘/词库/预测/STT…）。
 *    本移植只保留语音输入链路真正用到的那部分，并且把「用户可见的语音设置」改为委托到
 *    AppPrefs.Voice —— 这样这些设置会出现在应用设置页、参与设置搜索与偏好备份，
 *    符合本仓库 AGENTS.md 第 4 条「新设置项必须定义在 AppPrefs 对应 inner class 内」。
 *  - 插件框架自身的动态状态（每个插件的启用开关、按插件按域名的网络授权记录）不适合放进
 *    AppPrefs 的强类型偏好里，单独放一个 `voice_plugin_prefs` 文件，key 命名与上游一致。
 */
package com.kingzcheung.xime.settings

import android.content.Context
import android.content.SharedPreferences
import org.fcitx.fcitx5.android.data.prefs.AppPrefs

object SettingsPreferences {

    private const val PLUGIN_PREFS_NAME = "voice_plugin_prefs"

    private fun pluginPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PLUGIN_PREFS_NAME, Context.MODE_PRIVATE)

    // ---- 语音识别总开关与引擎选择（委托 AppPrefs.Voice） ----

    fun isSttEnabled(context: Context): Boolean =
        AppPrefs.getInstance().voice.voiceInputEnabled.getValue()

    fun setSttEnabled(context: Context, enabled: Boolean) {
        AppPrefs.getInstance().voice.voiceInputEnabled.setValue(enabled)
    }

    /** 语音转文本是否使用本地（离线）识别引擎。 */
    fun isSttUseLocal(context: Context): Boolean =
        AppPrefs.getInstance().voice.voiceUseLocal.getValue()

    fun setSttUseLocal(context: Context, useLocal: Boolean) {
        AppPrefs.getInstance().voice.voiceUseLocal.setValue(useLocal)
    }

    fun getSttOnlinePluginId(context: Context): String =
        AppPrefs.getInstance().voice.voiceOnlinePluginId.getValue()

    fun setSttOnlinePluginId(context: Context, pluginId: String) {
        AppPrefs.getInstance().voice.voiceOnlinePluginId.setValue(pluginId)
    }

    /** 是否把语音识别期间的录音写入文件（调试用）。 */
    fun isSttDebugRecord(context: Context): Boolean =
        AppPrefs.getInstance().voice.voiceDebugRecord.getValue()

    fun setSttDebugRecord(context: Context, enabled: Boolean) {
        AppPrefs.getInstance().voice.voiceDebugRecord.setValue(enabled)
    }

    fun isVerboseLoggingEnabled(context: Context): Boolean =
        AppPrefs.getInstance().internal.verboseLog.getValue()

    // ---- 插件启用开关 ----

    fun isPluginEnabled(context: Context, pluginId: String): Boolean {
        val prefs = pluginPrefs(context)
        val key = "plugin_enabled_$pluginId"
        if (prefs.contains(key)) return prefs.getBoolean(key, false)
        // 未显式设置过：跟随插件自身的默认启用状态（由调用方从 PluginManager 取），此处默认启用
        return true
    }

    fun setPluginEnabled(context: Context, pluginId: String, enabled: Boolean) {
        pluginPrefs(context).edit().putBoolean("plugin_enabled_$pluginId", enabled).apply()
    }

    // ---- 插件网络授权（per plugin per host） ----

    /** 插件已获用户授权的域名集合。 */
    fun getPluginAuthorizedHosts(context: Context, pluginId: String): Set<String> =
        decodeHosts(pluginPrefs(context).getString("plugin_net_auth_$pluginId", ""))

    /** 授权插件访问指定域名。 */
    fun authorizePluginHost(context: Context, pluginId: String, host: String) {
        val hosts = getPluginAuthorizedHosts(context, pluginId) + host
        val pending = getPluginPendingHosts(context, pluginId) - host
        pluginPrefs(context).edit()
            .putString("plugin_net_auth_$pluginId", hosts.joinToString(","))
            .putString("plugin_net_pending_$pluginId", pending.joinToString(","))
            .apply()
    }

    /** 撤销插件对指定域名的授权。 */
    fun revokePluginHost(context: Context, pluginId: String, host: String) {
        val hosts = getPluginAuthorizedHosts(context, pluginId) - host
        pluginPrefs(context).edit()
            .putString("plugin_net_auth_$pluginId", hosts.joinToString(","))
            .apply()
    }

    /** 插件运行中被拒绝访问、待用户授权的域名集合（供 UI 显眼展示并引导授权）。 */
    fun getPluginPendingHosts(context: Context, pluginId: String): Set<String> =
        decodeHosts(pluginPrefs(context).getString("plugin_net_pending_$pluginId", ""))

    /** 记录插件运行中被拒绝访问的域名（去重；返回 true 表示首次记录）。 */
    fun addPluginPendingHost(context: Context, pluginId: String, host: String): Boolean {
        val pending = getPluginPendingHosts(context, pluginId)
        if (host in pending) return false
        pluginPrefs(context).edit()
            .putString("plugin_net_pending_$pluginId", (pending + host).joinToString(","))
            .apply()
        return true
    }

    /** 清除插件的全部待授权记录。 */
    fun clearPluginPendingHosts(context: Context, pluginId: String) {
        pluginPrefs(context).edit().putString("plugin_net_pending_$pluginId", "").apply()
    }

    private fun decodeHosts(raw: String?): Set<String> =
        raw.orEmpty().split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
}
