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

    /** 子类可用来跑自己的异步动作（如数字墨水走 ML Kit 下载）。 */
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    private val _revision = MutableStateFlow(0)
    final override val revision: StateFlow<Int> = _revision.asStateFlow()

    // protected：子类（数字墨水走 ML Kit 下载）需按同一 Job 语义做取消安全的进度上报
    protected var downloadJob: Job? = null

    /** 见 [MarketCategory.revision]（子类在状态变化后调用）。 */
    protected fun bumpRevision() {
        _revision.value += 1
    }

    protected fun setModels(models: List<MarketModel>) {
        _models.value = models
        bumpRevision()
    }

    protected fun setIndexError(message: String?) {
        _indexError.value = message
        bumpRevision()
    }

    protected fun setDownloadState(state: MarketDownloadState) {
        _downloadState.value = state
        bumpRevision()
    }

    protected fun setDownloadingId(id: String?) {
        _downloadingId.value = id
        bumpRevision()
    }

    protected fun setLastError(message: String?) {
        _lastError.value = message
        bumpRevision()
    }

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

    /** 刷新清单；默认实现拉远程索引，分类可覆写成「只用内置清单」。 */
    override fun refreshIndex(context: Context) {
        if (_loadingIndex.value) return
        _loadingIndex.value = true
        scope.launch {
            try {
                val loaded = ModelIndex.load(context.applicationContext, indexCategory)
                setModels(loaded.ifEmpty { builtin })
                setIndexError(null)
            } catch (e: Exception) {
                Timber.w(e, "$id model index load failed")
                setIndexError(e.message ?: "unknown")
                if (_models.value.isEmpty()) setModels(builtin)
            } finally {
                _loadingIndex.value = false
                bumpRevision()
            }
        }
    }

    /** 下载一个模型；默认实现是「文件下载器」，分类可覆写（数字墨水走 ML Kit）。 */
    override fun downloadModel(context: Context, model: MarketModel) {
        if (downloadJob?.isActive == true) return
        val appContext = context.applicationContext
        setLastError(null)
        setDownloadingId(model.id)
        setDownloadState(MarketDownloadState.Downloading(0f, 0L, 0L))
        downloadJob = scope.launch {
            val job = coroutineContext[Job]
            ModelDownloader.download(appContext, model, targetDir(appContext, model)) { state ->
                // 已被取消或已有更新的下载接管时，旧回调不得再改状态
                if (downloadJob !== job) return@download
                setDownloadState(state)
            }.onSuccess {
                if (downloadJob !== job) return@onSuccess
                setDownloadingId(null)
            }.onFailure { e ->
                if (downloadJob !== job) return@onFailure
                // 失败时保留 downloadingId，使卡片继续显示错误与「重试下载」
                setLastError(e.message ?: "下载失败")
            }
        }
    }

    final override fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        setDownloadingId(null)
        setDownloadState(MarketDownloadState.Idle)
    }

    /** 删除一个模型；默认实现删除 [targetDir]，分类可覆写（数字墨水走 ML Kit）。 */
    override fun deleteModel(context: Context, model: MarketModel): Boolean {
        cancelDownload()
        val deleted = targetDir(context.applicationContext, model).deleteRecursively()
        bumpRevision()
        return deleted
    }

    /** 默认只递增修订号（文件类就绪判定是同步查文件，列表会自然重算）。 */
    override fun refreshDownloadedState(context: Context) {
        bumpRevision()
    }
}
