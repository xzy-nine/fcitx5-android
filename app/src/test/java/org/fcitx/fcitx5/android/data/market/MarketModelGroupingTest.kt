/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.data.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketModelGroupingTest {

    private fun model(id: String) = MarketModel(id = id, name = id)

    @Test
    fun `base tag keeps language and script only`() {
        // 地区变体（2 字母 / 3 数字）都归到语言
        assertEquals("en", MarketModelGrouping.baseTag("en-AU"))
        // UN M.49 三位数字地区码（002 = Africa）同样归到语言
        assertEquals("fr", MarketModelGrouping.baseTag("fr-002"))
        assertEquals("en", MarketModelGrouping.baseTag("en"))
        // 书写系统（4 字母）保留，避免 bn 与 bn-Latn 混成一组
        assertEquals("zh-Hani", MarketModelGrouping.baseTag("zh-Hani-CN"))
        assertEquals("zh-Hani", MarketModelGrouping.baseTag("zh-Hani"))
        assertEquals("ber-Latn", MarketModelGrouping.baseTag("ber-Latn"))
        // 异常输入不崩
        assertEquals("", MarketModelGrouping.baseTag(""))
    }

    @Test
    fun `variants of the same language fold into one group`() {
        val groups = MarketModelGrouping.group(
            models = listOf(model("en"), model("en-AU"), model("en-GB"), model("ja")),
            rank = { 0 },
            nameOf = { it },
        )
        val en = groups.single { it.key == "en" }
        assertEquals(listOf("en", "en-AU", "en-GB"), en.variants.map { it.id })
        assertTrue(en.foldable)
        // 单成员（ja）不成组：直接平铺
        assertFalse(groups.single { it.key == "ja" }.foldable)
    }

    @Test
    fun `groups keep the catalog order and unknown base falls back to the tag`() {
        val groups = MarketModelGrouping.group(
            models = listOf(model("zu"), model("zh-Hani"), model("en"), model("en-US")),
            rank = { 0 },
            nameOf = { tag -> if (tag == "zh-Hani") "Chinese, Han script." else tag },
        )
        // 同权重保持清单原顺序（稳定排序）
        assertEquals(listOf("zu", "zh-Hani", "en"), groups.map { it.key })
        assertEquals("Chinese, Han script.", groups.single { it.key == "zh-Hani" }.title)
        assertEquals("zu", groups.single { it.key == "zu" }.title)
    }

    @Test
    fun `downloaded and selected variants rank first within and across groups`() {
        val rank: (MarketModel) -> Int = {
            when (it.id) {
                "en-US" -> 2 // 使用中
                "ja" -> 1 // 已下载
                else -> 0
            }
        }
        val groups = MarketModelGrouping.group(
            models = listOf(model("en"), model("en-US"), model("ja"), model("zu")),
            rank = rank,
            nameOf = { it },
        )
        // 组间：含使用中的 en 组第一，含已下载的 ja 第二
        assertEquals(listOf("en", "ja", "zu"), groups.map { it.key })
        // 组内：使用中的变体排在基础语言之前
        assertEquals(listOf("en-US", "en"), groups.single { it.key == "en" }.variants.map { it.id })
    }

    @Test
    fun `empty input yields no groups`() {
        assertTrue(
            MarketModelGrouping.group(emptyList(), rank = { 0 }, nameOf = { it }).isEmpty()
        )
    }

    @Test
    fun `system language and its variants outrank everything but never loses inner order`() {
        val preferred = setOf("en")
        fun rank(id: String, selected: String = "ja", downloaded: Boolean = false) =
            MarketModelGrouping.rankOf(
                model = model(id),
                selectedModelId = selected,
                isDownloaded = downloaded,
                preferredKeys = preferred,
            )

        // 系统语言的普通变体 > 其它语言里正在使用的
        assertTrue(rank("en-AU") > rank("ja", selected = "ja"))
        // 系统语言里正在使用/已下载的 > 系统语言的普通变体
        assertTrue(rank("en-US", selected = "en-US") > rank("en-AU"))
        assertTrue(rank("en-US", downloaded = true) > rank("en-AU"))
        // 其它语言（都不在系统语言里）仍按「使用中 > 已下载 > 其余」
        assertTrue(rank("fr", selected = "fr") > rank("de", downloaded = true))
        assertTrue(rank("de", downloaded = true) > rank("zu"))
        // 空 preferredKeys = 不额外提权（语音分类等）
        assertTrue(
            MarketModelGrouping.rankOf(model("en"), "x", false, emptySet()) ==
                    MarketModelGrouping.rankOf(model("zu"), "x", false, emptySet())
        )
    }

    @Test
    fun `system language group is placed first after grouping`() {
        val preferred = setOf("en")
        val groups = MarketModelGrouping.group(
            models = listOf(model("zu"), model("de"), model("en"), model("en-GB")),
            rank = {
                MarketModelGrouping.rankOf(it, selectedModelId = "de", isDownloaded = true, preferredKeys = preferred)
            },
            nameOf = { it },
        )
        // de 正在使用，但 en 是系统语言 → en 组仍然第一
        assertEquals(listOf("en", "de", "zu"), groups.map { it.key })
    }
}