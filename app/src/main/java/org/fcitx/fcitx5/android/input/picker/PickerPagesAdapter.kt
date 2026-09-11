/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import android.annotation.SuppressLint
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.popup.PopupActionListener

/**
 * ViewPager2 的页 adapter。
 *
 * 分页/分类数据已抽到 [PickerPageModel]（View 与 Compose 版共用），本类只负责
 * ViewHolder 生命周期与把数据绑到 [PickerPageUi]。
 */
class PickerPagesAdapter(
    val theme: Theme,
    private val keyActionListener: KeyActionListener,
    private val popupActionListener: PopupActionListener,
    rawData: List<Pair<PickerData.Category, Array<String>>>,
    private val density: PickerPageUi.Density,
    recentlyUsedFileName: String,
    private val bordered: Boolean,
    private val policy: PickerPolicy
) : RecyclerView.Adapter<PickerPagesAdapter.ViewHolder>() {

    class ViewHolder(val ui: PickerPageUi) : RecyclerView.ViewHolder(ui.root)

    private val model = PickerPageModel(rawData, density, recentlyUsedFileName, policy)

    @SuppressLint("NotifyDataSetChanged")
    fun refreshIfNeeded() {
        if (model.refreshIfNeeded()) notifyDataSetChanged()
    }

    fun insertRecent(text: String) = model.insertRecent(text)

    fun getCategoryList(): List<PickerData.Category> = model.categoryList()

    fun getCategoryIndexOfPage(page: Int): Int = model.categoryIndexOfPage(page)

    fun getCategoryRangeOfPage(page: Int): IntRange = model.categoryRangeOfPage(page)

    fun getRangeOfCategoryIndex(cat: Int): IntRange = model.rangeOfCategoryIndex(cat)

    override fun getItemCount() = model.pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(PickerPageUi(parent.context, theme, density, bordered))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        if (position == 0) {
            // RecentlyUsed content should be displayed as-is, without popups
            holder.ui.setItems(model.pageItems(position))
        } else {
            // 需要 policy 来做 transform / 长按弹层
            holder.ui.setItems(model.pageItems(position), policy)
        }
    }

    override fun onViewAttachedToWindow(holder: ViewHolder) {
        holder.ui.keyActionListener = keyActionListener
        holder.ui.popupActionListener = if (holder.bindingAdapterPosition == 0) {
            // prevent popup on RecentlyUsed page
            null
        } else {
            popupActionListener
        }
    }

    override fun onViewDetachedFromWindow(holder: ViewHolder) {
        holder.ui.keyActionListener = null
        holder.ui.popupActionListener = null
    }
}
