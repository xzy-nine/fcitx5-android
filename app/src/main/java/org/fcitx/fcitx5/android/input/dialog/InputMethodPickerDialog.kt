/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.dialog

import android.content.Context
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.KeyboardMiuixBridge
import org.fcitx.fcitx5.android.utils.AppUtil

/**
 * Input method picker. Rendered by the compose overlay host ([KeyboardMiuixBridge.showPicker])
 * instead of a system AlertDialog, to keep the keyboard looking Miuix-styled.
 */
object InputMethodPickerDialog {

    suspend fun build(
        fcitx: FcitxAPI,
        service: FcitxInputMethodService,
        context: Context
    ): Unit {
        val entries = InputMethodData.resolve(fcitx, service)
        val enabledIM = fcitx.inputMethodEntryCached.uniqueName
        val enabledIndex = entries.indexOfFirst { it.uniqueName == enabledIM }
        val dividerIndex = entries.indexOfFirst { it.ime }
        KeyboardMiuixBridge.showPicker(
            KeyboardMiuixBridge.PickerSpec(
                entries = entries,
                selectedIndex = enabledIndex,
                dividerIndex = dividerIndex,
                onSelect = { entry ->
                    service.lifecycleScope.launch {
                        if (entry.ime) service.switchInputMethod(entry.uniqueName)
                        else fcitx.activateIme(entry.uniqueName)
                    }
                },
                onManage = {
                    AppUtil.launchMainToInputMethodList(context)
                },
            )
        )
    }
}
