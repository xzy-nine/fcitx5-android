/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.voice

import android.content.Context
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.plugin.PluginConfigStoreImpl
import com.kingzcheung.xime.plugin.crypto.CryptoHostApiImpl
import com.kingzcheung.xime.plugin.http.HttpHostApiImpl
import com.kingzcheung.xime.plugin.http.SseHostApiImpl
import com.kingzcheung.xime.plugin.ws.WsHostApiImpl
import com.kingzcheung.xime.plugin.core.api.AsrPlugin
import com.kingzcheung.xime.plugin.core.api.AsrPluginBackend
import com.kingzcheung.xime.plugin.core.api.AsrPluginListener
import com.kingzcheung.xime.plugin.core.api.AsrPluginState
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.speech.AsrBackend
import com.kingzcheung.xime.speech.AsrPluginHost
import com.kingzcheung.xime.speech.AsrPluginHostRegistry
import com.kingzcheung.xime.speech.RecognitionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.FcitxApplication
import timber.log.Timber

/**
 * custom: 在线 ASR 插件框架的宿主引导。
 *
 * 等价于 Xime 里 `XimeApplication` + `ExtensionManager` 做的接线，但只保留 ASR 所需部分：
 * 1. 为 [PluginManager] 注入宿主实现（配置存储 / WebSocket / HTTP / SSE / 加密）；
 * 2. 从 assets 安装随包内置的 Lua 插件（funasr-asr、volc-asr）；
 * 3. 把已启用的 ASR 插件适配成 [AsrPluginHost] 并注册进 [AsrPluginHostRegistry]，
 *    这样 speech 包（引擎装配）无需依赖插件框架。
 *
 * 首次调用发生在应用启动（[FcitxApplication]）或第一次打开语音页面时，重复调用无副作用。
 */
object VoicePluginBootstrap {

    private const val TAG = "VoicePluginBootstrap"

