/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.databinding.SymbolSliderEditBinding
import org.fcitx.fcitx5.android.input.keyboard.SymbolSliderEditStore
import org.fcitx.fcitx5.android.input.keyboard.SymbolSliderKey
import org.fcitx.fcitx5.android.utils.inputMethodManager

/**
 * 数字键盘滑块符号编辑弹窗。
 *
 * 以顶部弹窗形式展示，内含一个纯文本输入框：输入框中的每个字符即滑块中的一个段，
 * 字符顺序即滑块竖排从上到下的顺序。用户在输入框内可借助输入法直接编辑字符，
 * 点击确认后通过 [SymbolSliderEditStore] 把结果交回数字键盘。
 */
class SymbolSliderEditActivity : Activity() {

    private lateinit var binding: SymbolSliderEditBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.attributes.gravity = Gravity.TOP
        title = getString(R.string.symbol_slider_edit_title)
        binding = SymbolSliderEditBinding.inflate(layoutInflater).apply {
            setContentView(root)
            val initial = intent?.getStringExtra(EXTRA_SYMBOLS)
                ?: SymbolSliderKey.DefaultSymbols.joinToString("")
            symbolSliderEditInput.setText(initial)
            symbolSliderEditCancel.setOnClickListener { finish() }
            symbolSliderEditOk.setOnClickListener {
                SymbolSliderEditStore.set(
                    symbolSliderEditInput.text?.toString()?.trim().orEmpty()
                )
                finish()
            }
        }
        binding.symbolSliderEditInput.requestFocus()
        inputMethodManager.showSoftInput(
            binding.symbolSliderEditInput, InputMethodManager.SHOW_IMPLICIT
        )
    }

    override fun onStop() {
        super.onStop()
        finish()
    }

    companion object {
        const val EXTRA_SYMBOLS = "symbols"
    }
}
