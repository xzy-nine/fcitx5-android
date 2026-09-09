/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.graphics.Outline
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider

/**
 * Extensions for keyboard views, kept out of InputView.kt to minimize merge conflicts with
 * upstream changes in that file.
 */

/**
 * Clip this view with rounded top corners (keyboard area effect).
 *
 * @param topInsetPx 顶部跳过的不绘制区域高度。用于让键盘背景只覆盖工具栏及以下区域，
 *                   从而把键盘顶部圆角保持在工具栏顶部，同时让上方的预编辑栏保持透明。
 */
fun View.applyTopRoundedCornerClip(cornerRadiusPx: Float, topInsetPx: Float = 0f) {
    outlineProvider = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val path = android.graphics.Path().apply {
                    val r = cornerRadiusPx
                    val top = topInsetPx.coerceAtMost(view.height.toFloat())
                    val w = view.width.toFloat()
                    val h = view.height.toFloat()
                    moveTo(0f, top + r)
                    quadTo(0f, top, r, top)
                    lineTo(w - r, top)
                    quadTo(w, top, w, top + r)
                    lineTo(w, h)
                    lineTo(0f, h)
                    close()
                }
                outline.setConvexPath(path)
            }
        }
    } else {
        object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val top = topInsetPx.toInt().coerceAtMost(view.height)
                outline.setRoundRect(0, top, view.width, view.height, cornerRadiusPx)
            }
        }
    }
    clipToOutline = true
    invalidateOutline()
}
