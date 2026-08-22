/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import kotlinx.coroutines.flow.MutableStateFlow
import org.fcitx.fcitx5.android.input.dialog.InputMethodData

/**
 * One-way bridge from the keyboard's View layer into the compose overlay host
 * ([KeyboardMiuixOverlayHost]). PopupMenus / alert dialogs / snackbars that used to use the
 * system Material look now render as Miuix components, while the triggering call sites stay in
 * plain View code.
 */
object KeyboardMiuixBridge {

    class MenuAction(
        val text: String,
        val bold: Boolean = false,
        val enabled: Boolean = true,
        val separator: Boolean = false,
        val onClick: () -> Unit = {},
    )

    data class MenuSpec(
        val actions: List<MenuAction>,
        val onDismiss: () -> Unit = {},
    )

    data class SnackbarSpec(
        val text: String,
        val actionText: String,
        val onAction: () -> Unit,
        val onDismissed: () -> Unit = {},
    )

    data class PickerSpec(
        val entries: List<InputMethodData>,
        val selectedIndex: Int,
        val dividerIndex: Int,
        val onSelect: (InputMethodData) -> Unit,
        val onManage: () -> Unit,
    )

    val menu = MutableStateFlow<MenuSpec?>(null)
    val snackbar = MutableStateFlow<SnackbarSpec?>(null)
    val picker = MutableStateFlow<PickerSpec?>(null)

    fun showMenu(spec: MenuSpec) {
        push(spec)
    }

    fun dismissMenu() {
        menu.value = null
    }

    fun showSnackbar(
        text: String,
        actionText: String,
        onAction: () -> Unit,
        onDismissed: () -> Unit = {},
    ) {
        snackbar.value = SnackbarSpec(text, actionText, onAction, onDismissed)
    }

    fun showPicker(spec: PickerSpec) {
        picker.value = spec
    }

    fun dismissPicker() {
        picker.value = null
    }

    private inline fun push(spec: MenuSpec) {
        menu.value = spec
    }
}
