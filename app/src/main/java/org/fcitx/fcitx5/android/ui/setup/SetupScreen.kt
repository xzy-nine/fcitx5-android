/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.appContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Compose onboarding (replaces SetupFragment/ViewPager2). Follows the Notify-Relay guide pattern:
 * an enum of steps rendered in a non-swipeable HorizontalPager; each step shows an action button
 * until its condition is done, then a checkmark. State is refreshed on every ON_RESUME, on every
 * change to the underlying secure IME settings (a ContentObserver — the real completion signal that
 * also works on Xiaomi/HyperOS where the picker never pauses this activity), and on a light poll
 * while not finished.
 */
@Composable
fun SetupScreen(onFinish: () -> Unit) {
    val context = LocalContext.current
    var refreshTick by remember { mutableIntStateOf(0) }

    // re-read the IME state every time the screen is resumed (user returns from settings)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refreshTick++ }

    // On Xiaomi/HyperOS showInputMethodPicker() is a system dialog that does NOT pause this
    // activity, so ON_RESUME never fires and the picker offers no completion callback at all.
    // Observe the underlying secure settings instead — this is the real, ROM-independent
    // "callback" that fires the moment the default/enabled IME changes (catches the final
    // 英语/拼音 confirmation too, since it writes DEFAULT_INPUT_METHOD).
    DisposableEffect(Unit) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                refreshTick++
            }

            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                refreshTick++
            }
        }
        val resolver = appContext.contentResolver
        resolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD), false, observer
        )
        resolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_INPUT_METHODS), false, observer
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }

    // Belt-and-suspenders: poll while not finished (matches Xime's 2s loop) in case the observer
    // is throttled or coalesced on some ROMs. Stops as soon as every step is done.
    LaunchedEffect(Unit) {
        while (!SetupPage.entries.all { it.isDone() }) {
            delay(1500)
            refreshTick++
        }
    }

    val doneMap = remember(refreshTick) {
        SetupPage.entries.associateWith { it.isDone() }
    }
    val initialPage = remember {
        SetupPage.entries.indexOfFirst { !it.isDone() }.coerceAtLeast(0)
    }
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { SetupPage.entries.size },
    )
    val scope = rememberCoroutineScope()

    fun animateTo(index: Int) {
        scope.launch { pagerState.animateScrollToPage(index) }
    }

    val allDone = doneMap.values.all { it }
    val isLastPage = pagerState.currentPage == SetupPage.entries.size - 1

    BackHandler(enabled = pagerState.currentPage > 0) {
        animateTo(pagerState.currentPage - 1)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface)
            .navigationBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                userScrollEnabled = false,
            ) { page ->
                val step = SetupPage.entries[page]
                SetupStepContent(
                    step = step,
                    done = doneMap[step] ?: false,
                    context = context,
                )
            }
            SetupBottomBar(
                allDone = allDone,
                isFirstPage = pagerState.currentPage == 0,
                isLastPage = isLastPage,
                onPrev = { animateTo(pagerState.currentPage - 1) },
                onNext = {
                    if (isLastPage) onFinish()
                    else animateTo(pagerState.currentPage + 1)
                },
            )
        }
    }
}

@Composable
private fun SetupStepContent(
    step: SetupPage,
    done: Boolean,
    context: android.content.Context,
) {
    val hint = remember(step) { step.getHintText(context).toString() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
    ) {
        Spacer(Modifier.weight(1f))
        Image(
            painter = painterResource(R.drawable.ic_baseline_keyboard_24),
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurface),
        )
        Spacer(Modifier.height(32.dp))
        Text(
            text = hint,
            fontSize = 16.sp,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(32.dp))
        if (done) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_baseline_check_circle_24),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.primary),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = context.getString(R.string.done),
                    fontSize = 20.sp,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
        } else {
            TextButton(
                text = step.getButtonText(context).toString(),
                onClick = { step.getButtonAction(context) },
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun SetupBottomBar(
    allDone: Boolean,
    isFirstPage: Boolean,
    isLastPage: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (!isFirstPage) {
            TextButton(
                text = context.getString(R.string.prev),
                onClick = onPrev,
            )
        }
        Spacer(Modifier.weight(1f))
        if (!(isLastPage && !allDone)) {
            TextButton(
                text = context.getString(if (isLastPage) R.string.done else R.string.next),
                onClick = onNext,
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}
