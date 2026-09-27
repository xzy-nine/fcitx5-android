/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Kingz Cheung
 *
 * 移植自 Xime (https://github.com/ximeiorg/xime)，见仓库根 NOTICE.md。
 */
package com.kingzcheung.xime.plugin.core.runtime

import android.app.Application
import android.util.Log
import com.kingzcheung.xime.plugin.core.api.IPluginEntryClass
import com.kingzcheung.xime.plugin.core.config.NoopPluginConfigStore
import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.model.InitState
import com.kingzcheung.xime.plugin.core.model.PluginFrameworkContext
import com.kingzcheung.xime.plugin.core.model.PluginInfo
import com.kingzcheung.xime.plugin.core.model.PluginSource
import com.kingzcheung.xime.plugin.core.runtime.loader.LoadedPluginInfo
import com.kingzcheung.xime.plugin.core.security.PluginCrashHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

object PluginManager {

    private const val TAG = "PluginManager"

    fun interface PluginConfigStoreFactory {
        fun create(application: Application, pluginId: String): PluginConfigStore
    }

    @Volatile
    var configStoreFactory: PluginConfigStoreFactory =
        PluginConfigStoreFactory { _, _ -> NoopPluginConfigStore }

    /**
     * 宿主 WebSocket 白名单 API 提供者（app 层注入，按插件创建以便做域名/授权校验）。
     * 工厂参数为插件 id，宿主据此查询插件声明的域名与用户授权。
     */
    @Volatile
    var wsHostApiFactory: ((pluginId: String) -> com.kingzcheung.xime.plugin.core.lua.ws.WsHostApi)? = null

    /**
     * 宿主 HTTP 白名单 API 提供者（app 层注入，剪贴板同步等插件使用）。
     * 工厂参数为插件 id，宿主据此校验域名白名单与用户授权。
     */
    @Volatile
    var httpHostApiFactory: ((pluginId: String) -> com.kingzcheung.xime.plugin.core.lua.http.HttpHostApi)? = null

    /**
     * 宿主 SSE 流式 HTTP 白名单 API 提供者（app 层注入，AI 长文本流式生成等插件使用）。
     * 工厂参数为插件 id，宿主据此校验域名白名单与用户授权（同 httpHostApiFactory）。
     */
    @Volatile
    var sseHostApiFactory: ((pluginId: String) -> com.kingzcheung.xime.plugin.core.lua.http.SseHostApi)? = null

    /** 宿主加密/编码原语提供者（S3 SigV4 签名等，app 层注入）。 */
    @Volatile
    var cryptoHostApiFactory: (() -> com.kingzcheung.xime.plugin.core.lua.crypto.CryptoHostApi)? = null


    private var frameworkContext: PluginFrameworkContext? = null
    private val _loadedPluginsFlow = MutableStateFlow<Map<String, LoadedPluginInfo>>(emptyMap())
    private val _pluginInstancesFlow = MutableStateFlow<Map<String, IPluginEntryClass>>(emptyMap())

    val initStateFlow: StateFlow<InitState>
        get() = requireContext().initState

    val loadedPluginsFlow: StateFlow<Map<String, LoadedPluginInfo>>
        get() = _loadedPluginsFlow

    val pluginInstancesFlow: StateFlow<Map<String, IPluginEntryClass>>
        get() = _pluginInstancesFlow

    val isInitialized: Boolean
        get() = frameworkContext?.initState?.value == InitState.INITIALIZED

    val installerManager: com.kingzcheung.xime.plugin.core.runtime.installer.InstallerManager
        get() = requireContext().installerManager

    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun requireContext(): PluginFrameworkContext {
        return frameworkContext
            ?: throw IllegalStateException("PluginManager has not been initialized.")
    }

    @Synchronized
    fun initialize(
        context: Application,
        onSetup: (suspend () -> Unit)? = null
    ) {
        if (frameworkContext != null && frameworkContext?.initState?.value != InitState.NOT_INITIALIZED) {
            Log.d(TAG, "Already initialized, skipping")
            return
        }

        Log.d(TAG, "Starting initialization...")
        PluginCrashHandler.initialize(context)
        frameworkContext = PluginFrameworkContext(context)
        
        requireContext().initState.value = InitState.INITIALIZING

        requireContext().initializeLifecycleManager()
        requireContext().initState.value = InitState.INITIALIZED
        Log.d(TAG, "Framework initialized")

        managerScope.launch {
            try {
                Log.d(TAG, "Executing onSetup asynchronously...")
                onSetup?.invoke()
                updateFlows()
                Log.d(TAG, "onSetup completed successfully")
            } catch (e: Exception) {
                Log.e(TAG, "onSetup failed", e)
            }
        }
        Log.d(TAG, "Initialization complete (onSetup running in background)")
    }

    private fun updateFlows() {
        _loadedPluginsFlow.value = requireContext().loadedPlugins.toMap()
        _pluginInstancesFlow.value = requireContext().pluginInstances.toMap()
        Log.d(TAG, "Flows updated: ${_pluginInstancesFlow.value.size} instances")
    }

    suspend fun awaitInitialization() {
        if (isInitialized) return
        initStateFlow.first { it == InitState.INITIALIZED }
    }

    suspend fun launchPlugin(pluginId: String): Boolean {
        val result = requireContext().lifecycleManager.launchPlugin(pluginId)
        updateFlows()
        return result
    }

    suspend fun unloadPlugin(pluginId: String) {
        requireContext().lifecycleManager.unloadPlugin(pluginId)
        updateFlows()
    }

    suspend fun loadEnabledPlugins(): Int {
        Log.d(TAG, "loadEnabledPlugins called")
        val result = requireContext().lifecycleManager.loadEnabledPlugins()
        updateFlows()
        Log.d(TAG, "loadEnabledPlugins result: $result")
        return result
    }

    fun getPluginInstance(pluginId: String): IPluginEntryClass? {
        return requireContext().pluginInstances[pluginId]
    }

    fun getPluginInfo(pluginId: String): LoadedPluginInfo? {
        return requireContext().loadedPlugins[pluginId]
    }

    fun getAllPluginInstances(): Map<String, IPluginEntryClass> {
        return requireContext().pluginInstances.toMap()
    }

    fun getAllInstallPlugins(): List<PluginInfo> {
        return requireContext().xmlManager.getAllPlugins()
    }

    /**
     * 向所有运行实例广播下行事件。
     * 实际投递取决于插件是否声明了对应 `capabilities.events`，未声明/未启用通道的实例静默跳过。
     *
     * @return 成功投递（进入通道）的实例数。
     */
    fun dispatchEvent(event: com.kingzcheung.xime.plugin.core.lua.PluginEvent): Int {
        val context = frameworkContext ?: return 0
        var delivered = 0
        for ((_, loaded) in context.loadedPlugins) {
            val runtime = loaded.script ?: continue
            if (runtime.dispatchEvent(event)) delivered++
        }
        return delivered
    }

    /** 判断插件是否兼容当前主应用版本（宿主侧读取自身版本）。 */
    fun isPluginHostCompatible(plugin: PluginInfo): Boolean {
        val hostVersion = com.kingzcheung.xime.plugin.core.util.VersionUtil.getHostVersionName(requireContext().application)
        return com.kingzcheung.xime.plugin.core.util.VersionUtil.isHostSupported(
            hostVersion ?: "", plugin.minHostVersion, plugin.maxHostVersion
        )
    }

    suspend fun setPluginEnabled(pluginId: String, enabled: Boolean): Boolean {
        return try {
            val pluginInfo = requireContext().xmlManager.getPluginById(pluginId) ?: return false
            if (pluginInfo.enabled == enabled) return true
            val updatedPluginInfo = pluginInfo.copy(enabled = enabled)
            requireContext().xmlManager.updatePlugin(updatedPluginInfo)
            requireContext().xmlManager.flushToDisk()
            if (!enabled) {
                // 禁用时同步卸载运行中实例，避免插件继续后台运行（网络会话/剪贴板监听）
                unloadPlugin(pluginId)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun installPluginFromAssets(assetsPath: String, forceOverwrite: Boolean = true): Boolean {
        Log.d(TAG, "installPluginFromAssets: $assetsPath")
        return try {
            val context = requireContext().application
            val pluginFile = File(context.cacheDir, "temp_plugin.xipk")
            context.assets.open(assetsPath).use { input ->
                pluginFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            val result = installerManager.installPlugin(pluginFile, forceOverwrite, source = PluginSource.ASSET)
            pluginFile.delete()
            val success = result is com.kingzcheung.xime.plugin.core.runtime.installer.InstallerManager.InstallResult.Success
            Log.d(TAG, "installPluginFromAssets result: $success")
            success
        } catch (e: Exception) {
            Log.e(TAG, "installPluginFromAssets failed", e)
            false
        }
    }
}