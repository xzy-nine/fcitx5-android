/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.os.Handler
import android.os.Looper
import androidx.navigation.NavController
import org.fcitx.fcitx5.android.ui.main.MainActivity
import org.fcitx.fcitx5.android.utils.navigateWithAnim

/**
 * Handles settings search navigation state for [MainActivity], kept out of MainActivity.kt to
 * minimize merge conflicts with upstream changes in that file.
 */
class SettingsSearchNavigator(private val activity: MainActivity) {

    private var pendingResult: SearchResult? = null
    private var pendingHighlightKey: String? = null

    fun navigateToSetting(navController: NavController, result: SearchResult) {
        pendingResult = result
        val popped = navController.popBackStack(SettingsRoute.Index, false)
        if (!popped) {
            onIndexShown(navController)
        }
    }

    fun onIndexShown(navController: NavController) {
        val result = pendingResult ?: return
        pendingResult = null

        Handler(Looper.getMainLooper()).postDelayed({
            pendingHighlightKey = result.highlightKey
            navController.navigateWithAnim(result.route)
        }, 500)
    }

    fun takePendingHighlightKey(): String? {
        val key = pendingHighlightKey
        pendingHighlightKey = null
        return key
    }
}
