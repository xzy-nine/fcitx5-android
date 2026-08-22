/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavController

/**
 * Shared runtime between the compose [top.yukonga.miuix.kmp.nav.core.NavDisplay] and the legacy
 * fragment navigation.
 *
 * The compose back stack and the legacy back stack are synchronized as follows:
 *  - entering an [AppRoute.Legacy] attaches a fresh NavHostFragment whose graph starts at an
 *    invisible anchor; the requested destination is pushed on top of it.
 *  - system back, handled by FcitxComposeApp's NavDisplay `onBack`, first asks the legacy stack
 *    to pop: if it still has entries above the anchor, the legacy stack pops a single entry;
 *    otherwise the whole legacy entry is removed from the compose stack.
 */
class LegacyNavRuntime {

    var navController: NavController? = null
        private set

    internal fun attach(navController: NavController) {
        this.navController = navController
    }

    internal fun detach() {
        this.navController = null
    }

    private fun legacyStackSize(): Int = navController?.currentBackStack?.value?.size ?: 0

    /** Pops the legacy stack if it still has entries above the anchor. True when it consumed. */
    fun popLegacyBackStack(): Boolean {
        val controller = navController ?: return false
        if (legacyStackSize() > 1) {
            controller.popBackStack()
            return true
        }
        return false
    }
}

val LocalLegacyNavRuntime = staticCompositionLocalOf { LegacyNavRuntime() }
