/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 模型市场分类注册表 —— 让路由只用字符串 id，避免把分类对象塞进
 * `@Serializable` 的导航参数里。
 */
package org.fcitx.fcitx5.android.data.market

import org.fcitx.fcitx5.android.data.handwriting.HandwritingMarketCategory
import org.fcitx.fcitx5.android.data.voice.VoiceMarketCategory

object MarketCategories {

    /** 路由参数用的稳定分类 id（等于索引里的 `category` 值）。 */
    const val ASR = ModelIndex.CATEGORY_ASR
    const val HANDWRITING = ModelIndex.CATEGORY_HANDWRITING

    fun of(id: String): MarketCategory = when (id) {
        ASR -> VoiceMarketCategory
        HANDWRITING -> HandwritingMarketCategory
        else -> VoiceMarketCategory
    }
}
