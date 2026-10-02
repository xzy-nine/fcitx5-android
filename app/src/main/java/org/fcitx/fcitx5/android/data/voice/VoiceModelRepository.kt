/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.voice

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import timber.log.Timber

/**
 * custom: 语音模型市场/下载的进程级状态。
 *
 * 设计（对应「关掉页面下载继续」要求）：
 * - 单例 + 应用级 [CoroutineScope]：切走 IME 面板或退出设置页不会中断下载；
 * - 进度以 [StateFlow] 暴露，页面重新进入即恢复显示；
 * - 清单来自远程索引（[VoiceModelIndex]，默认 `https://index.ximei.me/`，可在设置里用
 *   `voice_index_url` 覆盖），索引不可用时回落到内置的官方 sherpa-onnx 模型清单
 *   （[VoiceModelCatalog.builtin]）。
 */
object VoiceModelRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _models = MutableStateFlow<List<VoiceModelInfo>>(emptyList())
    val models: StateFlow<List<VoiceModelInfo>> = _models.asStateFlow()

    private val _loadingIndex = MutableStateFlow(false)
    val loadingIndex: StateFlow<Boolean> = _loadingIndex.asStateFlow()

    private val _indexError = MutableStateFlow<String?>(null)
    val indexError: StateFlow<String?> = _indexError.asStateFlow()

    private val _downloadState = MutableStateFlow<VoiceModelDownloadState>(
        VoiceModelDownloadState.Idle
    )
    val downloadState: StateFlow<VoiceModelDownloadState> = _downloadState.asStateFlow()

    /** 正在下载（或下载失败等待重试）的模型 id。 */
    private val _downloadingId = MutableStateFlow<String?>(null)
    val downloadingId: StateFlow<String?> = _downloadingId.asStateFlow()

    /** 最近一次下载失败的原因（供页面显示）。 */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /**
     * 文件系统版本号：删除或下载完成后递增。
     * 页面收集它来让 `isDownloaded` 等「读磁盘」的派生值重新计算。
     */
    private val _modelsVersion = MutableStateFlow(0)
    val modelsVersion: StateFlow<Int> = _modelsVersion.asStateFlow()

    private var downloadJob: Job? = null

    /** 加载/刷新索引；失败时保留上一次成功结果。 */
    fun refreshIndex(context: Context) {
        if (_loadingIndex.value) return
        _loadingIndex.value = true
        scope.launch {
            try {
                _models.value = VoiceModelIndex.load(context.applicationContext)
                _indexError.value = null
            } catch (e: Exception) {
                Timber.w(e, "voice model index load failed")
                _indexError.value = e.message ?: "unknown"
                if (_models.value.isEmpty()) _models.value = VoiceModelCatalog.builtin
            } finally {
                _loadingIndex.value = false
            }
        }
    }

    /** 确保索引至少尝试加载过一次。 */
    fun ensureIndexLoaded(context: Context) {
        if (_models.value.isNotEmpty() || _loadingIndex.value) return
        refreshIndex(context)
    }

    fun downloadModel(context: Context, model: VoiceModelInfo) {
        if (downloadJob?.isActive == true) return
        val appContext = context.applicationContext
        _lastError.value = null
        _downloadingId.value = model.id
        _downloadState.value = VoiceModelDownloadState.Downloading(0f, 0L, 0L)
        downloadJob = scope.launch {
            VoiceModelDownloader.download(appContext, model) { state ->
                _downloadState.value = state
            }.onSuccess {
                _downloadingId.value = null
                _modelsVersion.value += 1
            }.onFailure { e ->
                // 失败时保留 downloadingId，使卡片继续显示错误与「重试下载」
                _lastError.value = e.message ?: "下载失败"
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _downloadingId.value = null
        _downloadState.value = VoiceModelDownloadState.Idle
    }

    fun deleteModel(context: Context, model: VoiceModelInfo): Boolean {
        // 正在下载的模型不能删（临时目录/半成品会被并发读写）
        if (_downloadingId.value == model.id) return false
        // 正在使用的模型不能删
        if (model.id == AppPrefs.getInstance().voice.voiceAsrModelId.getValue()) return false
        val deleted = VoiceModelStore.delete(context.applicationContext, model.id)
        if (deleted) _modelsVersion.value += 1
        return deleted
    }

    /** 「已下载」以引擎可解析出 4 个模型文件为准（而不是索引里的文件名清单）。 */
    fun isDownloaded(context: Context, model: VoiceModelInfo): Boolean =
        VoiceModelStore.resolve(context.applicationContext, model.id) != null

    fun sizeOnDisk(context: Context, model: VoiceModelInfo): Long =
        VoiceModelStore.sizeOnDisk(context.applicationContext, model.id)
}
