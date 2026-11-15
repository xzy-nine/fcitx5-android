/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.status

import org.fcitx.fcitx5.android.core.Action
import org.fcitx.fcitx5.android.input.bar.ToolbarButton

/**
 * 更多页网格里的一格。
 *
 * 「可移动按钮」（[Movable]）与「固定功能项」分开成两类，是因为两者的排序语义不同：
 * 只有前者参与布局拖动，后者顺序恒定。
 */
sealed interface MorePageCell {

    /** 可移动按钮（当前未停靠，故显示在按钮池里）。 */
    data class Movable(
        val button: ToolbarButton,
        /** 当前是否可用；不可用时置灰，但编辑模式下仍可拖到工具栏。 */
        val usable: Boolean,
    ) : MorePageCell

    /** fcitx 引擎状态项（中英标点、全半角、简繁、预测等），固定在更多页、不参与拖动。 */
    data class Fcitx(
        val action: Action,
        val label: String,
        val icon: Int,
        val active: Boolean,
    ) : MorePageCell

    /** 撤销/重做两个固定槽位的显隐开关（图标格子，点击切换）。 */
    data object UndoRedoToggle : MorePageCell

    /** 恢复默认按钮布局；仅编辑模式下出现。 */
    data object ResetLayout : MorePageCell

    /** 重载 fcitx 配置。 */
    data object ReloadConfig : MorePageCell

    /** 打开输入法应用设置。 */
    data object Settings : MorePageCell

    /** 编辑器信息检查器；仅在 debug 偏好开启时出现。 */
    data object EditorInfo : MorePageCell
}

/**
 * 更多页的数据组装。
 *
 * 纯函数：输入池子/引擎项/开关状态，输出两段网格的格子列表，便于单测固定项顺序与条件项。
 */
object MorePageModel {

    /** 按钮池：只有未停靠的按钮出现在这里（与工具栏互斥）。 */
    fun poolCells(
        pool: List<ToolbarButton>,
        usable: (ToolbarButton) -> Boolean,
    ): List<MorePageCell.Movable> = pool.map { MorePageCell.Movable(it, usable(it)) }

    /**
     * 固定功能项，顺序恒定：
     * 撤销开关 → 重置（仅编辑模式）→ 重载配置 → 设置 → 编辑器信息（条件）→ fcitx 引擎项。
     *
     * 引擎项放最后：它们的内容由引擎状态决定、数量可变，放在尾部不会挤动上方固定项的稳定位置。
     */
    fun fixedCells(
        editing: Boolean,
        showEditorInfo: Boolean,
        actions: List<Action>,
        iconOf: (Action) -> Int,
    ): List<MorePageCell> = buildList {
        add(MorePageCell.UndoRedoToggle)
        if (editing) add(MorePageCell.ResetLayout)
        add(MorePageCell.ReloadConfig)
        add(MorePageCell.Settings)
        if (showEditorInfo) add(MorePageCell.EditorInfo)
        actions.forEach {
            add(
                MorePageCell.Fcitx(
                    action = it,
                    label = it.shortText,
                    icon = iconOf(it),
                    active = it.icon.endsWith("-active") || it.isChecked,
                )
            )
        }
    }
}
