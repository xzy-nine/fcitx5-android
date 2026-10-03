/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 模型市场 —— 按分类泛化的**公共组件**（目前唯一分类是语音 `asr`）。
 *
 * 分层：
 * - 本文件：市场的数据类型（清单条目、下载状态）；
 * - [ModelIndex]：从远程索引 `models/index.yaml` 按 **category** 过滤出本分类的条目，
 *   索引不可用时回落各分类的内置清单；
 * - [ModelMarketScreen]：唯一的市场页 UI；
 * - [MarketCategory]：各分类的实现（谁提供清单、谁负责下载/删除/就绪判定）。
 *
 * 新增分类只需实现 [MarketCategory] 并在 [MarketCategories] 注册，页面无需改动。
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
 * 一个模型分类（语音 `asr` / 谷歌数字墨水 `digitalink`）需要提供给市场页的全部能力。
 *
 * 实现方持有自己的清单、下载器与本地存储；市场页（父级索引页 + 分类子页）只依赖这个接口，
 * 因此新增分类只需再写一个实现 + 在 [MarketCategories] 注册一行。
 */
interface MarketCategory {

    /** 稳定的分类 id（用于路由参数，禁止随意改名）。 */
    val id: String

    /** 清单是否来自远程索引（否则为内置清单；市场页据此决定要不要显示索引地址输入框）。 */
    val usesRemoteIndex: Boolean get() = true

    /**
     * 清单是否按**语言变体**折叠展示（同一语言的地区变体收进一个可展开的分组）。
     *
     * 数字墨水有 361 条语言（`en`/`en-AU`/`en-GB`…）故打开；语音模型是不同尺寸的模型、
     * 不是同一事物的变体，保持平铺。折叠逻辑见 [MarketModelGrouping]。
     */
    val groupsByLanguageVariant: Boolean get() = false

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

    /**
     * 状态修订号：**任何会影响列表展示的变化**（清单加载完、下载/删除完成、选中项变化、
     * 后台刷新到新的「已下载」状态）都要递增，市场页据此重组（否则同步查询的
     * [isDownloaded]/[selectedModelId] 会一直显示旧值）。
     */
    val revision: StateFlow<Int>

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

    /** 已下载数量（父级「模型市场」页的摘要行用）。 */
    fun downloadedCount(context: Context): Int =
        models.value.count { isDownloaded(context, it) }

    /**
     * 当前系统（应用）语言对应的**基础语言键**集合（[MarketModelGrouping.baseTag] 口径）。
     *
     * 市场页据此把该语言**及其全部地区变体**排到最前：系统是 `zh-Hans-CN` 时数字墨水给出
     * `zh-Hani`（于是 `zh-Hani` / `zh-Hani-CN` / `-HK` / `-TW` 整组前置）。
     * 返回空集 = 不额外提权。
     */
    fun preferredLanguageKeys(context: Context): Set<String> = emptySet()

    /**
     * **清单之外**的「直接下载」条目：官方渠道按 tag 能取到、但内置清单未收录的模型。
     *
     * 市场页把它们固定在最前面独立成行，且**照样走本分类的下载/删除/选中**
     * （数字墨水 = 直接向 Google 请求该语言的模型，见 `DigitalInkMarketCategory`）。
     * 返回空表 = 没有这类条目。
     */
    fun directDownloadModels(context: Context): List<MarketModel> = emptyList()

    /**
     * 重查「已下载」状态并触发一次重组（父级市场页返回时调用）。
     *
     * 与 [refreshIndex] 不同：**不发网络请求**，只让本地就绪判定重新生效
     * （数字墨水要重新问一次 ML Kit，语音/文件类只需重看文件是否存在）。
     */
    fun refreshDownloadedState(context: Context)
}
