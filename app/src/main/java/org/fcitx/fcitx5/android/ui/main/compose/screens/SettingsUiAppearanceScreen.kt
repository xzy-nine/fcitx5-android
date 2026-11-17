/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.screens

import android.app.Activity
import android.os.Handler
import android.os.Looper
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
import org.fcitx.fcitx5.android.data.prefs.SettingsUiPreferences
import org.fcitx.fcitx5.android.data.theme.SettingsKeyboardColors
import org.fcitx.fcitx5.android.ui.main.compose.AppRoute
import org.fcitx.fcitx5.android.ui.main.compose.dialog.SimpleConfirmDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置界面外观页。
 *
 * 主题模式、纯黑、跟随键盘配色、动态取色、调色板、配方与对比度。
 * 这些值在宿主 Activity 创建时决定，改动后需要重建界面才能生效。
 */
@Composable
fun SettingsUiAppearanceScreen(
    onBack: () -> Unit,
    onNavigate: (AppRoute) -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var selection by remember { mutableStateOf(SettingsUiPreferences.read(context)) }
    var confirmReset by remember { mutableStateOf(false) }

    // 重建合并到下一轮消息：一次点击只需重建一次，连续点击不会叠加。
    val handler = remember { Handler(Looper.getMainLooper()) }
    val rebuild = remember {
        Runnable {
            val host = activity ?: return@Runnable
            if (!host.isFinishing && !host.isDestroyed) host.recreate()
        }
    }

    fun commit(next: SettingsUiPreferences.Selection) {
        if (next == selection) return
        SettingsUiPreferences.save(context, next)
        // 读回一次，页面只保留引擎接受的值。
        selection = SettingsUiPreferences.read(context)
        handler.removeCallbacks(rebuild)
        handler.post(rebuild)
    }

    // 搜索跳转按这些键滚动到本页对应行；取值统一走 SettingsUiPreferences。
    val highlightKeys = listOf(
        "settings_ui_mode" to R.string.settings_ui_mode_title,
        "settings_ui_dynamic_colors" to R.string.settings_ui_dynamic_colors,
        "settings_ui_recipe" to R.string.settings_ui_recipe_title,
        "settings_ui_contrast" to R.string.settings_ui_contrast_title,
    )

    val paletteOptions = listOf(
        SettingsUiPreferences.PALETTE_COMPAT to R.string.settings_ui_palette_compat,
        SettingsUiPreferences.PALETTE_SEA_SALT to R.string.settings_ui_palette_sea_salt,
        SettingsUiPreferences.PALETTE_PINE to R.string.settings_ui_palette_pine,
        SettingsUiPreferences.PALETTE_BERRY to R.string.settings_ui_palette_berry,
        SettingsUiPreferences.PALETTE_AMBER to R.string.settings_ui_palette_amber,
        SettingsUiPreferences.PALETTE_WISTERIA to R.string.settings_ui_palette_wisteria,
        SettingsUiPreferences.PALETTE_GRAPHITE to R.string.settings_ui_palette_graphite,
    )
    val recipeOptions = listOf(
        SettingsUiPreferences.RECIPE_CMF_2026 to R.string.settings_ui_recipe_cmf2026,
        SettingsUiPreferences.RECIPE_TONAL_2025 to R.string.settings_ui_recipe_tonal2025,
        SettingsUiPreferences.RECIPE_VIBRANT_2025 to R.string.settings_ui_recipe_vibrant2025,
        SettingsUiPreferences.RECIPE_NEUTRAL_2025 to R.string.settings_ui_recipe_neutral2025,
        SettingsUiPreferences.RECIPE_EXPRESSIVE_2025 to R.string.settings_ui_recipe_expressive2025,
        SettingsUiPreferences.RECIPE_CLASSIC_2021 to R.string.settings_ui_recipe_classic2021,
    )

    val keyboardColors = SettingsUiPreferences.usesKeyboardColors(selection)
    val dynamic = selection.palette == SettingsUiPreferences.PALETTE_SYSTEM
    // 一次点击切到下一个取值；选项彼此互斥，无需额外选择器。
    fun <T> cycle(list: List<T>, current: T): T {
        val index = list.indexOf(current)
        return list[(if (index < 0) 0 else index + 1) % list.size]
    }

    PageScaffold(title = stringResource(R.string.settings_ui_appearance), onBack = onBack) {
        // 键与标题的对应关系供搜索定位使用，本页按分组顺序渲染。
        item { if (highlightKeys.isEmpty()) Text("") }
        item { SmallTitle(text = stringResource(R.string.settings_ui_display)) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                ArrowPreference(
                    title = stringResource(R.string.settings_ui_mode_title),
                    summary = stringResource(
                        when (selection.mode) {
                            SettingsUiPreferences.MODE_LIGHT -> R.string.settings_ui_mode_light
                            SettingsUiPreferences.MODE_DARK -> R.string.settings_ui_mode_dark
                            else -> R.string.settings_ui_mode_system
                        },
                    ),
                    onClick = {
                        val modes = listOf(
                            SettingsUiPreferences.MODE_SYSTEM,
                            SettingsUiPreferences.MODE_LIGHT,
                            SettingsUiPreferences.MODE_DARK,
                        )
                        commit(selection.copy(mode = cycle(modes, selection.mode)))
                    },
                )
                SwitchPreference(
                    title = stringResource(R.string.settings_ui_pure_black),
                    summary = stringResource(R.string.settings_ui_pure_black_summary),
                    checked = selection.pureBlack,
                    enabled = selection.mode != SettingsUiPreferences.MODE_LIGHT,
                    onCheckedChange = { commit(selection.copy(pureBlack = it)) },
                )
            }
        }

        item { SmallTitle(text = stringResource(R.string.settings_ui_color_title)) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                val keyboardAvailable = SettingsKeyboardColors.isAvailable()
                SwitchPreference(
                    title = stringResource(R.string.settings_ui_follow_keyboard),
                    summary = stringResource(
                        if (keyboardAvailable) R.string.settings_ui_follow_keyboard_summary
                        else R.string.settings_ui_follow_keyboard_unavailable,
                    ),
                    checked = keyboardColors,
                    enabled = keyboardAvailable,
                    onCheckedChange = { commit(selection.copy(followKeyboardTheme = it)) },
                )
                if (!keyboardColors && SettingsUiPreferences.systemDynamicColorsAvailable()) {
                    SwitchPreference(
                        title = stringResource(R.string.settings_ui_dynamic_colors),
                        summary = stringResource(R.string.settings_ui_dynamic_colors_summary),
                        checked = dynamic,
                        onCheckedChange = { enabled ->
                            commit(
                                if (enabled) {
                                    selection.copy(
                                        palette = SettingsUiPreferences.PALETTE_SYSTEM,
                                        manualPalette = selection.palette.takeUnless {
                                            it == SettingsUiPreferences.PALETTE_SYSTEM
                                        } ?: selection.manualPalette,
                                    )
                                } else selection.copy(palette = selection.manualPalette),
                            )
                        },
                    )
                }
                if (!keyboardColors && !dynamic) {
                    ArrowPreference(
                        title = stringResource(R.string.settings_ui_palette_title),
                        summary = stringResource(
                            paletteOptions.firstOrNull { it.first == selection.palette }?.second
                                ?: R.string.settings_ui_palette_compat,
                        ),
                        onClick = {
                            val next = cycle(paletteOptions.map { it.first }, selection.palette)
                            commit(selection.copy(palette = next, manualPalette = next))
                        },
                    )
                    if (selection.palette != SettingsUiPreferences.PALETTE_COMPAT) {
                        ArrowPreference(
                            title = stringResource(R.string.settings_ui_recipe_title),
                            summary = stringResource(
                                recipeOptions.firstOrNull { it.first == selection.recipe }?.second
                                    ?: R.string.settings_ui_recipe_cmf2026,
                            ),
                            onClick = {
                                commit(
                                    selection.copy(
                                        recipe = cycle(recipeOptions.map { it.first }, selection.recipe),
                                    ),
                                )
                            },
                        )
                    }
                }
                if (keyboardColors || !dynamic) {
                    ArrowPreference(
                        title = stringResource(R.string.settings_ui_contrast_title),
                        summary = stringResource(
                            when (selection.contrast) {
                                SettingsUiPreferences.CONTRAST_MEDIUM ->
                                    R.string.settings_ui_contrast_medium
                                SettingsUiPreferences.CONTRAST_HIGH ->
                                    R.string.settings_ui_contrast_high
                                else -> R.string.settings_ui_contrast_standard
                            },
                        ),
                        onClick = {
                            val options = listOf(
                                SettingsUiPreferences.CONTRAST_STANDARD,
                                SettingsUiPreferences.CONTRAST_MEDIUM,
                                SettingsUiPreferences.CONTRAST_HIGH,
                            )
                            commit(selection.copy(contrast = cycle(options, selection.contrast)))
                        },
                    )
                }
            }
        }

        item { SmallTitle(text = stringResource(R.string.settings_ui_interaction)) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                ArrowPreference(
                    title = stringResource(R.string.settings_ui_motion_title),
                    summary = stringResource(R.string.settings_ui_motion_summary),
                    onClick = { onNavigate(AppRoute.SettingsUiMotion) },
                )
                ArrowPreference(
                    title = stringResource(R.string.settings_ui_restore_default),
                    onClick = { confirmReset = true },
                )
                Text(
                    text = stringResource(R.string.settings_ui_intro),
                    modifier = Modifier.padding(16.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }

    if (confirmReset) {
        SimpleConfirmDialog(
            title = stringResource(R.string.settings_ui_restore_default),
            message = stringResource(R.string.settings_ui_restore_default_message),
            onConfirm = {
                confirmReset = false
                commit(SettingsUiPreferences.defaultSelection())
            },
            onDismiss = { confirmReset = false },
        )
    }
}