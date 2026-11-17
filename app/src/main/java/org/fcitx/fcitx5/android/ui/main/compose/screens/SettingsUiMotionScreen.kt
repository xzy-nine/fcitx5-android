/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.screens

import android.os.Build
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
import org.fcitx.fcitx5.android.data.prefs.SettingsUiMotionPreferences
import org.fcitx.fcitx5.android.data.prefs.SettingsUiMotionPreferences.Style
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置界面动效页。
 *
 * 进入与返回的切换动效、持续时间、预测性返回开关；保存后立即作用到当前页面。
 */
@Composable
fun SettingsUiMotionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var selection by remember { mutableStateOf(SettingsUiMotionPreferences.read(context)) }

    fun update(next: SettingsUiMotionPreferences.Selection, recreate: Boolean = false) {
        selection = next
        SettingsUiMotionPreferences.save(context, next)
        if (recreate) (context as? android.app.Activity)?.recreate()
    }

    val styles = listOf(
        Style.HORIZONTAL to R.string.settings_ui_motion_horizontal,
        Style.VERTICAL to R.string.settings_ui_motion_vertical,
        Style.SCALE to R.string.settings_ui_motion_scale,
        Style.FADE to R.string.settings_ui_motion_fade,
        Style.NONE to R.string.settings_ui_motion_none,
    )
    val returnStyles = listOf(Style.FOLLOW to R.string.settings_ui_motion_follow) + styles
    val durations = listOf(
        SettingsUiMotionPreferences.DURATION_FAST.toInt() to R.string.settings_ui_motion_fast,
        SettingsUiMotionPreferences.DURATION_STANDARD.toInt() to R.string.settings_ui_motion_standard,
        SettingsUiMotionPreferences.DURATION_SLOW.toInt() to R.string.settings_ui_motion_slow,
    )

    // 每次点击切到下一个取值；选项数量少，无需额外的选择器页面。
    fun <T> cycle(list: List<T>, current: T): T {
        val index = list.indexOf(current)
        return list[(if (index < 0) 0 else index + 1) % list.size]
    }

    PageScaffold(title = stringResource(R.string.settings_ui_motion_title), onBack = onBack) {
        item { SmallTitle(text = stringResource(R.string.settings_ui_motion_pages)) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                ArrowPreference(
                    title = stringResource(R.string.settings_ui_motion_enter),
                    summary = stringResource(
                        styles.firstOrNull { it.first == selection.enter }?.second
                            ?: R.string.settings_ui_motion_horizontal,
                    ),
                    onClick = {
                        update(selection.copy(enter = cycle(styles.map { it.first }, selection.enter)))
                    },
                )
                ArrowPreference(
                    title = stringResource(R.string.settings_ui_motion_return),
                    summary = stringResource(
                        returnStyles.firstOrNull { it.first == selection.returning }?.second
                            ?: R.string.settings_ui_motion_follow,
                    ),
                    onClick = {
                        update(
                            selection.copy(returning = cycle(returnStyles.map { it.first }, selection.returning)),
                        )
                    },
                )
                ArrowPreference(
                    title = stringResource(R.string.settings_ui_motion_duration),
                    summary = stringResource(
                        durations.firstOrNull { it.first.toLong() == selection.durationMillis }?.second
                            ?: R.string.settings_ui_motion_standard,
                        selection.durationMillis,
                    ),
                    enabled = selection.hasAnimation,
                    onClick = {
                        val next = cycle(durations.map { it.first.toLong() }, selection.durationMillis)
                        update(selection.copy(durationMillis = next))
                    },
                )
            }
        }

        item { SmallTitle(text = stringResource(R.string.settings_ui_motion_gestures)) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                val supportsPredictive = Build.VERSION.SDK_INT >= 34
                SwitchPreference(
                    title = stringResource(R.string.settings_ui_motion_predictive),
                    summary = stringResource(
                        when {
                            !supportsPredictive -> R.string.settings_ui_motion_predictive_unavailable
                            selection.returnStyle == Style.NONE ->
                                R.string.settings_ui_motion_predictive_no_animation
                            else -> R.string.settings_ui_motion_predictive_summary
                        },
                    ),
                    checked = supportsPredictive && selection.predictiveBack,
                    enabled = supportsPredictive && selection.returnStyle != Style.NONE,
                    onCheckedChange = { checked ->
                        update(selection.copy(predictiveBack = checked), recreate = true)
                    },
                )
                Text(
                    text = stringResource(R.string.settings_ui_motion_info),
                    modifier = Modifier.padding(16.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        item { SmallTitle(text = stringResource(R.string.settings_ui_motion_reset_category)) }
        item {
            Card(
                modifier = Modifier.padding(12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                ArrowPreference(
                    title = stringResource(R.string.settings_ui_motion_reset),
                    onClick = {
                        val defaults = SettingsUiMotionPreferences.Selection()
                        update(
                            defaults,
                            recreate = selection.predictiveBack != defaults.predictiveBack,
                        )
                    },
                )
            }
        }
    }
}