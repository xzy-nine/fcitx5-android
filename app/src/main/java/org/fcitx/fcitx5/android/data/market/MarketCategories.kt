/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 模型市场分类注册表 —— 让路由只用字符串 id，避免把分类对象塞进
 * `@Serializable` 的导航参数里。
 */
package org.fcitx.fcitx5.android.data.market

import org.fcitx.fcitx5.android.data.handwriting.DigitalInkMarketCategory
import org.fcitx.fcitx5.android.data.voice.VoiceMarketCategory

object MarketCategories {

    /** 路由参数用的稳定分类 id（等于索引里的 `category` 值）。 */
    const val ASR = ModelIndex.CATEGORY_ASR

    /** 谷歌数字墨水语言模型（清单内置，不走远程索引）。 */
    const val DIGITAL_INK = ModelIndex.CATEGORY_DIGITAL_INK

    /** 全部分类（顺序 = 模型市场父页的展示顺序）。 */
    val all: List<MarketCategory> = listOf(VoiceMarketCategory, DigitalInkMarketCategory)

    fun of(id: String): MarketCategory = when (id) {
        DIGITAL_INK -> DigitalInkMarketCategory
        ASR -> VoiceMarketCategory
        else -> VoiceMarketCategory
    }

    /** 分类路由 id → 分类（HomeScreen 等入口用 `AppRoute.ModelMarket(id)`）。 */
    fun routeOf(category: MarketCategory): String = category.id
}
