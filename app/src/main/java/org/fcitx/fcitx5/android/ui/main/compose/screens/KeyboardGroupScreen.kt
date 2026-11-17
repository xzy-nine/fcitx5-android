/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.screens

import android.content.Intent
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.ui.main.compose.settings.ManagedPrefsScreen
import org.fcitx.fcitx5.android.ui.main.settings.voice.JsVoicePluginsActivity
import org.fcitx.fcitx5.android.ui.main.settings.behavior.ButtonsCustomizerActivity
import org.fcitx.fcitx5.android.ui.main.settings.behavior.PopupEditorActivity
import org.fcitx.fcitx5.android.ui.main.settings.behavior.TextKeyboardLayoutEditorActivity
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 键盘设置的分组编号。 */
enum class KeyboardGroup(val index: Int) {
    Layout(0), Behavior(1), Feedback(2), Toolbar(3), Editors(4), Candidate(5), Voice(6);

    val titleRes: Int
        get() = when (this) {
            Layout -> R.string.keyboard_category_layout
            Behavior -> R.string.keyboard_category_behavior
            Feedback -> R.string.keyboard_category_feedback
            Toolbar -> R.string.keyboard_category_toolbar
            Editors -> R.string.keyboard_category_editors
            Candidate -> R.string.keyboard_category_candidate
            Voice -> R.string.keyboard_category_voice
        }

    val keys: Set<String>
        get() = when (this) {
            Layout -> setOf(
                "keyboard_height_percent", "keyboard_side_padding",
                "keyboard_bottom_padding", "expand_keypress_area",
            )
            Behavior -> setOf(
                "popup_on_key_press", "keyboard_long_press_delay",
                "swipe_symbol_behavior", "keep_keyboard_letters_uppercase",
                "reset_keyboard_on_focus_change",
                "space_long_press_behavior", "space_key_label_mode",
                "space_swipe_move_cursor", "show_lang_switch_key",
            )
            Feedback -> setOf(
                "haptic_on_keypress", "haptic_on_keyup", "haptic_on_repeat",
                "button_vibration_press_milliseconds", "button_vibration_press_amplitude",
                "sound_on_keypress", "button_sound_volume", "custom_key_sound",
            )
            Toolbar -> setOf(
                "toolbar_height_percent",
                "inline_suggestions", "toolbar_num_row_on_password",
            )
            Candidate -> setOf(
                "horizontal_candidate_style", "horizontal_candidate_overflow_scroll",
                "highlight_first_candidate", "show_candidate_index_badge",
                "candidate_index_badge_position", "candidate_index_badge_content",
                "expanded_candidate_style",
                "expanded_candidate_grid_span_count_portrait",
            )
            Voice -> setOf("show_voice_input_button", "preferred_voice_input")
            Editors -> emptySet()
        }

    companion object {
        const val GROUP_LAYOUT = 0
        const val GROUP_BEHAVIOR = 1
        const val GROUP_FEEDBACK = 2
        const val GROUP_TOOLBAR = 3
        const val GROUP_EDITORS = 4
        const val GROUP_CANDIDATE = 5
        const val GROUP_VOICE = 6

        fun fromIndex(index: Int): KeyboardGroup? = entries.firstOrNull { it.index == index }
    }
}

/**
 * 键盘设置分组页。
 *
 * 偏好项按 [KeyboardGroup.keys] 跨原分组挑选后渲染；编辑器与反馈组另有
 * 独立的工具入口，它们不对应任何偏好字段。
 */
@Composable
fun KeyboardGroupScreen(
    groupIndex: Int,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val group = KeyboardGroup.fromIndex(groupIndex)
    if (group == null) {
        PageScaffold(title = "", onBack = onBack) { }
        return
    }

    ManagedPrefsScreen(
        category = AppPrefs.getInstance().keyboard,
        onBack = onBack,
        keyFilter = group.keys.ifEmpty { emptySet() },
        extraContent = {
            val tools = when (group) {
                KeyboardGroup.Editors -> listOf(
                    R.string.text_keyboard_layout_manage_title to {
                        context.startActivity(
                            Intent(context, TextKeyboardLayoutEditorActivity::class.java),
                        )
                    },
                    R.string.edit_text_keyboard_layout to {
                        context.startActivity(
                            Intent(context, TextKeyboardLayoutEditorActivity::class.java),
                        )
                    },
                    R.string.edit_popup_preset to {
                        context.startActivity(Intent(context, PopupEditorActivity::class.java))
                    },
                )
                KeyboardGroup.Toolbar -> listOf(
                    R.string.edit_buttons to {
                        context.startActivity(Intent(context, ButtonsCustomizerActivity::class.java))
                    },
                )
                KeyboardGroup.Voice -> listOf(
                    R.string.js_voice_plugins_title to {
                        context.startActivity(JsVoicePluginsActivity.intent(context))
                    },
                )
                else -> emptyList()
            }
            if (tools.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier.padding(12.dp),
                        colors = CardDefaults.defaultColors(
                            color = MiuixTheme.colorScheme.surfaceContainerHighest,
                        ),
                    ) {
                        tools.forEach { (title, action) ->
                            ArrowPreference(
                                title = stringResource(title),
                                onClick = action,
                            )
                        }
                    }
                }
            }
        },
    )
}