/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 模型市场 —— 语音与手写共用的**公共组件**。
 *
 * 分层：
 * - 本文件：市场的数据类型（清单条目、下载状态）；
 * - [ModelIndex]：从远程索引 `models/index.yaml` 按 **category** 过滤出本分类的条目
 *   （语音 `asr` / 手写 `handwriting`），索引不可用时回落各分类的内置清单；
 * - [ModelMarketScreen]：唯一的市场页 UI；
 * - [MarketCategory]：各分类的实现（谁提供清单、谁负责下载/删除/就绪判定）。
 *
 * 因此「语音模型市场」与「手写模型市场」是同一个组件传不同 category，而不是两份页面。
 */
package org.fcitx.fcitx5.android.data.market

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/** 清单里的一个可下载文件。 */
data class MarketModelFile(
    val name: String,
    val url: String,
    /** sha256 校验值（索引未提供时为空 = 不校验）。 */
    val sha256: String = "",
)

/** 一个可下载的模型（各分类共用同一条清单结构）。 */
data class MarketModel(
    val id: String,
    val name: String,
    val description: String = "",
    /** 人类可读体积（索引里给的是字符串，如 "6.7 MB"）。 */
    val size: String = "",
    val version: String = "",
    /** tar.bz2 归档地址；为空时按 [files] 逐个下载。 */
    val archiveUrl: String? = null,
    val files: List<MarketModelFile> = emptyList(),
)

sealed class MarketDownloadState {
    data object Idle : MarketDownloadState()

    data class Downloading(
        val progress: Float,
        val bytesDownloaded: Long,
        val totalBytes: Long,
    ) : MarketDownloadState()

    /** tar.bz2 解压（bzip2 是单线程 CPU 密集操作，需单独上报，避免看起来卡住）。 */
    data class Extracting(val progress: Float) : MarketDownloadState()

    data class Error(val message: String) : MarketDownloadState()
    data object Complete : MarketDownloadState()
}

/**
 * 一个模型分类（语音 / 手写）需要提供给市场页的全部能力。
 *
 * 实现方持有自己的清单、下载器与本地存储；市场页只依赖这个接口，
 * 因此新增分类（如后续的联想词模型）只需再写一个实现 + 注册一行。
 */
interface MarketCategory {

    /** 稳定的分类 id（用于路由参数，禁止随意改名）。 */
    val id: String

    /** 分类展示名（资源 id）。 */
    val titleRes: Int

    /** 当前清单（远程索引结果；索引失败时由实现方回落到内置清单）。 */
    val models: StateFlow<List<MarketModel>>

    val loadingIndex: StateFlow<Boolean>
    val indexError: StateFlow<String?>

    val downloadState: StateFlow<MarketDownloadState>

    /** 正在下载（或下载失败等待重试）的模型 id。 */
    val downloadingId: StateFlow<String?>

    /** 最近一次下载失败原因。 */
    val lastError: StateFlow<String?>

    /** 当前选中（在用）的模型 id。 */
    fun selectedModelId(context: Context): String

    fun ensureIndexLoaded(context: Context)

    fun refreshIndex(context: Context)

    fun downloadModel(context: Context, model: MarketModel)

    fun cancelDownload()

    fun deleteModel(context: Context, model: MarketModel): Boolean

    /** 「已下载」以引擎真能解析出模型文件为准，而不是索引里的文件名清单。 */
    fun isDownloaded(context: Context, model: MarketModel): Boolean

    /** 选中为当前模型。 */
    fun selectModel(context: Context, model: MarketModel)
}
