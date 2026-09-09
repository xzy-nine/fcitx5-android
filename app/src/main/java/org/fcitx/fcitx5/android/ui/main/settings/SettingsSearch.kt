/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.settings

import android.content.Context
import android.os.Parcelable
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.parcelize.Parcelize
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceCategory
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceUi
import org.fcitx.fcitx5.android.ui.main.MainActivity

@Parcelize
data class SearchResult(
    val title: String,
    val path: List<String>,
    val route: SettingsRoute,
    val highlightKey: String? = null
) : Parcelable

object SettingsSearchManager {

    private data class SearchIndex(
        val title: String,
        val key: String,
        val categoryTitle: String,
        val route: SettingsRoute
    )

    private lateinit var searchIndex: List<SearchIndex>

    fun initialize(context: Context) {
        if (::searchIndex.isInitialized) return
        val appPrefs = AppPrefs.getInstance()
        val indices = mutableListOf<SearchIndex>()

        fun addCategory(
            category: ManagedPreferenceCategory,
            route: SettingsRoute,
            categoryTitleRes: Int
        ) {
            val categoryTitle = context.getString(categoryTitleRes)
            category.managedPreferencesUi.forEach { ui ->
                val title = getUiTitle(context, ui)
                if (title.isNotEmpty()) {
                    indices.add(SearchIndex(title, ui.key, categoryTitle, route))
                }
            }
        }

        addCategory(appPrefs.keyboard, SettingsRoute.VirtualKeyboard, R.string.virtual_keyboard)
        addCategory(appPrefs.candidates, SettingsRoute.CandidatesWindow, R.string.candidates_window)
        addCategory(appPrefs.clipboard, SettingsRoute.Clipboard, R.string.clipboard)
        addCategory(appPrefs.broadcast, SettingsRoute.Broadcast, R.string.broadcast_settings)
        addCategory(appPrefs.symbols, SettingsRoute.Symbol, R.string.emoji_and_symbols)
        addCategory(appPrefs.advanced, SettingsRoute.Advanced, R.string.advanced)

        searchIndex = indices
    }

    private fun getUiTitle(context: Context, ui: ManagedPreferenceUi<*>): String {
        return when (ui) {
            is ManagedPreferenceUi.Switch -> context.getString(ui.title)
            is ManagedPreferenceUi.StringList<*> -> context.getString(ui.title)
            is ManagedPreferenceUi.EditTextInt -> context.getString(ui.title)
            is EditTextFloatUi -> context.getString(ui.title)
            is ManagedPreferenceUi.SeekBarInt -> context.getString(ui.title)
            is ManagedPreferenceUi.TwinSeekBarInt -> context.getString(ui.title)
            else -> ""
        }
    }

    fun search(context: Context, query: String): List<SearchResult> {
        initialize(context)
        val lowerQuery = query.lowercase()
        val results = mutableListOf<SearchResult>()

        results.addAll(getIndexResults(context, lowerQuery))
        results.addAll(getDynamicResults(context, lowerQuery))
        results.addAll(getAboutResults(context, lowerQuery))

        return results.sortedBy { it.title }
    }

