/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.res.Configuration
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.utils.unset
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerInParent
import splitties.views.dsl.constraintlayout.parentId

/**
 * Extensions for [KeyView] subclasses, kept out of KeyView.kt to minimize merge conflicts
 * with upstream changes in that file.
 */

/**
 * Show or hide the icon of an [ImageTextKeyView], adjusting the main text constraints so it is
 * centered in the whole key (icon hidden) or sits above the bottom edge (icon visible).
 */
fun ImageTextKeyView.setIconVisible(visible: Boolean) {
    if (visible) {
        img.visibility = View.VISIBLE
        mainText.updateLayoutParams<ConstraintLayout.LayoutParams> {
            centerHorizontally()
            bottomToBottom = parentId
            bottomMargin = vMargin + if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) dp(2) else dp(4)
            topToTop = unset
        }
    } else {
        img.visibility = View.GONE
        mainText.updateLayoutParams<ConstraintLayout.LayoutParams> {
            centerInParent()
        }
    }
}
