/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.ui.main.SymbolSliderEditActivity

/**
 * 滑块符号编辑弹窗的结果传递容器。
 *
 * 编辑弹窗 [SymbolSliderEditActivity] 在确认后把编辑后的符号串写入 [pendingSymbols]，
 * 并触发 [setOnResultListener] 注册的回调。数字键盘在编辑前注册回调以实时应用结果，
 * 同时在重新 attach 时通过 [consume] 兜底读取（防止键盘实例被重建）。每个字符代表滑块中的
 * 一个段，字符顺序即滑块竖排从上到下的顺序。
 */
object SymbolSliderEditStore {
    private var pendingSymbols: String? = null
    private var resultListener: ((String) -> Unit)? = null

    /** 注册结果回调（编辑确认时触发）。 */
    fun setOnResultListener(listener: ((String) -> Unit)?) {
        resultListener = listener
    }

    /** 读取并清空待应用的符号串。 */
    fun consume(): String? {
        val result = pendingSymbols
        pendingSymbols = null
        return result
    }

    /** 由编辑弹窗写入编辑结果并通知回调。 */
    fun set(symbols: String) {
        pendingSymbols = symbols
        resultListener?.invoke(symbols)
    }
}
