/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.legacy

import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentContainerView
import androidx.navigation.fragment.NavHostFragment
import org.fcitx.fcitx5.android.ui.main.MainActivity
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Hosts the legacy fragment navigation (upstream settings pages that have not been migrated yet)
 * inside a single compose entry. A fresh NavHostFragment is attached on each visit and detached
 * when the entry is popped, so the legacy back stack always starts from an invisible anchor.
 */
@Composable
fun LegacyScreen(route: SettingsRoute) {
    val activity = LocalContext.current as MainActivity
    val runtime = LocalLegacyNavRuntime.current
    // force alpha=1 so the legacy View pages never show through whatever sits below
    val bgArgb = MiuixTheme.colorScheme.background.copy(alpha = 1f).toArgb()
    val container = remember {
        FragmentContainerView(activity).apply {
            id = View.generateViewId()
        }
    }
    AndroidView(
        factory = { container },
        modifier = Modifier.fillMaxSize(),
    )
    // keep the container background in sync with the active theme (dynamic color / night mode
    // changes), otherwise the legacy page keeps the color captured at first composition
    SideEffect {
        container.setBackgroundColor(bgArgb)
    }
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
            // Remove synchronously so the legacy NavHostFragment is torn down in the same frame
            // the compose Legacy entry is popped — an async commit() here would leave the
            // fragment briefly resident and fight the compose pop animation (jank on return).
            fragmentManager.beginTransaction()
                .remove(navHost)
                .commitNow()
        }
    }
}
