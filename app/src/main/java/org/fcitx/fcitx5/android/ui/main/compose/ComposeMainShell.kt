/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose

import android.content.Intent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.ui.main.MainActivity
import org.fcitx.fcitx5.android.ui.main.settings.SearchResult

/**
 * Activity-scoped runtime wired into the compose shell. Kept out of [MainActivity] itself so the
 * activity stays a thin host that the upstream can keep merging into.
 */
class ComposeMainShell(private val activity: MainActivity) {

    private val fcitx: FcitxConnection = FcitxDaemon.connect(javaClass.name)

    private val _intents = MutableSharedFlow<Intent>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val intents: MutableSharedFlow<Intent> get() = _intents

    /**
     * Legacy settings-search bridge. [org.fcitx.fcitx5.android.ui.main.settings.SettingsSearchNavigator]
     * is kept as-is and this shell forwards MainActivity's public search APIs to it, using the
     * compose-owned legacy NavController when available.
     */
    var legacyNavController: (() -> androidx.navigation.NavController?) = { null }

    private val searchNavigator =
        org.fcitx.fcitx5.android.ui.main.settings.SettingsSearchNavigator(activity)

    fun navigateToSetting(result: SearchResult) {
        legacyNavController()?.let { searchNavigator.navigateToSetting(it, result) }
    }

    fun onIndexShown() {
        legacyNavController()?.let { searchNavigator.onIndexShown(it) }
    }

    fun getPendingHighlightKey(): String? = searchNavigator.takePendingHighlightKey()

    init {
        activity.intent?.let { _intents.tryEmit(it) }
    }

    fun onNewIntent(intent: Intent) {
        _intents.tryEmit(intent)
    }

    fun onDestroy() {
        FcitxDaemon.disconnect(javaClass.name)
    }

    fun onStop() {
        fcitx.runIfReady { save() }
    }
}
