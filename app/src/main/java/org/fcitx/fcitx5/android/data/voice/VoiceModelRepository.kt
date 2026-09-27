/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.voice

import android.content.Context
import com.kingzcheung.xime.model.ModelDownloadState
import com.kingzcheung.xime.model.ModelInfo
import com.kingzcheung.xime.model.ModelManager
import com.kingzcheung.xime.speech.AsrModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * custom: 语音模型市场/下载的进程级状态。
 *
 * 设计（对应计划里的「关掉页面下载继续」要求）：
 * - 单例 + 应用级 [CoroutineScope]：切走 IME 面板或退出设置页不会中断下载；
 * - 进度以 [StateFlow] 暴露，页面重新进入即恢复显示；
 * - 模型清单来自 Xime 的远程索引（[ModelManager.loadFromRemote]，地址取
 *   `xime.yaml` 的 `xime_index.base_urls`，可在设置里用 `voice_index_url` 覆盖），
 *   索引不可用时回落到内置默认模型（[AsrModelManager.DEFAULT_MODEL]）。
 *
 * 不做 WorkManager/前台服务：与仓库既有做法（AutoDictSync 等）保持一致。
 */
object VoiceModelRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _models = MutableStateFlow<List<ModelInfo>>(emptyList())
    val models: StateFlow<List<ModelInfo>> = _models.asStateFlow()

    private val _loadingIndex = MutableStateFlow(false)
    val loadingIndex: StateFlow<Boolean> = _loadingIndex.asStateFlow()

    private val _indexError = MutableStateFlow<String?>(null)
    val indexError: StateFlow<String?> = _indexError.asStateFlow()

    private val _downloadState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)
    val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

    /** 正在下载的模型 id（用于列表里定位进度条）。 */
    private val _downloadingId = MutableStateFlow<String?>(null)
    val downloadingId: StateFlow<String?> = _downloadingId.asStateFlow()

    private var downloadJob: Job? = null

    /** 加载/刷新远程索引；失败时保留上一次成功结果。 */
    fun refreshIndex(context: Context) {
        if (_loadingIndex.value) return
        _loadingIndex.value = true
        scope.launch {
            try {
                ModelManager.loadFromRemote(context.applicationContext)
                _indexError.value = null
            } catch (e: Exception) {
                Timber.w(e, "voice model index load failed")
                _indexError.value = e.message ?: "unknown"
            } finally {
                _models.value = ModelManager.getAllModels()
                _loadingIndex.value = false
            }
        }
    }

    /** 确保索引至少尝试加载过一次。 */
    fun ensureIndexLoaded(context: Context) {
        if (_models.value.isNotEmpty() || _loadingIndex.value) return
        refreshIndex(context)
    }

    fun downloadModel(context: Context, model: ModelInfo) {
        if (downloadJob?.isActive == true) return
        val appContext = context.applicationContext
        _downloadingId.value = model.id
        _downloadState.value = ModelDownloadState.Downloading(0f, 0L, 0L)
        downloadJob = scope.launch {
            try {
                ModelManager.downloadModel(appContext, model, onProgress = { state ->
                    _downloadState.value = state
                })
            } catch (e: Exception) {
                Timber.e(e, "voice model download failed")
                _downloadState.value = ModelDownloadState.Error(e.message ?: "download failed")
            } finally {
                _downloadingId.value = null
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _downloadingId.value = null
        _downloadState.value = ModelDownloadState.Idle
    }

    fun deleteModel(context: Context, model: ModelInfo): Boolean =
        ModelManager.deleteModel(context.applicationContext, model)

    fun isDownloaded(context: Context, model: ModelInfo): Boolean =
        ModelManager.isModelDownloaded(context.applicationContext, model)

    fun sizeOnDisk(context: Context, model: ModelInfo): Long =
        ModelManager.getModelSizeOnDisk(context.applicationContext, model.id)
}
