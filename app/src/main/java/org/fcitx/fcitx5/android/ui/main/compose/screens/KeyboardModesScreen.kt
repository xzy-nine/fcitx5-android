/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.screens

import android.content.Intent
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
import org.fcitx.fcitx5.android.ui.main.settings.behavior.SplitKeyboardCalibrationActivity
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 键盘形态页：分体、浮动与单手。
 *
 * 浮动与单手是互斥形态，开启其一时关闭另一个；分体的附属项与单手的靠右显示
 * 只在对应主开关开启时可用。
 */
@Composable
fun KeyboardModesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val internal = remember { AppPrefs.getInstance().internal }
    val keyboard = remember { AppPrefs.getInstance().keyboard }

    // 主体开关是互斥关系，改动后需要重新读取实际落盘值。
    var splitEnabled by remember { mutableStateOf(keyboard.splitKeyboardEnabled.getValue()) }
    var floatingEnabled by remember { mutableStateOf(internal.floatingModeEnabled.getValue()) }
    var oneHandEnabled by remember { mutableStateOf(internal.oneHandModeEnabled.getValue()) }

    PageScaffold(title = stringResource(R.string.keyboard_modes_title), onBack = onBack) {
        item { SmallTitle(text = stringResource(R.string.keyboard_modes_category_split)) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                SwitchPreference(
                    title = stringResource(R.string.split_keyboard_enabled),
                    checked = splitEnabled,
                    onCheckedChange = {
                        splitEnabled = it
                        keyboard.splitKeyboardEnabled.setValue(it)
                    },
                )
                SwitchPreference(
                    title = stringResource(R.string.split_keyboard_use_landscape_layout),
                    checked = keyboard.splitKeyboardUseLandscapeLayout.getValue(),
                    enabled = splitEnabled,
                    onCheckedChange = { keyboard.splitKeyboardUseLandscapeLayout.setValue(it) },
                )
                SwitchPreference(
                    title = stringResource(R.string.split_keyboard_duplicate_middle),
                    summary = stringResource(R.string.split_keyboard_duplicate_middle_summary),
                    checked = keyboard.splitKeyboardDuplicateMiddleKey.getValue(),
                    enabled = splitEnabled,
                    onCheckedChange = { keyboard.splitKeyboardDuplicateMiddleKey.setValue(it) },
                )
                SwitchPreference(
                    title = stringResource(R.string.split_keyboard_align_halves),
                    summary = stringResource(R.string.split_keyboard_align_halves_summary),
                    checked = keyboard.splitKeyboardAlignHalves.getValue(),
                    enabled = splitEnabled,
                    onCheckedChange = { keyboard.splitKeyboardAlignHalves.setValue(it) },
                )
                ArrowPreference(
                    title = stringResource(R.string.split_keyboard_calibration_title),
                    enabled = splitEnabled,
                    onClick = {
                        context.startActivity(
                            Intent(context, SplitKeyboardCalibrationActivity::class.java),
                        )
                    },
                )
            }
        }

        item { SmallTitle(text = stringResource(R.string.keyboard_modes_category_floating)) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                SwitchPreference(
                    title = stringResource(R.string.floating_keyboard_enabled),
                    summary = stringResource(R.string.floating_keyboard_enabled_summary),
                    checked = floatingEnabled,
                    onCheckedChange = { enabled ->
                        floatingEnabled = enabled
                        internal.floatingModeEnabled.setValue(enabled)
                        if (enabled) {
                            oneHandEnabled = false
                            internal.oneHandModeEnabled.setValue(false)
                        }
                    },
                )
                SwitchPreference(
                    title = stringResource(R.string.auto_floating_landscape),
                    summary = stringResource(R.string.auto_floating_landscape_summary),
                    checked = internal.autoFloatingLandscape.getValue(),
                    onCheckedChange = { internal.autoFloatingLandscape.setValue(it) },
                )
                SwitchPreference(
                    title = stringResource(R.string.floating_move_handle_auto_hide),
                    summary = stringResource(R.string.floating_move_handle_auto_hide_summary),
                    checked = internal.autoHideFloatingMoveHandle.getValue(),
                    onCheckedChange = { internal.autoHideFloatingMoveHandle.setValue(it) },
                )
            }
        }

        item { SmallTitle(text = stringResource(R.string.keyboard_modes_category_one_hand)) }
        item {
            Card(
                modifier = Modifier.padding(12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                SwitchPreference(
                    title = stringResource(R.string.one_hand_keyboard_enabled),
                    summary = stringResource(R.string.one_hand_keyboard_enabled_summary),
                    checked = oneHandEnabled,
                    onCheckedChange = { enabled ->
                        oneHandEnabled = enabled
                        internal.oneHandModeEnabled.setValue(enabled)
                        if (enabled) {
                            floatingEnabled = false
                            internal.floatingModeEnabled.setValue(false)
                        }
                    },
                )
                SwitchPreference(
                    title = stringResource(R.string.one_hand_keyboard_on_right),
                    summary = stringResource(R.string.one_hand_keyboard_on_right_summary),
                    checked = internal.oneHandOnRightPortrait.getValue(),
                    enabled = oneHandEnabled,
                    onCheckedChange = { internal.oneHandOnRightPortrait.setValue(it) },
                )
            }
        }
    }
}