/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 模型市场分类的公共实现（索引加载 + 下载状态 + 删除/就绪判定入口）。
 *
 * 各分类（语音 / 手写）只声明：清单来源、待下载模型到本地目录的映射、内置兜底清单、
 * 以及「就绪判定」与「选中模型」两项分类语义；其余全部由本类复用，
 * 市场页 [org.fcitx.fcitx5.android.data.market.ModelMarketScreen] 只依赖 [MarketCategory]。
 */
package org.fcitx.fcitx5.android.data.market

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
import java.io.File

abstract class BaseMarketCategory(
    final override val id: String,
    final override val titleRes: Int,
) : MarketCategory {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _models = MutableStateFlow<List<MarketModel>>(emptyList())
    final override val models: StateFlow<List<MarketModel>> = _models.asStateFlow()

    private val _loadingIndex = MutableStateFlow(false)
    final override val loadingIndex: StateFlow<Boolean> = _loadingIndex.asStateFlow()

    private val _indexError = MutableStateFlow<String?>(null)
    final override val indexError: StateFlow<String?> = _indexError.asStateFlow()

    private val _downloadState = MutableStateFlow<MarketDownloadState>(MarketDownloadState.Idle)
    final override val downloadState: StateFlow<MarketDownloadState> = _downloadState.asStateFlow()

    private val _downloadingId = MutableStateFlow<String?>(null)
    final override val downloadingId: StateFlow<String?> = _downloadingId.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    final override val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var downloadJob: Job? = null

    /** 索引里的 category 值（与远端 `category` 字段一致）。 */
    protected abstract val indexCategory: String

    /** 索引不可用时的内置清单。 */
    protected abstract val builtin: List<MarketModel>

    /** 索引里请求的模型应落到的目录。 */
    protected abstract fun targetDir(context: Context, model: MarketModel): File

    /** 该模型在本地是否可用（由各分类定义自己的就绪条件）。 */
    abstract fun isReady(context: Context, modelId: String): Boolean

    final override fun isDownloaded(context: Context, model: MarketModel): Boolean =
        isReady(context, model.id)

    final override fun ensureIndexLoaded(context: Context) {
        if (_models.value.isNotEmpty() || _loadingIndex.value) return
        refreshIndex(context)
    }

    final override fun refreshIndex(context: Context) {
        if (_loadingIndex.value) return
        _loadingIndex.value = true
        scope.launch {
            try {
                val loaded = ModelIndex.load(context.applicationContext, indexCategory)
                if (loaded.isNotEmpty()) {
                    _models.value = loaded
                    _indexError.value = null
                } else {
                    _models.value = builtin
                }
            } catch (e: Exception) {
                Timber.w(e, "$id model index load failed")
                _indexError.value = e.message ?: "unknown"
                if (_models.value.isEmpty()) _models.value = builtin
            } finally {
                _loadingIndex.value = false
            }
        }
    }

    final override fun downloadModel(context: Context, model: MarketModel) {
        if (downloadJob?.isActive == true) return
        val appContext = context.applicationContext
        _lastError.value = null
        _downloadingId.value = model.id
        _downloadState.value = MarketDownloadState.Downloading(0f, 0L, 0L)
        downloadJob = scope.launch {
            ModelDownloader.download(appContext, model, targetDir(appContext, model)) { state ->
                _downloadState.value = state
            }.onSuccess {
                _downloadingId.value = null
            }.onFailure { e ->
                // 失败时保留 downloadingId，使卡片继续显示错误与「重试下载」
                _lastError.value = e.message ?: "下载失败"
            }
        }
    }

    final override fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _downloadingId.value = null
        _downloadState.value = MarketDownloadState.Idle
    }

    final override fun deleteModel(context: Context, model: MarketModel): Boolean {
        cancelDownload()
        return targetDir(context.applicationContext, model).deleteRecursively()
    }
}
