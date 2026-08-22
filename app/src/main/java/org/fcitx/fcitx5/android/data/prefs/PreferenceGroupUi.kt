/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen

/**
 * Renders a [ManagedPreferenceCategory]'s preferences grouped into titled
 * [ManagedPreferenceCategory.SubGroup] sections, kept out of ManagedPreferenceCategory.kt to
 * minimize merge conflicts with upstream changes in that file.
 */
fun renderGroupedPreferenceUi(
    screen: PreferenceScreen,
    groups: List<ManagedPreferenceCategory.SubGroup>,
    managedPreferencesUi: List<ManagedPreferenceUi<*>>
) {
    val ctx = screen.context
    val uiMap = managedPreferencesUi.associateBy { it.key }
    groups.forEachIndexed { _, group ->
        val category = PreferenceCategory(ctx).apply {
            title = ctx.getString(group.title)
        }
        screen.addPreference(category)
        group.keys.forEach { key ->
            val ui = uiMap[key]
            if (ui != null) {
                category.addPreference(ui.createUi(ctx).apply {
                    isEnabled = ui.isEnabled()
                })
            }
        }
    }
}
