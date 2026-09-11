/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import org.fcitx.fcitx5.android.data.RecentlyUsed

/**
 * Picker 的「分类 → 页 → 符号」数据模型，从 [PickerPagesAdapter] 抽出（批次 D-2）。
 *
 * 抽出的目的与 [org.fcitx.fcitx5.android.input.keyboard.NumberKeyboardRows] 相同：
 * 让 View 版 adapter 与 Compose 版 Picker 读**同一份**分页逻辑，避免两套实现漂移。
 *
 * 约定（与原实现一致）：
 * - 第 0 页恒为「最近使用」，内容取 [recentlyUsed]；
 * - 其余分类按 [PickerPageUi.Density.pageSize] 分块成页，`policy.filter` 先过滤；
 * - [insertRecent] 忽略单个数字字符（半角/全角）。
 */
class PickerPageModel(
    private val rawData: List<Pair<PickerData.Category, Array<String>>>,
    private val density: PickerPageUi.Density,
    recentlyUsedFileName: String,
    private val policy: PickerPolicy,
) {

    /** `list<Category to [start, end]>`，首项是空的「最近使用」分类。 */
    private val categories: MutableList<Pair<PickerData.Category, IntRange>> = mutableListOf(
        PickerData.RecentlyUsedCategory to IntRange(0, 0)
    )

    /** 每页的符号列表，首项是空的「最近使用」页。 */
    private val pages: MutableList<List<String>> = mutableListOf(emptyList())

    val recentlyUsed = RecentlyUsed(recentlyUsedFileName, density.pageSize)

    init {
        buildCategories()
    }

    private fun buildCategories() {
        rawData.forEach { (category, array) ->
            val list = array.filter(policy::filter)
            val chunks = list.chunked(density.pageSize)
            categories.add(category to IntRange(pages.size, pages.size + chunks.size - 1))
            pages.addAll(chunks)
        }
    }

    /** 页数（含「最近使用」页）。 */
    val pageCount: Int get() = pages.size

    /** 某页的符号列表；第 0 页返回最近使用。 */
    fun pageItems(page: Int): List<String> =
        if (page == 0) recentlyUsed.items else pages.getOrElse(page) { emptyList() }

    /** 第 0 页（最近使用）没有弹层，调用方据此决定是否挂 `popupActionListener`。 */
    fun hasPopups(page: Int): Boolean = page != 0

    fun categoryList(): List<PickerData.Category> = categories.map { it.first }

    fun categoryIndexOfPage(page: Int): Int =
        categories.indexOfFirst { page in it.second }

    fun categoryRangeOfPage(page: Int): IntRange =
        categories.find { page in it.second }?.second ?: IntRange(0, 0)

    fun rangeOfCategoryIndex(index: Int): IntRange =
        categories[index].second

    fun insertRecent(text: String) {
        // 单个数字不入最近使用（半角与全角）
        if (text.length == 1 && text[0].code.let { it in Digit || it in FullWidthDigit }) return
        recentlyUsed.insert(text)
    }

    private var lastInvalidateKey = policy.invalidateKey()

    /**
     * `policy.invalidateKey()` 变化时按原始数据重建全部分类与页。
     *
     * @return 是否发生了重建（调用方据此刷新 UI，View 侧是 `notifyDataSetChanged`）
     */
    fun refreshIfNeeded(): Boolean {
        val newKey = policy.invalidateKey()
        if (lastInvalidateKey == newKey) return false
        lastInvalidateKey = newKey
        categories.clear()
        categories.add(PickerData.RecentlyUsedCategory to IntRange(0, 0))
        pages.clear()
        pages.add(emptyList())
        buildCategories()
        return true
    }

    companion object {
        private val Digit = IntRange('0'.code, '9'.code)
        private val FullWidthDigit = IntRange('０'.code, '９'.code)
    }
}
