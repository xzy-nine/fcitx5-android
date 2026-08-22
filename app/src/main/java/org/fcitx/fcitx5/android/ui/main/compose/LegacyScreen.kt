/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose

import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentContainerView
import androidx.navigation.fragment.NavHostFragment
import org.fcitx.fcitx5.android.ui.main.MainActivity
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.navigateWithAnim

/**
 * Hosts the legacy fragment navigation (upstream settings pages that have not been migrated yet)
 * inside a single compose entry. A fresh NavHostFragment is attached on each visit and detached
 * when the entry is popped, so the legacy back stack always starts from an invisible anchor.
 */
@Composable
fun LegacyScreen(route: SettingsRoute) {
    val activity = LocalContext.current as MainActivity
    val runtime = LocalLegacyNavRuntime.current
    val container = remember { FragmentContainerView(activity).apply { id = View.generateViewId() } }
    AndroidView(
        factory = { container },
        modifier = Modifier.fillMaxSize(),
    )
    DisposableEffect(container) {
        val fragmentManager = activity.supportFragmentManager
        val navHost = NavHostFragment()
        fragmentManager.beginTransaction()
            .add(container.id, navHost)
            .commitNow()
        val controller = navHost.navController
        controller.enableOnBackPressed(false)
        controller.graph = LegacyGraph.createGraph(controller)
        runtime.attach(controller)
        controller.navigateWithAnim(route)
        onDispose {
            runtime.detach()
            fragmentManager.beginTransaction()
                .remove(navHost)
                .commitNow()
        }
    }
}
