/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.data.market

import org.fcitx.fcitx5.android.data.handwriting.DigitalInkMarketCategory
import org.fcitx.fcitx5.android.data.voice.VoiceMarketCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketCategoriesTest {

    @Test
    fun `of returns the registered category by id`() {
        assertEquals(VoiceMarketCategory, MarketCategories.of(MarketCategories.ASR))
        assertEquals(DigitalInkMarketCategory, MarketCategories.of(MarketCategories.DIGITAL_INK))
    }

    @Test
    fun `unknown route id falls back to voice market`() {
        assertEquals(VoiceMarketCategory, MarketCategories.of("unknown"))
    }

    @Test
    fun `routeOf returns the category id`() {
        assertEquals(MarketCategories.ASR, MarketCategories.routeOf(VoiceMarketCategory))
        assertEquals(MarketCategories.DIGITAL_INK, MarketCategories.routeOf(DigitalInkMarketCategory))
    }

    @Test
    fun `all lists both categories in display order`() {
        assertEquals(2, MarketCategories.all.size)
        assertEquals(VoiceMarketCategory, MarketCategories.all[0])
        assertEquals(DigitalInkMarketCategory, MarketCategories.all[1])
    }
}