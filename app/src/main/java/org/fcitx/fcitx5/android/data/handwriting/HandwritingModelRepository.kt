/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 手写模型市场/下载的进程级状态。
 *
 * 与 [org.fcitx.fcitx5.android.data.voice.VoiceModelRepository] 同构：
 * - 单例 + 应用级 CoroutineScope：退出设置页不会中断下载；
 * - 进度以 StateFlow 暴露，页面重新进入即恢复显示；
 * - 清单来自远程索引的 `category: handwriting` 条目（[HandwritingModelIndex]），
 *   索引不可用时回落内置清单（[HandwritingModelCatalog.builtin]）。
 */
package org.fcitx.fcitx5.android.data.handwriting

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

object HandwritingModelRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _models = MutableStateFlow<List<HandwritingModelInfo>>(emptyList())
    val models: StateFlow<List<HandwritingModelInfo>> = _models.asStateFlow()

    private val _loadingIndex = MutableStateFlow(false)
    val loadingIndex: StateFlow<Boolean> = _loadingIndex.asStateFlow()

    private val _indexError = MutableStateFlow<String?>(null)
    val indexError: StateFlow<String?> = _indexError.asStateFlow()

    private val _downloadState = MutableStateFlow<HandwritingModelState>(HandwritingModelState.Idle)
    val downloadState: StateFlow<HandwritingModelState> = _downloadState.asStateFlow()

    /** 正在下载（或下载失败等待重试）的模型 id。 */
    private val _downloadingId = MutableStateFlow<String?>(null)
    val downloadingId: StateFlow<String?> = _downloadingId.asStateFlow()

    /** 最近一次下载失败的原因（供页面显示）。 */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var downloadJob: Job? = null

    /** 加载/刷新索引；失败时保留上一次成功结果。 */
    fun refreshIndex(context: Context) {
        if (_loadingIndex.value) return
        _loadingIndex.value = true
        scope.launch {
            try {
                _models.value = HandwritingModelIndex.load(context.applicationContext)
                _indexError.value = null
            } catch (e: Exception) {
                Timber.w(e, "handwriting model index load failed")
                _indexError.value = e.message ?: "unknown"
                if (_models.value.isEmpty()) _models.value = HandwritingModelCatalog.builtin
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

    fun downloadModel(context: Context, model: HandwritingModelInfo) {
        if (downloadJob?.isActive == true) return
        val appContext = context.applicationContext
        _lastError.value = null
        _downloadingId.value = model.id
        _downloadState.value = HandwritingModelState.Downloading(0f, 0L, model.files.size.toLong())
        downloadJob = scope.launch {
            HandwritingModelDownloader.download(appContext, model) { state ->
                _downloadState.value = state
            }.onSuccess {
                _downloadingId.value = null
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
        _downloadState.value = HandwritingModelState.Idle
    }

    fun deleteModel(context: Context, model: HandwritingModelInfo): Boolean =
        HandwritingModelStore.delete(context.applicationContext, model.id)

    /** 「已下载」以能解析出 onnx + 字符索引为准（而不是索引里的文件名清单）。 */
    fun isDownloaded(context: Context, model: HandwritingModelInfo): Boolean =
        HandwritingModelStore.resolve(context.applicationContext, model.id) != null

    fun sizeOnDisk(context: Context, model: HandwritingModelInfo): Long =
        HandwritingModelStore.sizeOnDisk(context.applicationContext, model.id)
}
