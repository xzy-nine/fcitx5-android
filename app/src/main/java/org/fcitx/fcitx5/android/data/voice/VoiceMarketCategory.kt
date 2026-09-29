/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 语音模型市场分类（索引 `category: asr`）。
 */
package org.fcitx.fcitx5.android.data.voice

import android.content.Context
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.market.BaseMarketCategory
import org.fcitx.fcitx5.android.data.market.MarketModel
import org.fcitx.fcitx5.android.data.market.ModelIndex
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import java.io.File

object VoiceMarketCategory : BaseMarketCategory(ModelIndex.CATEGORY_ASR, R.string.voice_model_market) {

    override val indexCategory: String = ModelIndex.CATEGORY_ASR

    override val builtin: List<MarketModel> = VoiceModelCatalog.builtin

    override fun targetDir(context: Context, model: MarketModel): File =
        VoiceModelStore.modelDir(context, model.id)

    override fun isReady(context: Context, modelId: String): Boolean =
        VoiceModelStore.resolve(context, modelId) != null

    override fun selectedModelId(context: Context): String =
        AppPrefs.getInstance().voice.voiceAsrModelId.getValue()
            .ifBlank { VoiceModelCatalog.DEFAULT_ID }

    override fun selectModel(context: Context, model: MarketModel) {
        AppPrefs.getInstance().voice.voiceAsrModelId.setValue(model.id)
    }
}
