/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose

import kotlinx.serialization.Serializable
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import top.yukonga.miuix.kmp.nav.core.NavKey

/**
 * Compose navigation routes for the main window.
 *
 * Routes with parameters are intentionally kept out of [LegacyTarget] (and therefore the
 * serializable compose back stack): parameterized settings destinations are only reachable
 * from inside the legacy fragment stack, which lives in its own NavController.
 */
@Serializable
sealed interface AppRoute : NavKey {

    @Serializable
    data object Index : AppRoute

    @Serializable
    data class Legacy(val target: LegacyTarget) : AppRoute

    @Serializable
    data class LegacyInputMethodConfig(val name: String, val uniqueName: String) : AppRoute

    @Serializable
    data class LegacyPinyinDict(val uri: String) : AppRoute
}

@Serializable
enum class LegacyTarget {
    Index,
    GlobalConfig,
    InputMethodList,
    AddonList,
    Theme,
    VirtualKeyboard,
    CandidatesWindow,
    Clipboard,
    Broadcast,
    Symbol,
    Plugin,
    Advanced,
    Developer,
    License,
    About,
    QuickPhraseList,
    TableInputMethods,
    PinyinCustomPhrase;
}

object LegacyTargets {

    fun routeOf(target: LegacyTarget): SettingsRoute = when (target) {
        LegacyTarget.Index -> SettingsRoute.Index
        LegacyTarget.GlobalConfig -> SettingsRoute.GlobalConfig
        LegacyTarget.InputMethodList -> SettingsRoute.InputMethodList
        LegacyTarget.AddonList -> SettingsRoute.AddonList
        LegacyTarget.Theme -> SettingsRoute.Theme
        LegacyTarget.VirtualKeyboard -> SettingsRoute.VirtualKeyboard
        LegacyTarget.CandidatesWindow -> SettingsRoute.CandidatesWindow
        LegacyTarget.Clipboard -> SettingsRoute.Clipboard
        LegacyTarget.Broadcast -> SettingsRoute.Broadcast
        LegacyTarget.Symbol -> SettingsRoute.Symbol
        LegacyTarget.Plugin -> SettingsRoute.Plugin
        LegacyTarget.Advanced -> SettingsRoute.Advanced
        LegacyTarget.Developer -> SettingsRoute.Developer
        LegacyTarget.License -> SettingsRoute.License
        LegacyTarget.About -> SettingsRoute.About
        LegacyTarget.QuickPhraseList -> SettingsRoute.QuickPhraseList
        LegacyTarget.TableInputMethods -> SettingsRoute.TableInputMethods
        LegacyTarget.PinyinCustomPhrase -> SettingsRoute.PinyinCustomPhrase
    }
}
