/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

fun interface KeyActionListener {

    enum class Source {
        Keyboard, Popup
    }

    fun onKeyAction(action: KeyAction, source: Source)

    /**
     * 长按动作对应的「物理松开 / 手势取消」。
     *
     * 默认空实现：只有需要"按住语义"的动作关心（空格长按→语音输入：按下超过阈值即开始识别，
     * 抬起/取消时停止）。默认实现让本接口保持 `fun interface`（可继续 SAM 简写）。
     */
    fun onKeyActionRelease(action: KeyAction, source: Source) {}
}
