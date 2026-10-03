/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 系统语言 → 数字墨水（ML Kit）语言 tag 的**纯逻辑**推导。
 *
 * 用于两件事（都在模型市场里）：
 * 1. 把系统语言**及其全部地区变体**排到清单最前（分组键见 `MarketModelGrouping.baseTag`）；
 * 2. 系统语言对应的 tag **不在内置清单**（`DigitalInkModelCatalog`）时，给出「直接下载」
 *    候选 tag —— 走 ML Kit 的 `RemoteModelManager.download()` 直接向 Google 请求该语言的模型。
 *
 * 候选按「最具体 → 最一般」排列：`sr-Latn-RS` → `sr-RS` → `sr-Latn` → `sr`。
 * 中文特殊：ML Kit 只有 Han 脚本模型（简繁同模型），故基础 tag 直接是 `zh-Hani`，
 * 不再拼脚本子标签（`zh-Hani-CN` 才是它的地区变体）。
 */
package org.fcitx.fcitx5.android.data.handwriting

import java.util.Locale

object DigitalInkSystemLanguage {

    /** 中文（Han 脚本）模型 tag：ML Kit 用同一个模型覆盖简体/繁体。 */
    const val TAG_ZH_HANI = "zh-Hani"

    /**
     * 基础**语言** tag：中文 → [TAG_ZH_HANI]；其余 → 语言码小写（`en`/`ja`/`ko`…）。
     *
     * 与 [org.fcitx.fcitx5.android.data.market.MarketModelGrouping.baseTag] 语义不同：后者保留
     * 书写系统子标签（`zh-Hant-TW` → `zh-Hant`），本方法只取语言码并把中文硬映射到 Han 模型，
     * 两者不可互相替代，故名字有意区分。
     */
    fun baseLanguageTag(language: String): String {
        val lower = language.trim().lowercase(Locale.ROOT)
        return if (lower == "zh") TAG_ZH_HANI else lower
    }

    /**
     * 系统 locale 对应的候选 tag（最具体 → 最一般，已去重）。
     *
     * @param script 书写系统子标签（`Hans`/`Latn`…；中文忽略，见类注释）
     * @param country 地区子标签（`CN`/`RS`…）
     */
    fun candidateTags(language: String, script: String?, country: String?): List<String> {
        if (language.isBlank()) return emptyList()
        val base = baseLanguageTag(language)
        val han = base == TAG_ZH_HANI
        val s = script?.trim()?.takeIf { it.isNotEmpty() && !han }
        val c = country?.trim()?.takeIf { it.isNotEmpty() }
        return buildList {
            if (s != null && c != null) add("$base-$s-$c")
            if (c != null) add("$base-$c")
            if (s != null) add("$base-$s")
            add(base)
        }.distinct()
    }

    /**
     * 候选里**第一个**能被 ML Kit 解析的 tag 的规范形式（`fromLanguageTag` 的回落结果）。
     *
     * ⚠️ ML Kit 自己**不做回落**：`en-NZ` 会直接解析失败。所以这里必须显式按候选链
     * 逐个尝试，才能从 `en-NZ` 得到 `en`；整条链都解析不了（该语言 ML Kit 不支持）→ null。
     */
    fun canonicalTag(
        candidates: List<String>,
        canonicalOf: (String) -> String?,
    ): String? = candidates.firstNotNullOfOrNull { canonicalOf(it) }
}