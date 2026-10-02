/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 模型清单的**按语言变体分组**（纯逻辑，供数字墨水这类「同一语言多个地区变体」的
 * 分类折叠展示；语音分类不分组）。
 *
 * 分组键 = BCP-47 的「语言 + 书写系统」：地区子标签被去掉，因此
 * `en` / `en-AU` / `en-CA` 归为一组 `en`，`zh-Hani` / `zh-Hani-CN` / `zh-Hani-HK` 归为
 * 一组 `zh-Hani`（书写系统不同则不会混在一起，如 `bn` 与 `bn-Latn`）。
 *
 * 只有一个成员的组**不算可折叠组**（否则清单里绝大多数语言都变成要先展开一下才能下载）。
 */
package org.fcitx.fcitx5.android.data.market

/** 一个折叠组：`key` 是基础语言 tag，[variants] 是它下辖的全部模型（含基础语言本身）。 */
data class MarketModelGroup(
    val key: String,
    /** 组标题（基础语言名；清单里查不到时回落成 tag）。 */
    val title: String,
    val variants: List<MarketModel>,
) {
    /** 是否值得折叠：只有一个成员时直接平铺成卡片更省一次点击。 */
    val foldable: Boolean get() = variants.size > 1
}

object MarketModelGrouping {

    /** 「系统语言及其变体」组的权重加成：确保该组排在「已下载但非系统语言」之前。 */
    private const val PREFERRED_BONUS = 4

    /**
     * 排序权重（越大越靠前）。叠加两个诉求：
     *
     * 1. **系统语言及其全部变体置顶**（[preferredKeys]，最大加成）；
     * 2. 组内/组间再按「使用中 → 已下载」细化（沿用上一轮的需求）。
     *
     * 于是顺序为：系统语言里正在用的 → 系统语言里已下载的 → 系统语言其余变体 →
     * 其它语言里正在用的 → 其它语言里已下载的 → 其余。
     */
    fun rankOf(
        model: MarketModel,
        selectedModelId: String,
        isDownloaded: Boolean,
        preferredKeys: Set<String>,
    ): Int {
        var rank = if (baseTag(model.id) in preferredKeys) PREFERRED_BONUS else 0
        rank += when {
            model.id == selectedModelId -> 3
            isDownloaded -> 2
            else -> 0
        }
        return rank
    }

    /**
     * BCP-47 → 基础 tag（语言 + 可选书写系统）。
     *
     * 例：`zh-Hani-CN` → `zh-Hani`；`en-AU` → `en`；`fr-002` → `fr`；`ber-Latn` → `ber-Latn`。
     */
    fun baseTag(tag: String): String {
        val parts = tag.split('-').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return tag
        if (parts.size == 1) return parts[0]
        // 书写系统子标签固定 4 个字母（`Latn`/`Hani`/`Cyrl`…）；地区是 2 字母或 3 数字
        val second = parts[1]
        val isScript = second.length == 4 && second.all { it.isLetter() }
        return if (isScript) "${parts[0]}-$second" else parts[0]
    }

    /**
     * 把清单折成「基础语言 → 变体」。
     *
     * @param rank 每组/每条的重排权重（越大越靠前）：调用方用它把「使用中」放最前、
     *   「已下载」次之；同权重保持清单原顺序（`sortedByDescending` 稳定）。
     * @param nameOf 基础 tag 的展示名（通常查内置语言清单）
     */
    fun group(
        models: List<MarketModel>,
        rank: (MarketModel) -> Int,
        nameOf: (String) -> String,
    ): List<MarketModelGroup> {
        if (models.isEmpty()) return emptyList()
        // 首次出现顺序 = 清单原顺序（`groupBy` 保持插入顺序）
        val byBase = models.groupBy { baseTag(it.id) }
        return byBase.map { (base, variants) ->
            MarketModelGroup(
                key = base,
                title = nameOf(base),
                variants = variants.sortedByDescending(rank),
            )
        }.sortedByDescending { group -> group.variants.maxOf(rank) }
    }
}