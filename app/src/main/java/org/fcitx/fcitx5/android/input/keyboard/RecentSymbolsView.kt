/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.RecentSymbolsView.Companion.CELL_HEIGHT_FACTOR
import kotlin.math.roundToInt

/**
 * 数字键盘横屏分体模式下左侧的历史符号面板视图。
 *
 * 左侧为固定「!?#」布局切换按钮（切到符号选择页），右侧为历史符号 4 列网格（可上下滚动）。
 * 数据源为「最近使用的符号」（[org.fcitx.fcitx5.android.data.RecentlyUsed]），由外部注入。
 * 「!?#」按钮与网格格子同宽（扁矩形，高度见 [CELL_HEIGHT_FACTOR]），网格可上下滚动，
 * 点击经 [onSymbolInput] 回调输入并刷新数据；点击右侧按钮经 [onLayoutSwitch] 回调切换布局。
 * 无历史符号时网格区域显示空态占位提示。
 *
 * 子按钮直接复用键盘按键视图 [TextKeyView]，因此其背景、圆角、波纹/按压高亮、震动与
 * 声音反馈均与键盘其他按键一致，并同样受到主题与按键反馈设置的控制。
 *
 * **状态：已被纯 Compose 版 `ComposeRecentSymbols.kt`（`ComposeRecentSymbolsPanel`）取代。**
 * 生产路径（`KeyboardWindow` → `ComposeNumberKeyboard`）不再引用本文件，唯一引用者是同样
 * 已断线的 `NumberKeyboard.buildSplitLayout`（保留供回退）。删除本文件时请同步去掉那处引用，
 * 见 `Docs/View2Compose.md` §14 的「移除里程碑」。
 */
