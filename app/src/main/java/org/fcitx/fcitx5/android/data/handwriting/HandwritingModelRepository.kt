/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 手写模型市场的便捷入口（转发到公共市场组件里的手写分类）。
 *
 * 真正的实现（清单/下载状态/删除）在 [HandwritingMarketCategory]；
 * 模型下载与删除由市场公共组件 `ModelDownloader` 完成。
 */
package org.fcitx.fcitx5.android.data.handwriting

import android.content.Context
import org.fcitx.fcitx5.android.data.market.MarketModel

object HandwritingModelRepository {

    private val category = HandwritingMarketCategory

    val models = category.models

    val loadingIndex = category.loadingIndex

    val indexError = category.indexError

    val downloadState = category.downloadState

    val downloadingId = category.downloadingId

    val lastError = category.lastError

    fun refreshIndex(context: Context) = category.refreshIndex(context)

    fun ensureIndexLoaded(context: Context) = category.ensureIndexLoaded(context)

    fun downloadModel(context: Context, model: MarketModel) =
        category.downloadModel(context, model)

    fun cancelDownload() = category.cancelDownload()

    fun deleteModel(context: Context, model: MarketModel): Boolean =
        category.deleteModel(context, model)

    fun isDownloaded(context: Context, model: MarketModel): Boolean =
        category.isDownloaded(context, model)

    fun selectedModelId(context: Context): String = category.selectedModelId(context)

    fun selectModel(context: Context, model: MarketModel) = category.selectModel(context, model)
}
