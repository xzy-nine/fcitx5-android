/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.MainActivity
import org.fcitx.fcitx5.android.utils.item
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import splitties.resources.styledColor

/**
 * Handles settings search navigation state for [MainActivity], kept out of MainActivity.kt to
 * minimize merge conflicts with upstream changes in that file.
 */
class SettingsSearchNavigator(private val activity: MainActivity) {

    private var pendingResult: SearchResult? = null
    private var pendingHighlightKey: String? = null

    fun registerMenuProvider() {
        // search menu provider (always visible)
        activity.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                val iconTint = activity.styledColor(android.R.attr.colorControlNormal)
                menu.item(R.string.search, R.drawable.ic_baseline_search_24, iconTint, true) {
                    SettingsSearchManager.showSearchDialog(activity)
                }
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean = false
        }, activity, Lifecycle.State.STARTED)
    }

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
