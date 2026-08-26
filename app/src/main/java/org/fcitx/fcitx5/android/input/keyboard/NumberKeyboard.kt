/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import androidx.constraintlayout.widget.ConstraintLayout
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Variant
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.ui.main.SymbolSliderEditActivity
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.startToEndOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.imageResource

@SuppressLint("ViewConstructor")
class NumberKeyboard(
    context: Context,
    theme: Theme,
) : BaseKeyboard(context, theme, Layout) {

    override val supportsSplitLayout: Boolean = false

    companion object {
        const val Name = "Number"

        // 仅供 super 构造使用；实际布局由 rebuildKeyboardRows 自定义生成
        val Layout: List<List<KeyDef>> = emptyList()
    }

    val backspace: ImageKeyView by lazy { findViewById(R.id.button_backspace) }
    val space: TextKeyView by lazy { findViewById(R.id.button_mini_space) }
    val `return`: ImageKeyView by lazy { findViewById(R.id.button_return) }

    /** 滑块符号集，跨布局重建保持状态。 */
    private lateinit var sliderSymbols: MutableList<String>

    // 注意：rebuildKeyboardRows 会在 BaseKeyboard 构造期间（super 完成前）被调用，
    // 因此此处不能使用依赖子类属性初始化顺序的 lazy/属性访问，需即时读取 AppPrefs。
    private val symbolSliderVisibleCount: Int
        get() = AppPrefs.getInstance().symbols.symbolSliderVisibleCount.getValue()

    private fun buildRow1() = createKeyRow(
        listOf(
            NumPadKey("1", 0xffb1, 30f, 0f),
            NumPadKey("2", 0xffb2, 30f, 0f),
            NumPadKey("3", 0xffb3, 30f, 0f),
            BackspaceKey()
        )
    )

    private fun buildRow2() = createKeyRow(
        listOf(
            NumPadKey("4", 0xffb4, 30f, 0f),
            NumPadKey("5", 0xffb5, 30f, 0f),
            NumPadKey("6", 0xffb6, 30f, 0f),
            MiniSpaceKey()
        )
    )

    private fun buildRow3() = createKeyRow(
        listOf(
            NumPadKey("7", 0xffb7, 30f, 0f),
            NumPadKey("8", 0xffb8, 30f, 0f),
            NumPadKey("9", 0xffb9, 30f, 0f),
            NumPadKey("/", 0xffaf, 23f, 0.15f, Variant.Alternative)
        )
    )

    private fun buildRow4() = createKeyRow(
        listOf(
            ImageLayoutSwitchKey(
                R.drawable.ic_baseline_arrow_back_24,
                TextKeyboard.Name,
                percentWidth = 0.15f,
                variant = Variant.Accent
            ),
            NumPadKey(",", 0xffac, 23f, 0.1f, Variant.Alternative),
            LayoutSwitchKey("!?#", PickerWindow.Key.Symbol.name, 0.13333f, Variant.AltForeground),
            NumPadKey("0", 0xffb0, 30f, 0.23334f),
            NumPadKey("@", 0x40, 23f, 0.13333f, Variant.AltForeground),
            NumPadKey(".", 0xffae, 23f, 0.1f, Variant.Alternative),
            ReturnKey()
        )
    )

    override fun rebuildKeyboardRows(split: Boolean) {
        if (!::sliderSymbols.isInitialized) {
            sliderSymbols = SymbolSliderKey.DefaultSymbols.toMutableList()
        }
        removeAllViews()

        // 左侧纵向符号滑块（横跨第 1~3 行，即键盘高度的前 3/4）
        val sliderKeyDef = SymbolSliderKey(
            symbols = sliderSymbols.toTypedArray(),
            visibleCount = symbolSliderVisibleCount,
            percentWidth = 0.15f,
            variant = Variant.Alternative
        )
        val slider = createKeyView(sliderKeyDef) as SymbolSliderKeyView
        slider.symbols = sliderSymbols
        slider.onSymbolInput = { symbol -> onAction(KeyAction.FcitxKeyAction(symbol)) }
        slider.onEditClick = { launchSymbolEdit() }

        val row1 = buildRow1()
        val row2 = buildRow2()
        val row3 = buildRow3()
        val row4 = buildRow4()

        add(slider, lParams(0, 0) {
            startOfParent()
            topOfParent()
            matchConstraintDefaultWidth = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_PERCENT
            matchConstraintPercentWidth = 0.15f
            matchConstraintDefaultHeight = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_PERCENT
            matchConstraintPercentHeight = 0.75f
        })
        add(row1, lParams(0, 0) {
            startToEndOf(slider)
            endOfParent()
            topOfParent()
            matchConstraintDefaultHeight = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_PERCENT
            matchConstraintPercentHeight = 0.25f
        })
        add(row2, lParams(0, 0) {
            startToEndOf(slider)
            endOfParent()
            below(row1)
            matchConstraintDefaultHeight = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_PERCENT
            matchConstraintPercentHeight = 0.25f
        })
        add(row3, lParams(0, 0) {
            startToEndOf(slider)
            endOfParent()
            below(row2)
            matchConstraintDefaultHeight = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_PERCENT
            matchConstraintPercentHeight = 0.25f
        })
        add(row4, lParams(0, 0) {
            startOfParent()
            endOfParent()
            below(row3)
            bottomOfParent()
            matchConstraintDefaultHeight = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_PERCENT
            matchConstraintPercentHeight = 0.25f
        })

        onKeyboardLayoutRebuilt()
    }

    override fun onReturnDrawableUpdate(returnDrawable: Int) {
        `return`.img.imageResource = returnDrawable
    }

    override fun onAttach() {
        super.onAttach()
        // 注册编辑结果回调并兜底应用（防止键盘实例在编辑期间被重建）
        SymbolSliderEditStore.setOnResultListener { newSymbols -> applySymbols(newSymbols) }
        SymbolSliderEditStore.consume()?.let { applySymbols(it) }
    }

    /** 应用编辑弹窗返回的符号串。 */
    private fun applySymbols(newSymbols: String) {
        sliderSymbols = newSymbols.map { it.toString() }.toMutableList()
        refreshLayoutForPrefs()
    }

    /** 启动滑块符号编辑弹窗（顶部弹窗 Activity，内含输入框）。 */
    private fun launchSymbolEdit() {
        SymbolSliderEditStore.setOnResultListener { newSymbols -> applySymbols(newSymbols) }
        val current = sliderSymbols.joinToString("")
        val intent = Intent(context, SymbolSliderEditActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(SymbolSliderEditActivity.EXTRA_SYMBOLS, current)
        }
        context.startActivity(intent)
    }

}