@SuppressLint("ViewConstructor")
class RecentSymbolsView(
    ctx: Context,
    theme: Theme,
) : KeyView(ctx, theme, placeholderAppearance) {

    companion object {
        /** 仅作为占位外观，实际绘制由本视图完成。 */
        private val placeholderAppearance = KeyDef.Appearance.Text(
            displayText = "⌘",
            textSize = 16f,
            percentWidth = 0.5f,
            variant = KeyDef.Appearance.Variant.AltForeground
        )

        /** 历史符号网格列数。 */
        private const val COLUMN_COUNT = 4

        /** 符号子按钮字号（与数字键一致）。 */
        private const val TEXT_SIZE = 26f

        /** 右侧「!?#」按钮字号（适配窄列）。 */
        private const val SWITCH_TEXT_SIZE = 12f

        /** 「!?#」按钮与历史符号格子的统一高度 = 列宽 × 该系数（扁矩形，紧凑排列）。 */
        private const val CELL_HEIGHT_FACTOR = 0.6f
    }

    /** 历史符号列表（最近使用在前），由外部注入；setter 触发网格刷新。 */
    var symbols: List<String> = emptyList()
        set(value) {
            field = value
            adapter.submit(value)
            emptyHint.visibility = if (value.isEmpty()) View.VISIBLE else View.GONE
        }

    /** 点按某符号子按钮的回调。 */
    var onSymbolInput: ((String) -> Unit)? = null

    /** 点按顶部「!?#」按钮的回调（切换到符号选择页）。 */
    var onLayoutSwitch: (() -> Unit)? = null

    /** 顶部「!?#」按钮。 */
    private val layoutSwitchButton: TextKeyView

    /** 4 列网格 RecyclerView。 */
    private val gridRecycler: RecyclerView

    /** 空态占位提示。 */
    private val emptyHint: TextView

    /** 网格列宽（面板宽度 / 列数）。 */
    private var cellWidth = 0

    private val adapter = RecentSymbolsAdapter()

    init {
        // 面板背景使用 IME 主背景色（仅按钮保留各自的按键颜色）
        appearanceView.background = ColorDrawable(theme.backgroundColor)
        // 面板整体不可按压，移除按键按压高亮
        appearanceView.foreground = null

        layoutSwitchButton = TextKeyView(
            context, theme,
            KeyDef.Appearance.Text(
                displayText = "!?#",
                textSize = SWITCH_TEXT_SIZE,
                textStyle = Typeface.BOLD,
                variant = KeyDef.Appearance.Variant.AltForeground
            )
        ).apply {
            setOnClickListener { onLayoutSwitch?.invoke() }
        }

        emptyHint = TextView(context).apply {
            isClickable = false
            isFocusable = false
            text = context.getString(R.string.recent_symbols_empty_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            gravity = Gravity.CENTER
            setTextColor((theme.altKeyTextColor and 0x00FFFFFF) or (0x80 shl 24))
            visibility = View.GONE
        }

        gridRecycler = RecyclerView(context).apply {
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            layoutManager = GridLayoutManager(context, COLUMN_COUNT)
            adapter = this@RecentSymbolsView.adapter
        }

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            // 左侧：布局切换按钮（固定宽度，高度占满）
            addView(
                layoutSwitchButton,
                LinearLayout.LayoutParams(
                    0, // 宽度在 refreshCellSize() 中设置
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            // 右侧：网格 + 空态提示重叠
            val gridContainer = FrameLayout(context).apply {
                addView(
                    gridRecycler,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                addView(
                    emptyHint,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }
            addView(
                gridContainer,
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    1f
                )
            )
        }
        appearanceView.addView(
            panel,
            ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_PARENT,
                ConstraintLayout.LayoutParams.MATCH_PARENT
            )
        )
        // 首次布局完成后以实际宽度计算格子边长
        post { refreshCellSize() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        refreshCellSize()
    }

    /** 依据当前面板宽度计算各尺寸，并同步到右侧按钮与网格 item。 */
    private fun refreshCellSize() {
        val w = width
        if (w <= 0) return
        // 网格和按钮同宽：总宽度 / (列数 + 1)
        val newCell = w / (COLUMN_COUNT + 1)
        if (newCell == cellWidth) return
        cellWidth = newCell
        val cellHeight = (newCell * CELL_HEIGHT_FACTOR).roundToInt().coerceAtLeast(1)
        // 设置按钮固定宽度（与网格同宽），高度占满
        layoutSwitchButton.layoutParams = LinearLayout.LayoutParams(
            cellWidth,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        // IME 布局过程中 setLayoutParams 触发的 requestLayout 可能被吞掉，
        // 等布局稳定后再强制重测一次，确保按钮高度生效
        post { layoutSwitchButton.requestLayout() }
        adapter.cellHeight = cellHeight
        adapter.notifyDataSetChanged()
    }

    /** 历史符号网格适配器：每格一个 [TextKeyView]（正方形）。 */
    private inner class RecentSymbolsAdapter :
        RecyclerView.Adapter<RecentSymbolsAdapter.Holder>() {

        private val data = mutableListOf<String>()

        /** 格子高度（扁矩形），由面板宽度计算，变化时需 notifyDataSetChanged。 */
        var cellHeight = 0

        fun submit(list: List<String>) {
            data.clear()
            data.addAll(list)
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = data.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val key = TextKeyView(
                parent.context, theme,
                KeyDef.Appearance.Text(
                    displayText = "",
                    textSize = TEXT_SIZE,
                    textStyle = Typeface.NORMAL,
                    variant = KeyDef.Appearance.Variant.Alternative
                )
            )
            return Holder(key)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.key.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                cellHeight.coerceAtLeast(1)
            )
            holder.bind(data[position])
        }

        inner class Holder(val key: TextKeyView) : RecyclerView.ViewHolder(key) {
            init {
                key.setOnClickListener { onSymbolInput?.invoke(data[bindingAdapterPosition]) }
            }

            fun bind(symbol: String) {
                key.mainText.text = symbol
            }
        }
    }
}
