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
 * - 其余分类按 [PickerDensity.pageSize] 分块成页，`policy.filter` 先过滤；
 * - [insertRecent] 忽略单个数字字符（半角/全角）。
 */
class PickerPageModel(
    private val rawData: List<Pair<PickerData.Category, Array<String>>>,
    private val density: PickerDensity,
    recentlyUsedFileName: String,
    private val policy: PickerPolicy,
) {

    /** `list<Category to [start, end]>`，首项是空的「最近使用」分类。 */
    private val categories: MutableList<Pair<PickerData.Category, IntRange>> = mutableListOf(
        PickerData.RecentlyUsedCategory to IntRange(0, 0)
    )

    /** 每页的符号列表，首项是空的「最近使用」页。 */
    private val pages: MutableList<List<String>> = mutableListOf(emptyList())

    /**
     * 过滤后的分类（**不分页**）：`policy.filter` 之后的完整列表，供新 Picker 布局
     * （[flatCategories]）复用，避免与 [pages] 的分块重复过滤。
     */
    private val filteredCategories: MutableList<Pair<PickerData.Category, List<String>>> =
        mutableListOf()

    val recentlyUsed = RecentlyUsed(recentlyUsedFileName, density.pageSize)

    init {
        buildCategories()
    }

    private fun buildCategories() {
        rawData.forEach { (category, array) ->
            val list = array.filter(policy::filter)
            // 过滤后无符号的分类直接跳过：空分类的页面范围是 (n, n-1) 这种无效区间，
            // 会让 categoryList() 暴露一个点开没有内容的空标签
            if (list.isEmpty()) return@forEach
            filteredCategories.add(category to list)
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

    fun insertRecent(text: String): Boolean {
        // 单个数字不入最近使用（半角与全角）
        if (text.length == 1 && text[0].code.let { it in Digit || it in FullWidthDigit }) return false
        recentlyUsed.insert(text)
        return true
    }

    /**
     * 平铺分类列表（新 Picker 布局的数据源）：`[⟳最近使用] + 各分类(policy.filter 后)`，
     * 空分类剔除、每节带首项在平铺列表中的下标（左栏点击的滚动锚点）。
     *
     * 与 [pages] 的差别只在「是否按 `density.pageSize` 分块」：分块版继续供休眠的 View 版
     * [PickerPagesAdapter] 使用，本方法与它共用同一份 [filteredCategories]，不会漂移。
     *
     * 每次调用都会重新读 [recentlyUsed]（提交符号后需即时反映），故调用方按需缓存，
     * 不要放进每次重组都执行的位置。
     */
    fun flatCategories(): List<FlatCategory> {
        // 上限取 density.pageSize：RecentlyUsed 自身不淘汰旧项，旧布局靠「一页只有 pageSize 个
        // 格子」隐式限长，平铺后必须显式截断，否则该节会随时间无限增长。
        val recent = recentlyUsed.items.take(density.pageSize)
        return buildFlatCategories(filteredCategories, recent)
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
        filteredCategories.clear()
        buildCategories()
        return true
    }

    /**
     * 平铺分类（[flatCategories] 的元素）：一个分类的标签 + 候选列表 + 是否挂长按弹层。
     *
     * 不含「在网格中的下标」——网格还要在分类之间插空行（换页），槽位下标由
     * [buildPickerGridLayout] 统一算，避免两处各算一份锚点。
     *
     * @param hasPopups 是否挂长按弹层（「⟳最近使用」节为 false，与 View 侧 `policy == null` 同语义）
     */
    data class FlatCategory(
        val category: PickerData.Category,
        val items: List<String>,
        val hasPopups: Boolean,
    )

    companion object {
        private val Digit = IntRange('0'.code, '9'.code)
        private val FullWidthDigit = IntRange('０'.code, '９'.code)
    }
}

/**
 * [PickerPageModel.flatCategories] 的纯函数内核（可单测，见 `PickerPageModelFlatCategoriesTest`）：
 *
 * - 第 0 节恒为「⟳最近使用」（**允许为空**，与页面模型第 0 页语义一致），且不挂长按弹层；
 * - 其余分类保持 [filteredCategories] 的顺序，**空分类剔除**（过滤后没有符号的分类不该出现空标签）。
 *
 * @param filteredCategories 已过 `policy.filter` 的分类（顺序即展示顺序）
 * @param recent 最近使用条目（新 → 旧；调用方负责按上限截断）
 */
internal fun buildFlatCategories(
    filteredCategories: List<Pair<PickerData.Category, List<String>>>,
    recent: List<String>,
): List<PickerPageModel.FlatCategory> {
    val result = ArrayList<PickerPageModel.FlatCategory>(filteredCategories.size + 1)
    result.add(
        PickerPageModel.FlatCategory(PickerData.RecentlyUsedCategory, recent, hasPopups = false)
    )
    filteredCategories.forEach { (category, list) ->
        if (list.isEmpty()) return@forEach
        result.add(PickerPageModel.FlatCategory(category, list, hasPopups = true))
    }
    return result
}
