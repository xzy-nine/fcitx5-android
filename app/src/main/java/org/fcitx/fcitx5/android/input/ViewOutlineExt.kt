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
 */
fun View.applyTopRoundedCornerClip(cornerRadiusPx: Float) {
    outlineProvider = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val path = android.graphics.Path().apply {
                    val r = cornerRadiusPx
                    val w = view.width.toFloat()
                    val h = view.height.toFloat()
                    moveTo(0f, r)
                    quadTo(0f, 0f, r, 0f)
                    lineTo(w - r, 0f)
                    quadTo(w, 0f, w, r)
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
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
            }
        }
    }
    clipToOutline = true
}
