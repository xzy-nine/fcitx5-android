/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.compose.AppRoute
import org.fcitx.fcitx5.android.ui.main.compose.appRouteOf
import org.fcitx.fcitx5.android.ui.main.settings.SettingsSearchManager
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.window.WindowDialog
import androidx.compose.ui.res.stringResource

/**
 * Compose search dialog (replaces both the legacy AlertDialog and the standalone page). The mature
 * miuix SearchBar + InputField live inside a WindowDialog; tapping a result navigates and closes.
 */
@Composable
fun SettingsSearchDialog(
    onNavigate: (AppRoute) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var searchText by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(true) }
    var show by remember { mutableStateOf(true) }
    val results = remember(searchText) {
        if (searchText.isNotEmpty()) SettingsSearchManager.search(context, searchText) else emptyList()
    }

    WindowDialog(
        show = show,
        onDismissRequest = {
            show = false
            onDismiss()
        },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SearchBar(
                inputField = {
                    InputField(
                        query = searchText,
                        onQueryChange = { searchText = it },
                        onSearch = { expanded = false },
                        expanded = expanded,
                        onExpandedChange = { expanded = it },
                        label = stringResource(R.string.search_settings),
                    )
                },
                expanded = expanded,
                onExpandedChange = { expanded = it },
            ) {
                Column {
                    if (results.isEmpty()) {
                        ArrowPreference(
                            title = stringResource(R.string.search_settings),
                            onClick = {},
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
                                    show = false
                                    onDismiss()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
