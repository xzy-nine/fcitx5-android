/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import kotlin.math.roundToInt

/**
 * 数字键盘左侧纵向符号滑块键视图。
 *
 * 该键是一个按键区域，内部以纵向排列的「子按钮」形式展示符号：每个字符一个子按钮，
 * 底部最后一个子按钮为「编辑」按钮。整个列表放在可滚动容器中，默认只外显
 * [SymbolSliderKey.visibleCount] 个子按钮，向上/下滑动可查看其余（含编辑按钮）。
 * 每个子按钮高度为父（滑块）高度 / 外显段数再减去 0.1，露出下一个按钮的一小部分以提示可滑动。
 *
 * 滑块区背景使用 IME 主背景色；子按钮直接复用键盘按键视图 [TextKeyView] / [ImageKeyView]，
 * 因此其背景、圆角、波纹/按压高亮、震动与声音反馈均与键盘其他按键一致，并同样受到主题与
 * 按键反馈设置的控制。
 */
@SuppressLint("ViewConstructor")
class SymbolSliderKeyView(
    ctx: Context,
    theme: Theme,
    val keyDef: SymbolSliderKey,
) : KeyView(ctx, theme, keyDef.appearance) {

    /** 符号列表（可编辑），由外部注入以在重建时保持状态。 */
    var symbols: MutableList<String> = keyDef.symbols.toMutableList()
        set(value) {
            field = value
            subList?.let { rebuildSubButtons() }
        }

    /** 外显的子按钮数量（默认 3）。 */
    val visibleCount: Int = keyDef.visibleCount.coerceAtLeast(1)

    /** 点按某子按钮输入符号的回调。 */
    var onSymbolInput: ((String) -> Unit)? = null

    /** 点击「编辑」子按钮的回调（由宿主键盘启动编辑弹窗 Activity）。 */
    var onEditClick: (() -> Unit)? = null

    // ---- 样式（与键盘其他按键一致，受主题控制） ----
    /** 符号子按钮字号（相对数字键稍小）。 */
    private val baseTextSize = 26f

    private var subList: LinearLayout? = null
    private var lastHeight = 0

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h != lastHeight) {
            lastHeight = h
            subList?.let { rebuildSubButtons() }
        }
    }

    init {
        // 滑块区背景使用 IME 主背景色（仅按钮保留各自的按键颜色）
        appearanceView.background = ColorDrawable(theme.backgroundColor)
        // 可滚动子按钮列表，填满整个滑块区域
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            subList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            addView(
                subList,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        appearanceView.add(scroll, appearanceView.lParams(0, 0) {
            startOfParent()
            endOfParent()
            topOfParent()
            bottomOfParent()
        })
        // 首次布局完成后重建（onSizeChanged 会以实际高度重建，此处兜底）
        post { subList?.let { rebuildSubButtons() } }
    }

    /** 父（滑块）高度 / 外显段数，再减去 0.1 露出部分下一个按钮以提示可滑动。 */
    private fun subButtonHeight(): Int =
        ((lastHeight / visibleCount) * 0.9f).roundToInt().coerceAtLeast(1)

    /** 依据当前 [symbols] 重建子按钮列表，末尾追加「编辑」子按钮（无需分割线）。 */
    private fun rebuildSubButtons() {
        val list = subList ?: return
        list.removeAllViews()
        val subHeight = subButtonHeight()
        symbols.forEach { symbol ->
            list.addView(makeSymbolButton(symbol), llParams(subHeight))
        }
        list.addView(makeEditButton(), llParams(subHeight))
    }

    private fun llParams(height: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, height
    )

    /** 符号子按钮：复用 [TextKeyView]，具备与键盘按键一致的样式与反馈。 */
    private fun makeSymbolButton(symbol: String): KeyView {
        val appearance = KeyDef.Appearance.Text(
            displayText = symbol,
            textSize = baseTextSize,
            textStyle = Typeface.NORMAL,
            variant = KeyDef.Appearance.Variant.Alternative
        )
        return TextKeyView(context, theme, appearance).apply {
            setOnClickListener { onSymbolInput?.invoke(symbol) }
        }
    }

    /** 编辑子按钮：复用 [ImageKeyView]，具备与键盘按键一致的样式与反馈。 */
    private fun makeEditButton(): KeyView {
        val appearance = KeyDef.Appearance.Image(
            src = R.drawable.ic_baseline_edit_24,
            variant = KeyDef.Appearance.Variant.Alternative
        )
        return ImageKeyView(context, theme, appearance).apply {
            setOnClickListener { onEditClick?.invoke() }
        }
    }
}
