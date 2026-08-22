/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.SubtypeManager
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.InputFeedbacks
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Compose renderer for the input method list (replaces the View-based InputMethodListFragment).
 * Enabled IMEs are shown in Card rows, reorderable by long-press drag, removable and with a
 * per-IME settings button; every mutation is pushed back to fcitx with setEnabledIme.
 */
@Composable
fun InputMethodListScreen(
    onOpenConfig: (String, String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val fcitx: FcitxConnection = remember { FcitxDaemon.connect("compose-imlist") }
    val scope = remember {
        kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }
    var available by remember { mutableStateOf<List<InputMethodEntry>>(emptyList()) }
    var enabled by remember { mutableStateOf<List<InputMethodEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    fun push(enabledList: List<InputMethodEntry>) {
        fcitx.runIfReady {
            setEnabledIme(enabledList.map { it.uniqueName }.toTypedArray())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                SubtypeManager.syncWith(enabledIme())
            }
        }
    }

    DisposableEffect(Unit) {
        scope.launch {
            available = fcitx.runOnReady { availableIme().toList() }
            enabled = fcitx.runOnReady { enabledIme().toList() }
            loading = false
        }
        onDispose {
            fcitx.runIfReady { save() }
            FcitxDaemon.disconnect("compose-imlist")
            scope.cancel()
        }
    }

    if (loading) {
        Box(
            Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    // drag-reorder state
    val dragView = LocalView.current
    val rowHeightPx = with(LocalDensity.current) { 56.dp.toPx() }
    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragTarget by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val dragging = dragFrom >= 0

    fun finishDrag() {
        dragFrom = -1
        dragTarget = -1
        dragOffset = 0f
        push(enabled)
    }

    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxSize()) {
            LazyColumn(
                contentPadding = PaddingValues(
                    top = 64.dp + WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                    bottom = 24.dp,
                ),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(enabled, key = { _, e -> e.uniqueName }) { index, entry ->
                    val isDragging = index == dragFrom
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp)
                            .offset {
                                IntOffset(0, if (isDragging) dragOffset.roundToInt() else 0)
                            }
                            .pointerInput(entry.uniqueName) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        // vibrate on long-press, honoring the same keyboard
                                        // haptic configuration as the virtual keyboard.
                                        // InputFeedbacks.syncSystemPrefs() is normally only called
                                        // by the IME service, so sync it here too or the
                                        // "FollowingSystem" mode would short-circuit the vibration.
                                        InputFeedbacks.syncSystemPrefs()
                                        InputFeedbacks.hapticFeedback(dragView, longPress = true)
                                        dragFrom = index
                                        dragTarget = index
                                        dragOffset = 0f
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragOffset += amount.y
                                        val from = dragFrom
                                        val target = (from + (dragOffset / rowHeightPx).roundToInt())
                                            .coerceIn(0, enabled.lastIndex)
                                        if (target != dragTarget) {
                                            enabled = enabled.toMutableList().also {
                                                val e = it.removeAt(from)
                                                it.add(target, e)
                                            }
                                            // after reorder the dragged item now lives at [target]
                                            dragOffset -= (target - from) * rowHeightPx
                                            dragFrom = target
                                            dragTarget = target
                                        }
                                    },
                                    onDragEnd = { finishDrag() },
                                    onDragCancel = { finishDrag() },
                                )
                            },
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.defaultColors(
                                color = MiuixTheme.colorScheme.surfaceContainerHighest,
                            ),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                            ) {
                                Text(
                                    text = entry.displayName,
                                    modifier = Modifier.weight(1f).padding(vertical = 14.dp),
                                )
                                if (entry.isConfigurable) {
                                    IconButton(onClick = { onOpenConfig(entry.name, entry.uniqueName) }) {
                                        Icon(MiuixIcons.Tune, null, Modifier.size(20.dp))
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        enabled = enabled.toMutableList().apply { removeAt(index) }
                                        finishDrag()
                                    },
                                ) {
                                    Icon(MiuixIcons.Delete, null, Modifier.size(20.dp))
                                }
                                Icon(MiuixIcons.More, null, Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
        val candidates = available.filter { a -> enabled.none { it.uniqueName == a.uniqueName } }
        if (candidates.isNotEmpty()) {
            FloatingActionButton(
                onClick = {
                    val pick = candidates.first()
                    enabled = enabled + pick
                    finishDrag()
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                Icon(MiuixIcons.Add, null)
            }
        }
        SmallTopAppBar(
            color = MiuixTheme.colorScheme.surfaceContainer,
            title = context.getString(R.string.input_methods),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(MiuixIcons.Back, null, Modifier.size(24.dp))
                }
            },
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
        )
    }
}
