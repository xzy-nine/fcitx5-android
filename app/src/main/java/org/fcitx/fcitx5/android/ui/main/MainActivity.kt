/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.ui.main.compose.ComposeMainShell
import org.fcitx.fcitx5.android.ui.main.compose.FcitxComposeApp
import org.fcitx.fcitx5.android.ui.main.settings.SearchResult

/**
 * Thin compose host: all navigation/UI logic lives in the compose shell.
 *
 * Upstream merge note: this file is deliberately reduced to a stub. When upstream changes
 * MainActivity (adding callbacks, menus or intent handling), port the change into
 * `compose/ComposeMainShell.kt` / `compose/FcitxComposeApp.kt` instead of this file.
 */
class MainActivity : AppCompatActivity() {

    private val shell = ComposeMainShell(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FcitxComposeApp(this, shell)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        shell.onNewIntent(intent)
    }

    override fun onStop() {
        shell.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        shell.onDestroy()
        super.onDestroy()
    }

    // The following public APIs are used by the legacy fragments; forwarded to the compose shell.
    fun navigateToSetting(result: SearchResult) = shell.navigateToSetting(result)

    fun onIndexShown() = shell.onIndexShown()

    fun getPendingHighlightKey(): String? = shell.getPendingHighlightKey()

    companion object {
        const val EXTRA_SETTINGS_ROUTE = "${BuildConfig.APPLICATION_ID}.EXTRA_SETTINGS_ROUTE"
    }

}
