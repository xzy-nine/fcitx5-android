/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.status

import org.fcitx.fcitx5.android.core.Action
import org.fcitx.fcitx5.android.input.bar.ToolbarButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更多页数据组装：固定项顺序、条件项（编辑模式的重置、debug 的编辑器信息）与按钮池的可用性透传。
 *
 * [MorePageModel] 是纯函数，但 [Action] 是 fcitx 侧数据类，这里只用到 `shortText`/`icon`/`isChecked`
 * 三个字段，故用最小构造。
 */
class MorePageModelTest {

    private fun action(shortText: String, icon: String = "", checked: Boolean = false) = Action(
        id = 0,
        isSeparator = false,
        isCheckable = checked,
        isChecked = checked,
        name = shortText,
        icon = icon,
        shortText = shortText,
        longText = shortText,
        menu = null,
    )

    private fun fixed(
        editing: Boolean = false,
        showEditorInfo: Boolean = false,
        actions: List<Action> = emptyList(),
    ) = MorePageModel.fixedCells(
        editing = editing,
        showEditorInfo = showEditorInfo,
        actions = actions,
        iconOf = { 0 },
    )

    // ---------------------------------------------------------------- 按钮池

    @Test
    fun `pool follows the given button order`() {
        val pool = listOf(ToolbarButton.SplitKeyboard, ToolbarButton.Tune)
        val cells = MorePageModel.poolCells(pool) { true }
        assertEquals(listOf(ToolbarButton.SplitKeyboard, ToolbarButton.Tune), cells.map { it.button })
    }

    @Test
    fun `pool passes usability through to each cell`() {
        val pool = listOf(ToolbarButton.Voice, ToolbarButton.Clipboard)
        val cells = MorePageModel.poolCells(pool) { it != ToolbarButton.Voice }
        assertEquals(false, cells[0].usable)
        assertEquals(true, cells[1].usable)
    }

    @Test
    fun `pool may be empty when every button is docked`() {
        assertTrue(MorePageModel.poolCells(emptyList()) { true }.isEmpty())
    }

    // ---------------------------------------------------------------- 固定项顺序

    @Test
    fun `fixed order is undo toggle then reload then settings`() {
        assertEquals(
            listOf(
                MorePageCell.UndoRedoToggle,
                MorePageCell.ReloadConfig,
                MorePageCell.Settings,
            ),
            fixed(),
        )
    }

    @Test
    fun `reset layout only appears while editing`() {
        assertTrue(MorePageCell.ResetLayout !in fixed(editing = false))
        assertEquals(
            listOf(
                MorePageCell.UndoRedoToggle,
                MorePageCell.ResetLayout,
                MorePageCell.ReloadConfig,
                MorePageCell.Settings,
            ),
            fixed(editing = true),
        )
    }

    @Test
    fun `editor info cell appears only when the inspector preference is on`() {
        assertTrue(MorePageCell.EditorInfo !in fixed(showEditorInfo = false))
        assertEquals(
            listOf(
                MorePageCell.UndoRedoToggle,
                MorePageCell.ReloadConfig,
                MorePageCell.Settings,
                MorePageCell.EditorInfo,
            ),
            fixed(showEditorInfo = true),
        )
    }

    @Test
    fun `engine actions come last so they cannot shift the stable items`() {
        val cells = fixed(actions = listOf(action("a"), action("b")))
        assertEquals(
            listOf(
                MorePageCell.UndoRedoToggle,
                MorePageCell.ReloadConfig,
                MorePageCell.Settings,
            ),
            cells.take(3),
        )
        assertEquals(5, cells.size)
        assertEquals("a", (cells[3] as MorePageCell.Fcitx).label)
        assertEquals("b", (cells[4] as MorePageCell.Fcitx).label)
    }

    @Test
    fun `engine action active state comes from the icon suffix or the checked flag`() {
        val cells = fixed(
            actions = listOf(
                action("punc", icon = "fcitx-punc-active"),
                action("full", icon = "fcitx-fullwidth-inactive"),
                action("checked", checked = true),
            )
        ).filterIsInstance<MorePageCell.Fcitx>()
        assertEquals(listOf(true, false, true), cells.map { it.active })
    }

    @Test
    fun `editing and editor info combine in the documented order`() {
        val cells = fixed(editing = true, showEditorInfo = true)
        assertEquals(
            listOf(
                MorePageCell.UndoRedoToggle,
                MorePageCell.ResetLayout,
                MorePageCell.ReloadConfig,
                MorePageCell.Settings,
                MorePageCell.EditorInfo,
            ),
            cells,
        )
    }

    @Test
    fun `removed jump entries never appear`() {
        // 主题 / 输入法选项 / 虚拟键盘三个跳转项已移除，任何组合下都不应重新出现
        listOf(fixed(), fixed(editing = true), fixed(showEditorInfo = true)).forEach { cells ->
            assertTrue(cells.none { it is MorePageCell.Fcitx })
        }
    }
}
