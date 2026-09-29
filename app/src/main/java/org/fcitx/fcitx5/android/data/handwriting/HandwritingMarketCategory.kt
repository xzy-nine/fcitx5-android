/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 手写模型市场分类（索引 `category: handwriting`）。
 *
 * 与 [org.fcitx.fcitx5.android.data.voice.VoiceMarketCategory] 共用同一个市场页与下载器，
 * 区别只有：索引分类、内置清单、本地就绪判定（onnx + 字符索引）。
 */
package org.fcitx.fcitx5.android.data.handwriting

import android.content.Context
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.market.BaseMarketCategory
import org.fcitx.fcitx5.android.data.market.MarketModel
import org.fcitx.fcitx5.android.data.market.ModelIndex
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import java.io.File

object HandwritingMarketCategory :
    BaseMarketCategory(ModelIndex.CATEGORY_HANDWRITING, R.string.handwriting_model_market) {

    override val indexCategory: String = ModelIndex.CATEGORY_HANDWRITING

    override val builtin: List<MarketModel> = HandwritingModelCatalog.builtin

    override fun targetDir(context: Context, model: MarketModel): File =
        HandwritingModelStore.modelDir(context, model.id)

    override fun isReady(context: Context, modelId: String): Boolean =
        HandwritingModelStore.isReady(context, modelId)

    override fun selectedModelId(context: Context): String =
        AppPrefs.getInstance().handwriting.handwritingModelId.getValue()
            .ifBlank { HandwritingModelCatalog.DEFAULT_ID }

    override fun selectModel(context: Context, model: MarketModel) {
        AppPrefs.getInstance().handwriting.handwritingModelId.setValue(model.id)
    }
}
