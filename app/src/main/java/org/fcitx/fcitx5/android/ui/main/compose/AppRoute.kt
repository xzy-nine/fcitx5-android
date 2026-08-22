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
    data object About : AppRoute

    @Serializable
    data object Licenses : AppRoute

    @Serializable
    data object Developer : AppRoute

    @Serializable
    data class Legacy(val target: LegacyTarget) : AppRoute

    @Serializable
    data class LegacyInputMethodConfig(val name: String, val uniqueName: String) : AppRoute

    @Serializable
    data class LegacyPinyinDict(val uri: String) : AppRoute

    @Serializable
    data class LegacyPunctuation(val title: String, val lang: String?) : AppRoute

    @Serializable
    data class Prefs(val category: PrefCategory, val highlightKey: String? = null) : AppRoute

    @Serializable
    data class RawConfigHost(
        val kind: RawConfigHostType,
        val name: String? = null,
        val uniqueName: String? = null,
    ) : AppRoute

    @Serializable
    data object SettingsSearch : AppRoute

    @Serializable
    data object InputMethodList : AppRoute

    @Serializable
    data class InputMethodConfig(val name: String, val uniqueName: String) : AppRoute

    @Serializable
    data object AddonList : AppRoute

    @Serializable
    data class AddonConfig(val name: String, val uniqueName: String) : AppRoute

    @Serializable
    data object PluginList : AppRoute

    @Serializable
    data object QuickPhraseList : AppRoute

    @Serializable
    data class QuickPhraseEdit(val fileName: String) : AppRoute

    @Serializable
    data object TableInputMethods : AppRoute

    @Serializable
    data object PinyinCustomPhrase : AppRoute

    @Serializable
    data class PinyinDictionary(val uri: String? = null) : AppRoute

    @Serializable
    data class Punctuation(val title: String, val lang: String?) : AppRoute

    @Serializable
    data object Theme : AppRoute
}

@Serializable
enum class RawConfigHostType {
    GlobalConfig,
    InputMethodConfig,
    AddonConfig;
}

@Serializable
enum class PrefCategory {
    Advanced,
    Keyboard,
    Candidates,
    Clipboard,
    Broadcast,
    Symbols;

    fun provider() = org.fcitx.fcitx5.android.data.prefs.AppPrefs.getInstance().let {
        when (this) {
            Advanced -> it.advanced
            Keyboard -> it.keyboard
            Candidates -> it.candidates
            Clipboard -> it.clipboard
            Broadcast -> it.broadcast
            Symbols -> it.symbols
        }
    }
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
