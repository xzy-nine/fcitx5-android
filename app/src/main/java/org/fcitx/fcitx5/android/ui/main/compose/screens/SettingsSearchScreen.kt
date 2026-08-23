/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.compose.AppRoute
import org.fcitx.fcitx5.android.ui.main.compose.appRouteOf
import org.fcitx.fcitx5.android.ui.main.settings.SettingsSearchManager
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.window.WindowBottomSheet

/**
 * Compose search bottom sheet (replaces both the legacy AlertDialog and the standalone page). Uses
 * the miuix SearchBar + InputField inside a [WindowBottomSheet]: the field is initially collapsed,
 * expands when focused to reveal the result list, and IME Search (Enter) collapses it again. Search
 * results are rendered in a scrollable [LazyColumn]; empty states distinguish "not yet searched"
 * from "no matches".
 */
@Composable
fun SettingsSearchDialog(
    onNavigate: (AppRoute) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    var searchText by remember { mutableStateOf("") }
    // Initially collapsed; miuix expands the SearchBar once the field gains focus.
    var expanded by remember { mutableStateOf(false) }
    var show by remember { mutableStateOf(true) }

    val results = remember(searchText) {
        if (searchText.isNotBlank()) {
            SettingsSearchManager.search(context, searchText)
        } else {
            emptyList()
        }
    }

    // When the sheet appears, wait for its window-level Dialog to attach, then focus the search
    // field and bring up the IME automatically. Setting expanded triggers the InputField's own
    // focus logic (and expands the result area), while the keyboard controller guarantees IME.
    LaunchedEffect(show) {
        if (show) {
            delay(300)
            expanded = true
            keyboard?.show()
        }
    }

    WindowBottomSheet(
        show = show,
        title = stringResource(R.string.search_settings),
        onDismissRequest = { show = false },
        onDismissFinished = { onDismiss() },
    ) {
        // Available inside the sheet content; triggers its closing animation.
        val dismiss = LocalDismissState.current
        SearchBar(
            modifier = Modifier.padding(bottom = 8.dp),
            inputField = {
                InputField(
                    query = searchText,
                    onQueryChange = { searchText = it },
                    onSearch = { expanded = false },
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    label = stringResource(R.string.search_hint),
                )
            },
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            when {
                // Nothing typed yet: show a passive hint instead of a bogus row.
                searchText.isBlank() -> BasicComponent(
                    title = stringResource(R.string.search_settings),
                    summary = stringResource(R.string.search_hint),
                )

                // Typed but no match.
                results.isEmpty() -> BasicComponent(
                    title = stringResource(R.string.search_no_results, searchText),
                )

                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                ) {
                    items(results) { result ->
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
                                dismiss?.invoke()
                            },
                        )
                    }
                }
            }
        }
    }
}
