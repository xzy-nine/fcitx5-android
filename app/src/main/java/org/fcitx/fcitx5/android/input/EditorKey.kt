/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: 2026 fcitx5-android custom
 */

package org.fcitx.fcitx5.android.input

import android.view.View
import android.view.inputmethod.EditorInfo

/**
 * 输入框标识（只取「换框会变、同框重启不变」的字段），用来区分
 * 「同一输入框被应用重启输入连接（resync）」与「焦点换到了另一个框」。
 *
 * 两者在框架层都是 `onStartInputView(restarting = true)`，只看 `restarting` 分不开，
 * 须比较本标识判定是否同框。
 *
 * 不含 `initialSelStart/End`：同框重启常带陈旧选区，计入会让同框重启被误判为换框。
 *
 * `fieldId` 为 [View.NO_ID]（应用没给控件 id）时退化为只比较其余字段。
 *
 * 单一真源：键盘侧（[InputView.startInput]）、触控笔手写（`StylusHandwritingController`）
 * 共用同一实现，避免两处判定口径漂移。
 */
internal class EditorKey(
    private val packageName: String?,
    private val fieldId: Int,
    private val inputType: Int,
    private val hintText: String?,
    private val imeOptions: Int,
) {

    fun isSameAs(other: EditorKey?): Boolean = other != null &&
            packageName == other.packageName &&
            inputType == other.inputType &&
            hintText == other.hintText &&
            imeOptions == other.imeOptions &&
            (fieldId == other.fieldId || fieldId == View.NO_ID)

    override fun toString(): String =
        "EditorKey(pkg=$packageName, fieldId=$fieldId, inputType=0x${inputType.toString(16)}, " +
                "hint=$hintText, imeOptions=0x${imeOptions.toString(16)})"

    companion object {
        fun of(info: EditorInfo) = EditorKey(
            packageName = info.packageName,
            fieldId = info.fieldId,
            inputType = info.inputType,
            // hintText 可能是 Spanned，转成 String 再比较，避免同一段文字因实例类型不同而判成换框
            hintText = info.hintText?.toString(),
            imeOptions = info.imeOptions,
        )
    }
}
