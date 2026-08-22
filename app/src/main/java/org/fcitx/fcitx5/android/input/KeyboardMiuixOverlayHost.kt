/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.dialog.InputMethodListAdapter
import org.fcitx.fcitx5.android.input.dialog.SingleDividerDecoration
import splitties.resources.styledDrawable
import splitties.views.dsl.recyclerview.recyclerView
import splitties.views.recyclerview.verticalLayoutManager
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.SnackbarResult
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * Compose host mounted on top of the keyboard View hierarchy. Renders the Miuix-styled
 * menus / dialogs / snackbars requested through [KeyboardMiuixBridge].
 *
 * InputMethodService's input view tree does not propagate a SavedStateRegistryOwner, so a minimal
 * owner chain is installed on this view before [ComposeView] attaches.
 */
fun createKeyboardMiuixOverlayHost(context: Context): ComposeView {
    val lifecycleOwner = object : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        fun resume() = registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        fun pause() = registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    }
    val savedStateOwner = object : LifecycleOwner by lifecycleOwner,
        SavedStateRegistryOwner {
        private val controller = SavedStateRegistryController.create(this)
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
        fun attach() = controller.performRestore(Bundle())
    }
    val viewModelStoreOwner = object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }
    return ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        setViewTreeLifecycleOwner(lifecycleOwner)
        setViewTreeSavedStateRegistryOwner(savedStateOwner)
        setViewTreeViewModelStoreOwner(viewModelStoreOwner)
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                savedStateOwner.attach()
                lifecycleOwner.resume()
            }

            override fun onViewDetachedFromWindow(v: View) {
                lifecycleOwner.pause()
            }
        })
        setContent {
            KeyboardMiuixOverlay()
        }
    }
}

@Composable
private fun KeyboardMiuixOverlay() {
    val themeController = remember { ThemeController(ColorSchemeMode.System) }
    MiuixTheme(controller = themeController) {
        // MUST stay transparent: this host fills the whole input view above the keyboard,
        // a surface-colored Scaffold would hide the keyboard behind it.
        Scaffold(containerColor = Color.Transparent) {
            KeyboardMiuixContent()
        }
    }
}

@Composable
private fun KeyboardMiuixContent() {
    val menuSpec by KeyboardMiuixBridge.menu.collectAsState()
    val pickerSpec by KeyboardMiuixBridge.picker.collectAsState()
    val snackbarSpec by KeyboardMiuixBridge.snackbar.collectAsState()
    val snackbarState = remember { SnackbarHostState() }

    // ---- context menu (candidate actions / confirm actions / status area submenu) ----
    menuSpec?.let { spec ->
        val safeActions = spec.actions
        OverlayListPopup(
            show = true,
            enableWindowDim = false,
            onDismissRequest = {
                KeyboardMiuixBridge.dismissMenu()
                spec.onDismiss()
            },
            onDismissFinished = {
                KeyboardMiuixBridge.dismissMenu()
            },
        ) {
            Card(insideMargin = PaddingValues(0.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    safeActions.forEach { action ->
                        if (action.separator) {
                            HorizontalDivider()
                            return@forEach
                        }
                        Text(
                            text = action.text,
                            style = MiuixTheme.textStyles.body1,
                            fontWeight = if (action.bold) FontWeight.Bold else null,
                            color = if (action.enabled) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.onBackgroundVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = action.enabled) {
                                    if (action.enabled) action.onClick()
                                    KeyboardMiuixBridge.dismissMenu()
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                        )
                    }
                }
            }
        }
    }

    // ---- input method picker ----
    pickerSpec?.let { spec ->
        val context = LocalContext.current
        OverlayDialog(
            title = context.getString(R.string.choose_input_method),
            show = true,
            onDismissRequest = { KeyboardMiuixBridge.dismissPicker() },
            onDismissFinished = { KeyboardMiuixBridge.dismissPicker() },
        ) {
            Column(Modifier.fillMaxWidth()) {
                AndroidView(
                    factory = { ctx ->
                        ctx.recyclerView {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            )
                            layoutManager = verticalLayoutManager()
                            adapter = InputMethodListAdapter(spec.entries, spec.selectedIndex) { data ->
                                spec.onSelect(data)
                            }
                            ctx.styledDrawable(android.R.attr.dividerHorizontal)?.let {
                                addItemDecoration(SingleDividerDecoration(it, spec.dividerIndex))
                            }
                        }
                    },
                )
                TextButton(
                    text = context.getString(R.string.input_methods),
                    onClick = {
                        KeyboardMiuixBridge.dismissPicker()
                        spec.onManage()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    // ---- snackbar ----
    LaunchedEffect(snackbarSpec) {
        snackbarSpec?.let {
            val result = snackbarState.showSnackbar(
                message = it.text,
                actionLabel = it.actionText,
                withDismissAction = false,
            )
            if (result == SnackbarResult.ActionPerformed) it.onAction()
            else it.onDismissed()
        }
    }
    Box(Modifier.fillMaxSize()) {
        SnackbarHost(
            state = snackbarState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
        )
    }
}
