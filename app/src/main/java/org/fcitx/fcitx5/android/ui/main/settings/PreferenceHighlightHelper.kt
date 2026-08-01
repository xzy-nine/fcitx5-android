/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.settings

import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.R

object PreferenceHighlightHelper {

    fun highlightPreference(fragment: PreferenceFragmentCompat, highlightKey: String?) {
        if (highlightKey.isNullOrEmpty()) return

        val screen = fragment.preferenceScreen
        if (screen == null || screen.preferenceCount == 0) return

        val targetPreference = findPreferenceByTitle(screen, highlightKey)
        if (targetPreference != null) {
            highlightView(fragment, targetPreference)
        }
    }

    private fun findPreferenceByTitle(group: PreferenceGroup, title: String): Preference? {
        for (i in 0 until group.preferenceCount) {
            val preference = group.getPreference(i)
            if (preference.title?.toString() == title) {
                return preference
            }
            if (preference is PreferenceGroup) {
                val found = findPreferenceByTitle(preference, title)
                if (found != null) {
                    return found
                }
            }
        }
        return null
    }

    private fun highlightView(fragment: PreferenceFragmentCompat, preference: Preference) {
        preference.isSelectable = true

        Handler(Looper.getMainLooper()).post {
            val view = findPreferenceView(fragment, preference)
            if (view != null) {
                val highlightColor = fragment.resources.getColor(R.color.highlight_background, null)
                val originalBackground = view.background

                view.setBackgroundColor(highlightColor)

                scrollToView(fragment, view)

                Handler(Looper.getMainLooper()).postDelayed({
                    if (originalBackground != null) {
                        view.background = originalBackground
                    } else {
                        view.background = null
                    }
                }, 500)
            }
        }
    }

    private fun scrollToView(fragment: PreferenceFragmentCompat, view: View) {
        val listView = fragment.listView
        if (listView is RecyclerView) {
            listView.smoothScrollToPosition(findPositionInRecyclerView(listView, view))
        } else {
            listView.scrollTo(0, view.top)
        }
    }

    private fun findPositionInRecyclerView(recyclerView: RecyclerView, targetView: View): Int {
        val adapter = recyclerView.adapter ?: return 0
        for (i in 0 until adapter.itemCount) {
            val viewHolder = recyclerView.findViewHolderForAdapterPosition(i)
            if (viewHolder?.itemView === targetView) {
                return i
            }
        }
        return 0
    }

    private fun findPreferenceView(fragment: PreferenceFragmentCompat, preference: Preference): View? {
        val listView = fragment.listView
        if (listView is RecyclerView) {
            val adapter = listView.adapter ?: return null
            for (i in 0 until adapter.itemCount) {
                val viewHolder = listView.findViewHolderForAdapterPosition(i)
                viewHolder?.itemView?.let { view ->
                    val tag = view.tag
                    if (tag is Preference && tag === preference) {
                        return view
                    }
                }
            }
        } else {
            for (i in 0 until listView.childCount) {
                val child = listView.getChildAt(i)
                val tag = child.tag
                if (tag is Preference && tag === preference) {
                    return child
                }
            }
        }
        return null
    }
}
