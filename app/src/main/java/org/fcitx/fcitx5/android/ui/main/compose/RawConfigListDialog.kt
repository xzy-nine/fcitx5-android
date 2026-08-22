/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.input.rememberTextFieldState
import org.fcitx.fcitx5.android.R
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.window.WindowListPopup

/** Edit mode of [RawConfigListEditDialog], mirroring the old ListFragment behaviours. */
sealed interface RawListEditMode {
    /** Pick from a fixed enum pool; already used values are hidden. */
    data class Choices(val entries: List<String>, val entriesI18n: List<String>?) : RawListEditMode

    /** Toggle between "True" / "False". */
    data object Bool : RawListEditMode

    /** Free-form integers. */
    data object Number : RawListEditMode

    /** Free-form strings. */
    data object FreeText : RawListEditMode

    /** Key capture entries (edited through [KeyCaptureDialog]). */
    data object Key : RawListEditMode
}

/**
 * Window-based ordered list editor for fcitx RawConfig list-style options (EnumList / List),
 * replacing the View-based [org.fcitx.fcitx5.android.ui.main.settings.ListFragment] flow.
 * Entries support reorder and removal; adding depends on the edit mode.
 */
@Composable
fun RawConfigListEditDialog(
    title: String,
    initialEntries: List<String>,
    mode: RawListEditMode,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var show by remember { mutableStateOf(true) }
    val entries = remember { mutableStateListOf<String>().apply { addAll(initialEntries) } }
    var showAddPopup by remember { mutableStateOf(false) }
    var keyEditIndex by remember { mutableStateOf(-1) }
    val addState = rememberTextFieldState()
    val listState = rememberLazyListState()

    fun display(value: String): String = when (val m = mode) {
        is RawListEditMode.Choices ->
            m.entriesI18n?.getOrNull(m.entries.indexOf(value)) ?: value
        RawListEditMode.Key ->
            runCatching { org.fcitx.fcitx5.android.core.Key.parse(value).localizedString }
                .getOrDefault(value)
        else -> value
    }

    if (keyEditIndex >= 0) {
        KeyCaptureDialog(
            title = title,
            initialKey = entries.getOrNull(keyEditIndex),
            onConfirm = { serialized ->
                if (keyEditIndex < entries.size) {
                    entries[keyEditIndex] = serialized
                } else {
                    entries.add(serialized)
                }
                keyEditIndex = -1
            },
            onDismiss = { keyEditIndex = -1 },
        )
    }

    if (show) {
        WindowDialog(
            show = true,
            title = title,
            onDismissRequest = {
                show = false
                onDismiss()
            },
        ) {
            Column(Modifier.fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                ) {
                    items(entries.size) { index ->
                        val value = entries[index]
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = display(value),
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(vertical = 12.dp)
                                    .then(
                                        if (mode == RawListEditMode.Key) {
                                            Modifier.clickable { keyEditIndex = index }
                                        } else Modifier
                                    ),
                            )
                            IconButton(
                                onClick = {
                                    if (index > 0) {
                                        entries.removeAt(index)
                                        entries.add(index - 1, value)
                                    }
                                },
                                enabled = index > 0,
                            ) {
                                Icon(MiuixIcons.ExpandLess, null, Modifier.size(20.dp))
                            }
                            IconButton(
                                onClick = {
                                    if (index < entries.lastIndex) {
                                        entries.removeAt(index)
                                        entries.add(index + 1, value)
                                    }
                                },
                                enabled = index < entries.lastIndex,
                            ) {
                                Icon(MiuixIcons.ExpandMore, null, Modifier.size(20.dp))
                            }
                            IconButton(onClick = { entries.removeAt(index) }) {
                                Icon(MiuixIcons.Delete, null, Modifier.size(20.dp))
                            }
                        }
                    }
                }

                when (mode) {
                    is RawListEditMode.Choices, RawListEditMode.Bool -> {
                        TextButton(
                            text = context.getString(R.string.add),
                            onClick = { showAddPopup = true },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    RawListEditMode.Key -> {
                        TextButton(
                            text = context.getString(R.string.add),
                            onClick = { keyEditIndex = entries.size },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    else -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            TextField(
                                state = addState,
                                label = context.getString(R.string.add),
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = {
                                    val text = addState.text.toString()
                                    val valid = when (mode) {
                                        RawListEditMode.Number -> text.toIntOrNull() != null
                                        else -> text.isNotEmpty()
                                    }
                                    if (valid && text !in entries) {
                                        entries.add(text)
                                    }
                                },
                            ) {
                                Icon(MiuixIcons.Add, null, Modifier.size(20.dp))
                            }
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                ) {
                    TextButton(
                        text = context.getString(android.R.string.cancel),
                        onClick = {
                            show = false
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = context.getString(android.R.string.ok),
                        onClick = {
                            onConfirm(entries.toList())
                            show = false
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }

    if (showAddPopup) {
        val candidates = when (val m = mode) {
            is RawListEditMode.Choices -> m.entries.filter { it !in entries }
            RawListEditMode.Bool -> listOf("True", "False").filter { it !in entries }
            else -> emptyList()
        }
        WindowListPopup(
            show = true,
            enableWindowDim = false,
            onDismissRequest = { showAddPopup = false },
            onDismissFinished = { showAddPopup = false },
        ) {
            val dismiss = LocalDismissState.current
            ListPopupColumn {
                candidates.forEachIndexed { index, candidate ->
                    DropdownImpl(
                        text = display(candidate),
                        optionSize = candidates.size,
                        isSelected = false,
                        index = index,
                        onSelectedIndexChange = {
                            entries.add(candidate)
                            dismiss?.invoke()
                        },
                    )
                }
            }
        }
    }
}
