/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.settings.SettingsSearchManager
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Compose renderer for the settings search (replaces the View-based SettingsSearchManager
 * AlertDialog). Typing filters across managed preference categories and fixed entries; tapping a
 * result navigates to the corresponding Compose screen.
 */
@Composable
fun SettingsSearchScreen(
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(true) }
    val results = remember(query) {
        if (query.isNotEmpty()) SettingsSearchManager.search(context, query) else emptyList()
    }

    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            SearchBar(
                inputField = {
                    InputField(
                        query = query,
                        onQueryChange = { query = it },
                        onSearch = { expanded = false },
                        expanded = expanded,
                        onExpandedChange = { expanded = it },
                        label = context.getString(R.string.search_settings),
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                },
                expanded = expanded,
                onExpandedChange = { expanded = it },
                modifier = Modifier.padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
            ) {
                Column {
                    if (results.isEmpty()) {
                        Text(
                            text = context.getString(R.string.search_settings),
                            modifier = Modifier.padding(16.dp),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    } else {
                        results.forEach { result ->
                            ArrowPreference(
                                title = result.title,
                                summary = result.path.joinToString(" > "),
                                onClick = {
                                    val route = appRouteOf(result.route)
                                    onNavigate(
                                        if (result.highlightKey != null && route is AppRoute.Prefs) {
                                            route.copy(highlightKey = result.highlightKey)
                                        } else route
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
        SmallTopAppBar(
            title = context.getString(R.string.search_settings),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(MiuixIcons.Back, null, Modifier.size(24.dp))
                }
            },
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
        )
    }
}
