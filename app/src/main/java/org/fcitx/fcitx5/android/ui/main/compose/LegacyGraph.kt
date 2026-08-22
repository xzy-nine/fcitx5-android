/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose

import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.createGraph
import androidx.navigation.fragment.fragment
import androidx.savedstate.SavedState
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.data.quickphrase.QuickPhrase
import org.fcitx.fcitx5.android.ui.main.AboutFragment
import org.fcitx.fcitx5.android.ui.main.DeveloperFragment
import org.fcitx.fcitx5.android.ui.main.LicensesFragment
import org.fcitx.fcitx5.android.ui.main.PluginFragment
import org.fcitx.fcitx5.android.ui.main.settings.ListFragment
import org.fcitx.fcitx5.android.ui.main.settings.PinyinCustomPhraseFragment
import org.fcitx.fcitx5.android.ui.main.settings.PinyinDictionaryFragment
import org.fcitx.fcitx5.android.ui.main.settings.PunctuationEditorFragment
import org.fcitx.fcitx5.android.ui.main.settings.QuickPhraseEditFragment
import org.fcitx.fcitx5.android.ui.main.settings.QuickPhraseListFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.TableInputMethodFragment
import org.fcitx.fcitx5.android.ui.main.settings.addon.AddonConfigFragment
import org.fcitx.fcitx5.android.ui.main.settings.addon.AddonListFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.AdvancedSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.CandidatesSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.ClipboardSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.BroadcastSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.KeyboardSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.SymbolSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.global.GlobalConfigFragment
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodConfigFragment
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodListFragment
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemeFragment
import org.fcitx.fcitx5.android.utils.parcelable
import kotlin.reflect.typeOf

/**
 * Empty-invisible start destination for the legacy fragment graph.
 *
 * The upstream [SettingsRoute.createGraph] uses MainFragment as its start destination, which would
 * duplicate the compose home screen when the compose back stack enters a legacy screen. This graph
 * mirrors the upstream graph except for the start destination (an invisible anchor) and the label
 * maps (titles are owned by the compose [AppRoute.Legacy] screens).
 */
class LegacyAnchorFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = FrameLayout(requireContext())
        // anchor sits below the actual legacy content pages; keep it opaque so nothing shows
        // through on the brief transition between anchor and the first destination.
        val bg = TypedValue().also {
            requireContext().theme.resolveAttribute(
                android.R.attr.colorBackground, it, true
            )
        }
        v.setBackgroundColor(bg.data)
        return v
    }
}

object LegacyGraph {

    fun createGraph(controller: NavController) = controller.createGraph(SettingsRoute.Index) {
        val ctx = controller.context

        /* ========== Index ========== */

        fragment<LegacyAnchorFragment, SettingsRoute.Index>()

        /* ========== Fcitx ========== */

        fragment<GlobalConfigFragment, SettingsRoute.GlobalConfig>()
        fragment<InputMethodListFragment, SettingsRoute.InputMethodList>()
        fragment<InputMethodConfigFragment, SettingsRoute.InputMethodConfig>()
        fragment<AddonListFragment, SettingsRoute.AddonList>()
        fragment<AddonConfigFragment, SettingsRoute.AddonConfig>()

        /* ========== Android ========== */

        fragment<ThemeFragment, SettingsRoute.Theme>()
        fragment<KeyboardSettingsFragment, SettingsRoute.VirtualKeyboard>()
        fragment<CandidatesSettingsFragment, SettingsRoute.CandidatesWindow>()
        fragment<ClipboardSettingsFragment, SettingsRoute.Clipboard>()
        fragment<BroadcastSettingsFragment, SettingsRoute.Broadcast>()
        fragment<SymbolSettingsFragment, SettingsRoute.Symbol>()
        fragment<PluginFragment, SettingsRoute.Plugin>()
        fragment<AdvancedSettingsFragment, SettingsRoute.Advanced>()
        fragment<DeveloperFragment, SettingsRoute.Developer>()
        fragment<LicensesFragment, SettingsRoute.License>()
        fragment<AboutFragment, SettingsRoute.About>()

        /* ========== External ========== */

        fragment<ListFragment, SettingsRoute.ListConfig>(
            typeMap = mapOf(typeOf<SettingsRoute.ListConfig.Params>() to ListConfigParamsNavType)
        )
        fragment<PinyinDictionaryFragment, SettingsRoute.PinyinDict>()
        fragment<PunctuationEditorFragment, SettingsRoute.Punctuation>()
        fragment<QuickPhraseListFragment, SettingsRoute.QuickPhraseList>()
        fragment<QuickPhraseEditFragment, SettingsRoute.QuickPhraseEdit>(
            typeMap = mapOf(typeOf<SettingsRoute.QuickPhraseEdit.Param>() to QuickPhraseEditParamNavType)
        )
        fragment<TableInputMethodFragment, SettingsRoute.TableInputMethods>()
        fragment<PinyinCustomPhraseFragment, SettingsRoute.PinyinCustomPhrase>()
    }

    private val ListConfigParamsNavType =
        object : NavType<SettingsRoute.ListConfig.Params>(isNullableAllowed = false) {
            override fun put(bundle: SavedState, key: String, value: SettingsRoute.ListConfig.Params) {
                bundle.putParcelable(key, value)
            }

            override fun get(bundle: SavedState, key: String): SettingsRoute.ListConfig.Params? {
                return bundle.parcelable<SettingsRoute.ListConfig.Params>(key)
            }

            override fun serializeAsValue(value: SettingsRoute.ListConfig.Params): String {
                return Uri.encode(Json.encodeToString(value))
            }

            override fun parseValue(value: String): SettingsRoute.ListConfig.Params {
                return Json.decodeFromString(value)
            }
        }

    private val QuickPhraseEditParamNavType =
        object : NavType<SettingsRoute.QuickPhraseEdit.Param>(isNullableAllowed = false) {
            override fun put(bundle: SavedState, key: String, value: SettingsRoute.QuickPhraseEdit.Param) {
                bundle.putParcelable(key, value)
            }

            override fun get(bundle: SavedState, key: String): SettingsRoute.QuickPhraseEdit.Param? {
                return bundle.parcelable<SettingsRoute.QuickPhraseEdit.Param>(key)
            }

            override fun serializeAsValue(value: SettingsRoute.QuickPhraseEdit.Param): String {
                return Uri.encode(Json.encodeToString(value))
            }

            override fun parseValue(value: String): SettingsRoute.QuickPhraseEdit.Param {
                return Json.decodeFromString(value)
            }
        }
}
