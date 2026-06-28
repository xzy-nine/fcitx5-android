/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.navigateWithAnim

class ClipboardSettingsFragment: ManagedPreferenceFragment(AppPrefs.getInstance().clipboard) {
    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        screen.addPreference(R.string.broadcast_settings, icon = R.drawable.ic_baseline_broadcast_on_24) {
            requireActivity().supportFragmentManager.primaryNavigationFragment?.navigateWithAnim(SettingsRoute.Broadcast)
        }
    }
}