    /** 内置 Lua 插件的 assets 路径（.xipk 归档）。 */
    private val BUNDLED_PLUGINS = listOf(
        "plugins/funasr-asr.xipk",
        "plugins/volc-asr.xipk",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var started = false

    fun ensureStarted(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        val app = context.applicationContext
        scope.launch {
            try {
                PluginManager.configStoreFactory = PluginManager.PluginConfigStoreFactory { application, pluginId ->
                    PluginConfigStoreImpl(application, pluginId)
                }
                PluginManager.wsHostApiFactory = { pluginId -> WsHostApiImpl(app, pluginId) }
                PluginManager.httpHostApiFactory = { pluginId -> HttpHostApiImpl(app, pluginId) }
                PluginManager.sseHostApiFactory = { pluginId -> SseHostApiImpl(app, pluginId) }
                PluginManager.cryptoHostApiFactory = { CryptoHostApiImpl() }

                PluginManager.initialize(app as android.app.Application) {
                    installBundledPlugins(app)
                    PluginManager.loadEnabledPlugins()
                }
                PluginManager.awaitInitialization()
                ExtensionManager.initialize(app)

                registerAsrHosts()
            } catch (e: Exception) {
                Timber.e(e, "voice plugin bootstrap failed")
                started = false
            }
        }
    }

    /** 供设置页在展示插件列表前调用，确保插件已加载。 */
    fun ensureLoaded(context: Context) = ensureStarted(context)

    private suspend fun installBundledPlugins(context: Context) {
        BUNDLED_PLUGINS.forEach { path ->
            val ok = try {
                PluginManager.installPluginFromAssets(path)
            } catch (e: Exception) {
                Timber.e(e, "install bundled plugin failed: %s", path)
                false
            }
            if (ok) {
                Timber.i("bundled plugin installed: %s", path)
            } else {
                Timber.w(
                    "bundled plugin NOT installed: %s (asset missing or archive invalid?)", path
                )
            }
        }
    }

    private fun registerAsrHosts() {
        AsrPluginHostRegistry.provider = { context ->
            ExtensionManager.getEnabledAsrPlugins(context).map { (id, plugin) ->
                PluginAsrHost(id, plugin)
            }
        }
    }
}

/**
 * 把插件框架的 [AsrPlugin] 适配成 speech 包认识的 [AsrPluginHost]。
 *
 * 状态/结果映射与 Xime 的 `PluginAsrBackendAdapter` 等价：
 * `AsrPluginState` ↔ [RecognitionState]。
 */
private class PluginAsrHost(
    override val pluginId: String,
    private val plugin: AsrPlugin,
) : AsrPluginHost {

    override val displayName: String
        get() = ExtensionManager.getAllInstalledPlugins()
            .firstOrNull { it.id == pluginId }?.name ?: pluginId

    override fun isConfigured(context: Context): Boolean = try {
        plugin.isConfigured()
    } catch (e: Exception) {
        Timber.w(e, "plugin %s isConfigured failed", pluginId)
        false
    }

    override fun createBackend(context: Context): AsrBackend =
        PluginAsrBackendAdapter(plugin.createBackend(context))
}

/**
 * [AsrPluginBackend] → speech 包的 [AsrBackend] 适配器（等价 Xime 的 PluginAsrBackendAdapter）。
 */
private class PluginAsrBackendAdapter(
    private val delegate: AsrPluginBackend,
) : AsrBackend {

    override val name: String get() = "在线语音识别"

    private var resultCallback: ((String) -> Unit)? = null
    private var partialCallback: ((String) -> Unit)? = null
    private var stateCallback: ((RecognitionState) -> Unit)? = null
    private var errorCallback: ((String) -> Unit)? = null

    private val listener = object : AsrPluginListener {
        override fun onFinal(text: String) {
            resultCallback?.invoke(text)
        }

        override fun onPartial(text: String) {
            partialCallback?.invoke(text)
        }

        override fun onError(message: String) {
            errorCallback?.invoke(message)
        }

        override fun onStateChanged(state: AsrPluginState) {
            stateCallback?.invoke(state.toRecognitionState())
        }
    }

    override fun setCallbacks(
        onResult: (String) -> Unit,
        onPartialResult: ((String) -> Unit)?,
        onStateChange: (RecognitionState) -> Unit,
        onError: (String) -> Unit
    ) {
        resultCallback = onResult
        partialCallback = onPartialResult
        stateCallback = onStateChange
        errorCallback = onError
        delegate.setListener(listener)
    }

    override fun initialize(): Boolean = try {
        delegate.initialize()
    } catch (e: Exception) {
        Timber.e(e, "plugin backend initialize failed")
        false
    }

    override fun start(): Boolean = try {
        delegate.start()
    } catch (e: Exception) {
        Timber.e(e, "plugin backend start failed")
        false
    }

    override fun processAudioChunk(buffer: ByteArray) {
        try {
            delegate.processAudioChunk(buffer)
        } catch (e: Exception) {
            Timber.w(e, "plugin backend processAudioChunk failed")
        }
    }

    override fun stop() {
        try {
            delegate.stop()
        } catch (e: Exception) {
            Timber.w(e, "plugin backend stop failed")
        }
    }

    override fun cancel() {
        try {
            delegate.cancel()
        } catch (e: Exception) {
            Timber.w(e, "plugin backend cancel failed")
        }
    }

    override fun release() {
        try {
            delegate.release()
        } catch (e: Exception) {
            Timber.w(e, "plugin backend release failed")
        }
        resultCallback = null
        partialCallback = null
        stateCallback = null
        errorCallback = null
    }

    override fun isAvailable(): Boolean = true

    private fun AsrPluginState.toRecognitionState(): RecognitionState = when (this) {
        AsrPluginState.IDLE -> RecognitionState.IDLE
        AsrPluginState.LISTENING -> RecognitionState.LISTENING
        AsrPluginState.PROCESSING -> RecognitionState.PROCESSING
        AsrPluginState.ERROR -> RecognitionState.ERROR
    }
}