    private fun getIndexResults(context: Context, query: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val indexItems = listOf(
            IndexItem(R.string.global_options, R.drawable.ic_baseline_tune_24, SettingsRoute.GlobalConfig),
            IndexItem(R.string.input_methods, R.drawable.ic_baseline_language_24, SettingsRoute.InputMethodList),
            IndexItem(R.string.addons, R.drawable.ic_baseline_extension_24, SettingsRoute.AddonList),
            IndexItem(R.string.theme, R.drawable.ic_baseline_palette_24, SettingsRoute.Theme),
            IndexItem(R.string.virtual_keyboard, R.drawable.ic_baseline_keyboard_24, SettingsRoute.VirtualKeyboard),
            IndexItem(R.string.candidates_window, R.drawable.ic_baseline_list_alt_24, SettingsRoute.CandidatesWindow),
            IndexItem(R.string.clipboard, R.drawable.ic_clipboard, SettingsRoute.Clipboard),
            IndexItem(R.string.emoji_and_symbols, R.drawable.ic_baseline_emoji_symbols_24, SettingsRoute.Symbol),
            IndexItem(R.string.plugins, R.drawable.ic_baseline_android_24, SettingsRoute.Plugin),
            IndexItem(R.string.advanced, R.drawable.ic_baseline_more_horiz_24, SettingsRoute.Advanced),
            IndexItem(R.string.developer, R.drawable.ic_baseline_code_24, SettingsRoute.Developer),
            IndexItem(R.string.about, R.drawable.ic_baseline_info_24, SettingsRoute.About),
        )

        indexItems.forEach { item ->
            val title = context.getString(item.title)
            if (title.lowercase().contains(query)) {
                results.add(SearchResult(title, listOf(title), item.route))
            }
        }

        return results
    }

    private fun getDynamicResults(context: Context, query: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        searchIndex.forEach { index ->
            if (index.title.lowercase().contains(query) || index.categoryTitle.lowercase().contains(query)) {
                results.add(SearchResult(
                    index.title,
                    listOf(index.categoryTitle, index.title),
                    index.route,
                    index.title
                ))
            }
        }

        return results
    }

    private fun getAboutResults(context: Context, query: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        val developer = context.getString(R.string.developer)
        if (developer.lowercase().contains(query)) {
            results.add(SearchResult(developer, listOf(developer), SettingsRoute.Developer))
        }

        val license = context.getString(R.string.license)
        if (license.lowercase().contains(query)) {
            results.add(SearchResult(license, listOf(developer, license), SettingsRoute.License))
        }

        val about = context.getString(R.string.about)
        if (about.lowercase().contains(query)) {
            results.add(SearchResult(about, listOf(about), SettingsRoute.About))
        }

        return results
    }

    fun showSearchDialog(activity: MainActivity) {
        val context = activity
        val builder = AlertDialog.Builder(context)
        builder.setTitle(R.string.search_settings)

        val customView = LayoutInflater.from(context).inflate(R.layout.search_dialog_layout, null)
        val searchEditText = customView.findViewById<android.widget.EditText>(R.id.search_edit_text)
        val recyclerView = customView.findViewById<RecyclerView>(R.id.search_results_list)
        builder.setView(customView)

        val dialog = builder.create()

        val adapter = SearchResultsAdapter(context) { result ->
            dialog.dismiss()
            activity.navigateToSetting(result)
        }
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = adapter

        dialog.show()

        searchEditText.requestFocus()

        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString() ?: ""
                if (query.isNotEmpty()) {
                    val results = search(context, query)
                    adapter.updateResults(results)
                } else {
                    adapter.updateResults(emptyList())
                }
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        adapter.updateResults(emptyList())
    }

    private data class IndexItem(
        @StringRes val title: Int,
        @DrawableRes val icon: Int,
        val route: SettingsRoute
    )

    private class SearchResultsAdapter(
        private val context: Context,
        private val onItemClick: (SearchResult) -> Unit
    ) : RecyclerView.Adapter<SearchResultsAdapter.ViewHolder>() {

        private var results = emptyList<SearchResult>()

        fun updateResults(newResults: List<SearchResult>) {
            results = newResults
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(context).inflate(android.R.layout.simple_list_item_2, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val result = results[position]
            holder.titleView.text = result.title
            holder.subtitleView.text = result.path.joinToString(" > ")
            holder.itemView.setOnClickListener {
                onItemClick(result)
            }
        }

        override fun getItemCount(): Int = results.size

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val titleView: TextView = itemView.findViewById(android.R.id.text1)
            val subtitleView: TextView = itemView.findViewById(android.R.id.text2)
        }
    }
}
