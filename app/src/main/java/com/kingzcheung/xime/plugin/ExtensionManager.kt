/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Kingz Cheung
 *
 * 移植自 Xime (https://github.com/ximeiorg/xime) 的 plugin/ExtensionManager.kt，见仓库根 NOTICE.md。
 *
 * 与上游的差异：上游 325 行，同时服务表情/剪贴板同步/工具面板等插件类型；
 * 本移植只保留「插件安装/启用查询 + ASR 插件取用 + 图标提取 + 分类查询」，
 * 去掉 emoji/clipboard/tool 相关函数与其数据依赖（EmojiData/EmojiCategory）。
 */
package com.kingzcheung.xime.plugin

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.plugin.core.api.AsrPlugin
import com.kingzcheung.xime.plugin.core.api.IPluginEntryClass
import com.kingzcheung.xime.plugin.core.api.PluginIcon
import com.kingzcheung.xime.plugin.core.lua.ws.NetworkPolicy
import com.kingzcheung.xime.plugin.core.model.PluginCategory
import com.kingzcheung.xime.plugin.core.model.PluginInfo
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

object ExtensionManager {
    private const val TAG = "ExtensionManager"

    private var initialized = false
    private var managerJob: Job = SupervisorJob()
    private val managerScope get() = CoroutineScope(managerJob + Dispatchers.IO)

    fun initialize(context: Context) {
        if (initialized) return
        if (!managerJob.isActive) {
            managerJob = SupervisorJob()
        }
        initialized = true
    }

    /** 提取插件图标（manifest icon / getIcon()）到本地 plugin_icons/，返回可供 Compose 使用的路径。 */
    fun extractPluginIcon(
        context: Context,
        pluginId: String,
        plugin: IPluginEntryClass,
        pluginInfo: PluginInfo?
    ): PluginIcon? {
        val pluginIcon = try {
            plugin.getIcon()
        } catch (e: Exception) {
            Log.w(TAG, "getIcon not supported by ${pluginInfo?.name}")
            null
        }

        if (pluginIcon == null) return null
        if (pluginIcon.text != null) return PluginIcon(text = pluginIcon.text)

        val assetName = pluginIcon.assetName
        if (assetName == null ||
            !com.kingzcheung.xime.plugin.core.runtime.installer.InstallerManager
                .isValidResourcePath(assetName)
        ) {
            return null
        }
        return copyResourceIcon(context, pluginId, pluginInfo, assetName, "")
    }

    /** manifest 顶层 icon 识别为图片文件名（常见图片扩展名）。 */
    private val IMAGE_EXTENSION_REGEX = Regex("(?i)\\.(png|jpe?g|gif|webp|svg)$")

    fun extractPluginManifestIcon(context: Context, pluginInfo: PluginInfo): PluginIcon? {
        val manifestIcon = pluginInfo.manifestIcon ?: return null
        if (!IMAGE_EXTENSION_REGEX.containsMatchIn(manifestIcon)) {
            return PluginIcon(text = manifestIcon)
        }
        return copyResourceIcon(context, pluginInfo.id, pluginInfo, manifestIcon, "tb_")
    }

    private fun copyResourceIcon(
        context: Context,
        pluginId: String,
        pluginInfo: PluginInfo?,
        assetName: String,
        suffix: String,
    ): PluginIcon? {
        if (!com.kingzcheung.xime.plugin.core.runtime.installer.InstallerManager
                .isValidResourcePath(assetName)
        ) {
            return null
        }
        val iconDir = File(context.filesDir, "plugin_icons")
        if (!iconDir.exists()) iconDir.mkdirs()
        val iconFile = File(iconDir, "${pluginId}_$suffix$assetName")
        if (!iconFile.exists()) {
            // Lua 插件资源在 resources/ 目录（path 指向入口脚本，其父目录为插件目录）
            val resourceFile = pluginInfo?.path
                ?.let { File(it).parentFile }
                ?.let { File(it, "resources/$assetName") }
            if (resourceFile != null && resourceFile.exists()) {
                try {
                    iconFile.parentFile?.mkdirs()
                    resourceFile.copyTo(iconFile, overwrite = true)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to extract icon for $pluginId", e)
                }
            }
        }
        return if (iconFile.exists()) PluginIcon(assetName = iconFile.absolutePath) else null
    }

    fun reload(context: Context): Boolean {
        return try {
            managerScope.launch {
                PluginManager.loadEnabledPlugins()
            }
            PluginManager.isInitialized
        } catch (e: Exception) {
            Log.e(TAG, "reload failed", e)
            false
        }
    }

    fun getAsrPlugins(): List<AsrPlugin> =
        PluginManager.getAllPluginInstances().values.mapNotNull { it as? AsrPlugin }

    fun getEnabledAsrPlugins(context: Context): List<Pair<String, AsrPlugin>> =
        getAsrPlugins().mapNotNull { plugin ->
            val pluginId = getPluginId(plugin)
            if (pluginId.isNotEmpty() && SettingsPreferences.isPluginEnabled(context, pluginId)) {
                Pair(pluginId, plugin)
            } else null
        }

    private fun getPluginId(plugin: Any): String =
        PluginManager.getAllPluginInstances().entries
            .firstOrNull { it.value === plugin }?.key ?: ""

    fun getAllInstalledPlugins(): List<PluginInfo> = PluginManager.getAllInstallPlugins()

    /** 插件配置中解析出的 HTTP(S) 域名（用户填写的自定义服务器地址），供网络授权 UI 展示。 */
    fun getConfiguredNetworkHosts(context: Context, pluginId: String): List<String> = try {
        PluginConfigStoreImpl(
            context.applicationContext as android.app.Application, pluginId
        ).keys().mapNotNull { key ->
            PluginConfigStoreImpl(
                context.applicationContext as android.app.Application, pluginId
            ).get(key)?.let { NetworkPolicy.extractHttpHost(it) }
        }.distinct()
    } catch (e: Exception) {
        Log.e(TAG, "getConfiguredNetworkHosts failed for $pluginId", e)
        emptyList()
    }

    fun getPluginsByCategory(category: PluginCategory): List<PluginInfo> =
        getAllInstalledPlugins().filter { it.category == category }

    fun getEnabledPluginsByCategory(context: Context, category: PluginCategory): List<PluginInfo> =
        getPluginsByCategory(category).filter { SettingsPreferences.isPluginEnabled(context, it.id) }

    fun getPluginById(id: String): IPluginEntryClass? = PluginManager.getPluginInstance(id)

    fun isInitialized(): Boolean = initialized && PluginManager.isInitialized

    /** 待授权的网络域名（插件声明 + 用户配置 + 运行时被拒），供设置页显眼展示。 */
    fun getUnauthorizedHosts(context: Context, pluginId: String): List<String> {
        val info = getAllInstalledPlugins().firstOrNull { it.id == pluginId } ?: return emptyList()
        val authorized = SettingsPreferences.getPluginAuthorizedHosts(context, pluginId)
        return (info.declaredHosts +
                getConfiguredNetworkHosts(context, pluginId) +
                SettingsPreferences.getPluginPendingHosts(context, pluginId))
            .distinct()
            .filter { it !in authorized }
    }

    fun release() {
        initialized = false
        managerJob.cancel()
    }
}
