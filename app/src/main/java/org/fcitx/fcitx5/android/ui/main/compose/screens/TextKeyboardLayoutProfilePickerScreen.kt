/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.config.ConfigProviders
import org.fcitx.fcitx5.android.input.config.UserConfigFiles
import org.fcitx.fcitx5.android.utils.toast
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 文本键盘布局 profile 选择器。
 *
 * 列出全部 profile，选中即切换并保存；默认 profile 显示为「默认」。
 * 该页仅由输入法工具栏按钮触发，因此以对话框形式呈现。
 */
@Composable
fun TextKeyboardLayoutProfilePickerScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val defaultProfile = UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE

    var current by remember {
        mutableStateOf(
            UserConfigFiles.normalizeTextKeyboardLayoutProfile(
                AppPrefs.getInstance().keyboard.textKeyboardLayoutProfile.getValue(),
            ) ?: defaultProfile,
        )
    }

    val profiles = remember(current) {
        (UserConfigFiles.listTextKeyboardLayoutProfiles() + current).distinct()
            .sortedWith(compareBy({ it != defaultProfile }, { it }))
    }

    fun display(name: String): String =
        if (name == defaultProfile) context.getString(R.string.default_) else name

    fun select(target: String) {
        AppPrefs.getInstance().keyboard.textKeyboardLayoutProfile.setValue(target)
        // 触发配置重载，使新 profile 立即生效
        ConfigProviders.provider = ConfigProviders.provider
        context.toast(
            context.getString(R.string.text_keyboard_layout_file_select_summary, display(target)),
        )
        current = target
        onDismiss()
    }

    WindowDialog(
        show = true,
        title = stringResource(R.string.text_keyboard_layout_file_select_title),
        onDismissRequest = onDismiss,
    ) {
        Column {
            profiles.forEach { name ->
                RadioButtonPreference(
                    title = display(name),
                    selected = name == current,
                    onClick = { select(name) },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                ArrowPreference(
                    title = stringResource(android.R.string.cancel),
                    onClick = onDismiss,
                )
            }
        }
    }
}
