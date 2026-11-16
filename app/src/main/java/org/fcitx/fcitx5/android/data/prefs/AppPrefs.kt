/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.InputFeedbacks.InputFeedbackMode
import org.fcitx.fcitx5.android.data.handwriting.HandwritingEngineKind
import org.fcitx.fcitx5.android.data.voice.VoiceModelCatalog
import org.fcitx.fcitx5.android.input.bar.ToolbarLayout
import org.fcitx.fcitx5.android.input.candidates.CandidateIndexBadgeContent
import org.fcitx.fcitx5.android.input.candidates.CandidateIndexBadgePosition
import org.fcitx.fcitx5.android.input.candidates.expanded.ExpandedCandidateStyle
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesOrientation
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesVirtualKeyboardPosition
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode
import org.fcitx.fcitx5.android.input.keyboard.KeyboardHeightPercentBase
import org.fcitx.fcitx5.android.input.keyboard.SpaceKeyLabelMode
import org.fcitx.fcitx5.android.input.keyboard.SpaceLongPressBehavior
import org.fcitx.fcitx5.android.input.keyboard.SwipeSymbolDirection
import org.fcitx.fcitx5.android.input.editing.TextEditingStyle
import org.fcitx.fcitx5.android.input.config.UserConfigFiles
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.picker.SymbolCatalogType
import org.fcitx.fcitx5.android.input.picker.SymbolPanelStyle
import org.fcitx.fcitx5.android.input.popup.EmojiModifier
import org.fcitx.fcitx5.android.utils.DeviceUtil
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.vibrator

class AppPrefs(private val sharedPreferences: SharedPreferences) {

    inner class Internal : ManagedPreferenceInternal(sharedPreferences) {
        val firstRun = bool("first_run", true)
        val lastSymbolLayout = string("last_symbol_layout", PickerWindow.Key.Symbol.name)
        val lastPickerType = string("last_picker_type", PickerWindow.Key.Emoji.name)
        val verboseLog = bool("verbose_log", false)
        val pid = int("pid", 0)
        val editorInfoInspector = bool("editor_info_inspector", false)
        val needNotifications = bool("need_notifications", true)
        val floatingKeyboardWidthPortraitRatio = float("floating_keyboard_width_portrait_ratio", 0f)
        val floatingKeyboardWidthLandscapeRatio = float("floating_keyboard_width_landscape_ratio", 0f)
        val floatingKeyboardHeightPortraitRatio = float("floating_keyboard_height_portrait_ratio", 0f)
        val floatingKeyboardHeightLandscapeRatio = float("floating_keyboard_height_landscape_ratio", 0f)
        // legacy single (orientation-agnostic) ratio prefs kept for one-time migration
        val floatingKeyboardWidthRatio = float("floating_keyboard_width_ratio", 0f)
        val floatingKeyboardHeightRatio = float("floating_keyboard_height_ratio", 0f)
        // legacy px prefs kept for one-time migration to the ratio-based values above
        val floatingKeyboardWidthLegacy = int("floating_keyboard_width", 0)
        val floatingKeyboardHeightLegacy = int("floating_keyboard_height", 0)
        val floatingKeyboardXPortraitRatio = float("floating_keyboard_x_portrait_ratio", -1f)
        val floatingKeyboardYPortraitRatio = float("floating_keyboard_y_portrait_ratio", -1f)
        val floatingKeyboardXLandscapeRatio = float("floating_keyboard_x_landscape_ratio", -1f)
        val floatingKeyboardYLandscapeRatio = float("floating_keyboard_y_landscape_ratio", -1f)
        // legacy px prefs kept for one-time migration to the ratio-based values above
        val floatingKeyboardXPortrait = int("floating_keyboard_x_portrait", -1)
        val floatingKeyboardYPortrait = int("floating_keyboard_y_portrait", -1)
        val floatingKeyboardXLandscape = int("floating_keyboard_x_landscape", -1)
        val floatingKeyboardYLandscape = int("floating_keyboard_y_landscape", -1)
        val oneHandOnRightPortrait = bool("one_hand_on_right_portrait", true)
        val oneHandOnRightLandscape = bool("one_hand_on_right_landscape", true)
        val floatingModeEnabled = bool("floating_mode_enabled", false)
        val autoHideFloatingMoveHandle = bool("auto_hide_floating_move_handle", false)
        val oneHandModeEnabled = bool("one_hand_mode_enabled", false)

        /**
         * 横屏时自动使用浮动键盘（竖屏恢复停靠）。
         *
         * 与 [floatingModeEnabled] 是两回事：后者记录**用户手动**选择的键盘形态，
         * 前者只是一个「横屏自动切过去」的规则。因此自动切换只改运行时的 `isFloating`，
         * 不写回 [floatingModeEnabled]——否则一次横屏就把用户的选择永久改写成浮动，
         * 转回竖屏再也恢复不了停靠。
         */
        val autoFloatingLandscape = bool("auto_floating_landscape", false)
        val oneHandKeyboardWidthPortraitRatio = float("one_hand_keyboard_width_portrait_ratio", 0f)
        val oneHandKeyboardWidthLandscapeRatio = float("one_hand_keyboard_width_landscape_ratio", 0f)
        // legacy single (orientation-agnostic) ratio pref kept for one-time migration
        val oneHandKeyboardWidthRatio = float("one_hand_keyboard_width_ratio", 0f)
        // legacy px pref kept for one-time migration to the ratio-based value above
        val oneHandKeyboardWidthLegacy = int("one_hand_keyboard_width", 0)

        // Settings initialization flag
        val settingsInitialized = bool("settings_initialized", false)
        val splitKeyboardMigrated = bool("split_keyboard_migrated", false)
        val lastShareReceiveDirectory = string("last_share_receive_directory", "")
        val lastShareReceiveDirectoryRemembered = bool("last_share_receive_directory_remembered", false)
        // A single provider/app pair keeps selection atomic and stays in the existing app backup.
        val preferredJsApp = string("preferred_js_app", "")
    }

    inner class Advanced : ManagedPreferenceCategory(R.string.advanced, sharedPreferences) {
        val ignoreSystemCursor = switch(R.string.ignore_sys_cursor, "ignore_system_cursor", false)
        val disableAnimation = switch(R.string.disable_animation, "disable_animation", false)
        val vivoKeypressWorkaround = switch(
            R.string.vivo_keypress_workaround,
            "vivo_keypress_workaround",
            // Some vivo and Xiaomi input windows can dispatch a key gesture more than once.
            DeviceUtil.isVivoOriginOS || DeviceUtil.isMIUI
        )
        val ignoreSystemWindowInsets = switch(
            R.string.ignore_system_window_insets, "ignore_system_window_insets", false
        )
        val keyboardHeightPercentBase = enumList(
            R.string.keyboard_height_percent_base,
            "keyboard_height_percent_base",
            // Base the height percent on the real screen size (Display.getRealSize)
            // instead of Resources.getDisplayMetrics, so the same percentage yields
            // a keyboard height that matches the physical screen.
            KeyboardHeightPercentBase.RealSize
        )
    }

    inner class Keyboard : ManagedPreferenceCategory(R.string.virtual_keyboard, sharedPreferences) {
        val hapticOnKeyPress =
            enumList(
                R.string.button_haptic_feedback,
                "haptic_on_keypress",
                InputFeedbackMode.FollowingSystem
            )
        val hapticOnKeyUp = switch(
            R.string.button_up_haptic_feedback,
            "haptic_on_keyup",
            true
        ) { hapticOnKeyPress.getValue() != InputFeedbackMode.Disabled }
        val hapticOnRepeat = switch(R.string.haptic_on_repeat, "haptic_on_repeat", false)

        val buttonPressVibrationMilliseconds: ManagedPreference.PInt
        val buttonLongPressVibrationMilliseconds: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.button_vibration_milliseconds,
                R.string.button_press,
                "button_vibration_press_milliseconds",
                2,
                R.string.button_long_press,
                "button_vibration_long_press_milliseconds",
                24,
                0,
                100,
                "ms",
                defaultLabel = R.string.system_default
            ) { hapticOnKeyPress.getValue() != InputFeedbackMode.Disabled }
            buttonPressVibrationMilliseconds = primary
            buttonLongPressVibrationMilliseconds = secondary
        }

        val buttonPressVibrationAmplitude: ManagedPreference.PInt
        val buttonLongPressVibrationAmplitude: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.button_vibration_amplitude,
                R.string.button_press,
                "button_vibration_press_amplitude",
                255,
                R.string.button_long_press,
                "button_vibration_long_press_amplitude",
                255,
                0,
                255,
                defaultLabel = R.string.system_default
            ) {
                (hapticOnKeyPress.getValue() != InputFeedbackMode.Disabled)
                        // hide this if using default duration
                        && (buttonPressVibrationMilliseconds.getValue() != 0 || buttonLongPressVibrationMilliseconds.getValue() != 0)
                        && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && appContext.vibrator.hasAmplitudeControl())
            }
            buttonPressVibrationAmplitude = primary
            buttonLongPressVibrationAmplitude = secondary
        }

        val soundOnKeyPress = enumList(
            R.string.button_sound,
            "sound_on_keypress",
            InputFeedbackMode.FollowingSystem
        )
        val soundOnKeyPressVolume = int(
            R.string.button_sound_volume,
            "button_sound_volume",
            0,
            0,
            100,
            "%",
            defaultLabel = R.string.system_default
        ) {
            soundOnKeyPress.getValue() != InputFeedbackMode.Disabled
        }
        val customKeySound = ManagedPreference.PString(
            sharedPreferences,
            "custom_key_sound",
            ""
        ).apply { register() }
        val focusChangeResetKeyboard =
            switch(R.string.reset_keyboard_on_focus_change, "reset_keyboard_on_focus_change", true)
        val expandToolbarByDefault =
            switch(R.string.expand_toolbar_by_default, "expand_toolbar_by_default", true)
        val inlineSuggestions = switch(R.string.inline_suggestions, "inline_suggestions", true)
        val toolbarNumRowOnPassword =
            switch(R.string.toolbar_num_row_on_password, "toolbar_num_row_on_password", true)
        val popupOnKeyPress = switch(R.string.popup_on_key_press, "popup_on_key_press", true)
        val keepLettersUppercase = switch(
            R.string.keep_keyboard_letters_uppercase,
            "keep_keyboard_letters_uppercase",
            true
        )

        val showVoiceInputButton =
            switch(R.string.show_voice_input_button, "show_voice_input_button", false)
        // 长按空格也会使用首选语音输入，不依赖工具栏按钮是否显示。
        val preferredVoiceInput = voiceInputPreference(
            R.string.preferred_voice_input, "preferred_voice_input", ""
        )

        val expandKeypressArea =
            switch(R.string.expand_keypress_area, "expand_keypress_area", false)
        val splitKeyboard =
            switch(R.string.split_keyboard, "split_keyboard", false)
        val splitKeyboardBlankRatio: ManagedPreference.PInt
        val splitKeyboardBlankRatioLandscape: ManagedPreference.PInt
        val splitKeyboardThreshold: ManagedPreference.PFloat

        init {
            val (primary, secondary) = twinInt(
                R.string.split_keyboard_blank_ratio,
                R.string.portrait,
                "split_keyboard_blank_ratio",
                30,
                R.string.landscape,
                "split_keyboard_blank_ratio_landscape",
                30,
                0,
                60,
                "%"
            )
            splitKeyboardBlankRatio = primary
            splitKeyboardBlankRatioLandscape = secondary
        }

        init {
            splitKeyboardThreshold = float(
                R.string.split_keyboard_threshold,
                "split_keyboard_threshold",
                1.5f,
                1.0f,
                4.0f,
                "x",
                step = 0.01f,
                decimals = 2
            )
        }
        val swipeSymbolDirection = enumList(
            R.string.swipe_symbol_behavior,
            "swipe_symbol_behavior",
            SwipeSymbolDirection.Up
        )
        val longPressDelay = int(
            R.string.keyboard_long_press_delay,
            "keyboard_long_press_delay",
            300,
            100,
            700,
            "ms",
            10
        )
        val spaceKeyLongPressBehavior = enumList(
            R.string.space_long_press_behavior,
            "space_long_press_behavior",
            SpaceLongPressBehavior.VoiceInput
        )
        val spaceKeyLabelMode = enumList(
            R.string.space_key_label_mode,
            "space_key_label_mode",
            SpaceKeyLabelMode.Default
        )
        val spaceSwipeMoveCursor =
            switch(R.string.space_swipe_move_cursor, "space_swipe_move_cursor", true)
        val showLangSwitchKey =
            switch(R.string.show_lang_switch_key, "show_lang_switch_key", true)
        val textKeyboardLayoutProfile = ManagedPreference.PString(
            sharedPreferences,
            "text_keyboard_layout_profile",
            "default"
        ).apply { register() }
        /**
         * 数字输入框使用的自定义布局键（可为任意布局名或 ime:submode，如 rime:wanxiang）。
         * 留空表示使用内置数字键盘。仅存于应用设置，与布局文件解耦。
         */
        val numericLayoutOverride = ManagedPreference.PString(
            sharedPreferences,
            "numeric_layout_override",
            ""
        ).apply { register() }

        val keyboardHeightPercent: ManagedPreference.PInt
        val keyboardHeightPercentLandscape: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.keyboard_height,
                R.string.portrait,
                "keyboard_height_percent",
                30,
                R.string.landscape,
                "keyboard_height_percent_landscape",
                39,
                10,
                90,
                "%"
            )
            keyboardHeightPercent = primary
            keyboardHeightPercentLandscape = secondary
        }

        val toolbarHeight: ManagedPreference.PInt
        val toolbarHeightLandscape: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.toolbar_height,
                R.string.portrait,
                "toolbar_height",
                40,
                R.string.landscape,
                "toolbar_height_landscape",
                60,
                20,
                80,
                "dp"
            )
            toolbarHeight = primary
            toolbarHeightLandscape = secondary
        }

        val keyboardSidePadding: ManagedPreference.PInt
        val keyboardSidePaddingLandscape: ManagedPreference.PInt

        init {
            val dm = appContext.resources.displayMetrics
            val shortEdgeDp = (minOf(dm.widthPixels, dm.heightPixels) / dm.density).toInt()
            val longEdgeDp = (maxOf(dm.widthPixels, dm.heightPixels) / dm.density).toInt()
            // Keep keyboard width >= 1/2 screen width: sidePadding <= width/4.
            val portraitMaxPaddingDp = (shortEdgeDp / 4).coerceAtLeast(0)
            val landscapeMaxPaddingDp = (longEdgeDp / 4).coerceAtLeast(0)
            val sidePaddingMaxDp = maxOf(portraitMaxPaddingDp, landscapeMaxPaddingDp)
            val (primary, secondary) = twinInt(
                R.string.keyboard_side_padding,
                R.string.portrait,
                "keyboard_side_padding",
                5,
                R.string.landscape,
                "keyboard_side_padding_landscape",
                17,
                0,
                sidePaddingMaxDp,
                "dp"
            )
            keyboardSidePadding = primary
            keyboardSidePaddingLandscape = secondary
        }

        val keyboardBottomPadding: ManagedPreference.PInt
        val keyboardBottomPaddingLandscape: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.keyboard_bottom_padding,
                R.string.portrait,
                "keyboard_bottom_padding",
                22,
                R.string.landscape,
                "keyboard_bottom_padding_landscape",
                8,
                0,
                100,
                "dp"
            )
            keyboardBottomPadding = primary
            keyboardBottomPaddingLandscape = secondary
        }

        /**
         * 工具栏（Kawaii Bar）自身尺寸百分比：栏高、图标、栏内按钮与文字标签一起缩放。
         *
         * 工具栏高度长期写死 40dp，用户无法让它更高更醒目，这个百分比就是把高度交出去。
         *
         * **候选项字号不在此列**。候选字由「字体设定」（`fontset.json` 的 `cand_font`）独立控制，
         * 那是用户为"字多大"专门准备的旋钮。曾让两者联动，结果是只想加高工具栏的人被顺手
         * 放大了字，还得回头调字体设定抵消；`AutoScaleTextView` 的 Proportional 模式又**只缩
         * 不放**，调大 `cand_font` 也顶不出栏高。两个旋钮必须分开。
         * 想让候选字更大：先把工具栏调高（给字腾出高度），再在「字体设定」里调大 `cand_font`。
         *
         * 换算逻辑在 [org.fcitx.fcitx5.android.input.bar.ToolbarMetrics]（纯函数，有单测）。
         * 下限 80% 而不是更小：工具栏同时承载状态区/隐藏键盘等按钮，再小图标就不可点了。
         */
        val toolbarHeightPercent = int(
            R.string.toolbar_height_percent,
            "toolbar_height_percent",
            100,
            80,
            200,
            "%",
            10
        )

        // ===== Split keyboard settings =====
        // Note: Threshold and gap are managed exclusively via SplitKeyboardCalibrationActivity
        // They are stored in Keyboard category to trigger InputView refresh when changed
        val splitKeyboardEnabled = switch(
            R.string.split_keyboard_enabled,
            "split_keyboard_enabled",
            true  // Default enabled; may be adjusted based on device type on first install
        )

        // Internal split keyboard settings (no UI, managed via calibration activity)
        val splitKeyboardGapPercent = ManagedPreference.PInt(sharedPreferences, "split_keyboard_gap_percent", 20).apply { register() }

        // When enabled, use landscape layout parameters (height/padding/etc.) while split keyboard is active
        val splitKeyboardUseLandscapeLayout = switch(
            R.string.split_keyboard_use_landscape_layout,
            "split_keyboard_use_landscape_layout",
            false
        )

        // 分体时，对奇数字符行（如 QWERTY 的 asdfghjkl / zxcvbnm）把几何中间键复制一份，
        // 左右两半各保留一枚（如 g、v），避免中间键只落在一侧够不到。默认开启。
        val splitKeyboardDuplicateMiddleKey = switch(
            R.string.split_keyboard_duplicate_middle,
            "split_keyboard_duplicate_middle",
            true,
            R.string.split_keyboard_duplicate_middle_summary
        )

        // 分体时是否强制左右两半在中缝处对齐（各自占满 (1-中缝)/2 的宽度）。
        //
        // 开启（默认，也是既有行为）：两侧都缩放到恰好半宽，中缝严格居中、上下行对齐。
        // 代价是**按键宽度被强行改写**——某一侧键多（或用户用 splitAfter 把断点大幅
        // 挪偏）时，该侧每一枚键都要被压窄到刚好填满半宽，与另一侧和合体状态下的键宽
        // 都不一致。
        //
        // 关闭：每枚键都保持**合体时的宽度**（弹性键同样只按行内剩余空间算，不受分体
        // 影响），两侧各自靠外沿固定，中缝不再是预留的固定宽度、而是两侧排完后**剩下的
        // 空间**。于是往一行里加空白占位键就能把这一侧往中缝方向推——占位键占多宽就推
        // 多远，且不会影响同行其它键的宽度（见 SplitRowWidths.unalignedWidths）。
        // 代价是中缝实际宽度随行内容变化、各行断点位置可能不齐。
        val splitKeyboardAlignHalves = switch(
            R.string.split_keyboard_align_halves,
            "split_keyboard_align_halves",
            true,
            R.string.split_keyboard_align_halves_summary
        )

        val horizontalCandidateStyle = enumList(
            R.string.horizontal_candidate_style,
            "horizontal_candidate_style",
            HorizontalCandidateMode.AlwaysFillWidth
        )
        val horizontalCandidateSwipe = switch(
            R.string.horizontal_candidate_swipe,
            "horizontal_candidate_swipe",
            true
        )
        val horizontalCandidateOverflowScroll = switch(
            R.string.horizontal_candidate_overflow_scroll,
            "horizontal_candidate_overflow_scroll",
            true,
            R.string.horizontal_candidate_overflow_scroll_summary
        )
        val highlightFirstCandidate = switch(
            R.string.highlight_first_candidate,
            "highlight_first_candidate",
            false,
            R.string.highlight_first_candidate_summary
        )
        val showCandidateIndexBadge = switch(
            R.string.show_candidate_index_badge,
            "show_candidate_index_badge",
            false
        )
        val candidateIndexBadgePosition = enumList(
            R.string.candidate_index_badge_position,
            "candidate_index_badge_position",
            CandidateIndexBadgePosition.TopLeft,
            enableUiOn = { showCandidateIndexBadge.getValue() }
        )
        val candidateIndexBadgeContent = enumList(
            R.string.candidate_index_badge_content,
            "candidate_index_badge_content",
            CandidateIndexBadgeContent.EngineLabel,
            enableUiOn = { showCandidateIndexBadge.getValue() }
        )
        val expandedCandidateStyle = enumList(
            R.string.expanded_candidate_style,
            "expanded_candidate_style",
            ExpandedCandidateStyle.Grid
        )
        val expandedCandidateGridSpanCount: ManagedPreference.PInt
        val expandedCandidateGridSpanCountLandscape: ManagedPreference.PInt
        val candidateDivider: ManagedPreference.PBool

        init {
            val (primary, secondary) = twinInt(
                R.string.expanded_candidate_grid_span_count,
                R.string.portrait,
                "expanded_candidate_grid_span_count_portrait",
                 6,
                R.string.landscape,
                "expanded_candidate_grid_span_count_landscape",
                 8,
                 4,
                 12,
            )
            expandedCandidateGridSpanCount = primary
            expandedCandidateGridSpanCountLandscape = secondary
            candidateDivider = switch(
                R.string.candidate_divider,
                "candidate_divider",
                false
            )
        }

        init {
            groups = listOf(
                SubGroup(R.string.group_key_feedback, listOf(
                    hapticOnKeyPress.key,
                    hapticOnKeyUp.key,
                    hapticOnRepeat.key,
                    buttonPressVibrationMilliseconds.key,
                    buttonLongPressVibrationMilliseconds.key,
                    buttonPressVibrationAmplitude.key,
                    buttonLongPressVibrationAmplitude.key,
                    soundOnKeyPress.key,
                    soundOnKeyPressVolume.key,
                )),
                SubGroup(R.string.group_toolbar, listOf(
                    expandToolbarByDefault.key,
                    toolbarNumRowOnPassword.key,
                    toolbarHeight.key,
                    toolbarHeightLandscape.key,
                    showLangSwitchKey.key,
                    inlineSuggestions.key,
                )),
                SubGroup(R.string.group_keys_layout, listOf(
                    focusChangeResetKeyboard.key,
                    popupOnKeyPress.key,
                    keepLettersUppercase.key,
                    expandKeypressArea.key,
                    keyboardHeightPercent.key,
                    keyboardHeightPercentLandscape.key,
                    keyboardSidePadding.key,
                    keyboardSidePaddingLandscape.key,
                    keyboardBottomPadding.key,
                    keyboardBottomPaddingLandscape.key,
                )),
                SubGroup(R.string.group_split_keyboard, listOf(
                    splitKeyboard.key,
                    splitKeyboardBlankRatio.key,
                    splitKeyboardBlankRatioLandscape.key,
                    splitKeyboardThreshold.key,
                )),
                SubGroup(R.string.group_key_gestures, listOf(
                    swipeSymbolDirection.key,
                    longPressDelay.key,
                    spaceKeyLongPressBehavior.key,
                    spaceSwipeMoveCursor.key,
                    preferredVoiceInput.key,
                )),
                SubGroup(R.string.group_candidate_style, listOf(
                    horizontalCandidateStyle.key,
                    horizontalCandidateSwipe.key,
                    candidateDivider.key,
                    expandedCandidateGridSpanCount.key,
                    expandedCandidateGridSpanCountLandscape.key,
                )),
            )
        }

        // custom: 工具栏按钮布局（两段式：docked = 工具栏可停靠行，pool = 更多页按钮池）。
        // 刻意不进任何 SubGroup —— 布局由 IME 内的更多页编辑，不在应用设置里暴露条目；
        // 键必须以逗号分隔的 ToolbarButton.id 形式存放，解码/自愈见 ToolbarLayout.sanitize。
        val toolbarDockedButtons: ManagedPreference.PString
        val toolbarPoolButtons: ManagedPreference.PString
        val toolbarShowUndoRedo: ManagedPreference.PBool

        init {
            toolbarDockedButtons = ManagedPreference.PString(
                sharedPreferences,
                "toolbar_docked_buttons",
                ToolbarLayout.encode(ToolbarLayout.DEFAULT_DOCKED)
            ).apply { register() }
            toolbarPoolButtons = ManagedPreference.PString(
                sharedPreferences,
                "toolbar_pool_buttons",
                ToolbarLayout.encode(ToolbarLayout.DEFAULT_POOL)
            ).apply { register() }
            toolbarShowUndoRedo = ManagedPreference.PBool(
                sharedPreferences,
                "toolbar_show_undo_redo",
                true
            ).apply { register() }
        }

    }

    inner class TextEditing :
        ManagedPreferenceCategory(R.string.text_editing, sharedPreferences) {
        private val foxyEnabled: () -> Boolean = {
            style.getValue() == TextEditingStyle.FoxySwipe
        }

        val style = enumList(
            R.string.text_editing_style,
            "text_editing_style",
            TextEditingStyle.Default
        )
        val cursorStepDp = int(
            R.string.text_editing_cursor_step,
            "text_editing_cursor_step_dp",
            18,
            4,
            80,
            "dp",
            enableUiOn = foxyEnabled
        )
        val cursorLongPressDelay = int(
            R.string.text_editing_cursor_long_press_delay,
            "text_editing_cursor_long_press_delay",
            300,
            100,
            1200,
            "ms",
            10,
            enableUiOn = foxyEnabled
        )

        val cursorPromptMove = ManagedPreference.PString(
            sharedPreferences,
            "text_editing_cursor_prompt_move",
            ""
        ).apply { register() }
        val cursorPromptLongPress = ManagedPreference.PString(
            sharedPreferences,
            "text_editing_cursor_prompt_long_press",
            ""
        ).apply { register() }
        val cursorPromptSelecting = ManagedPreference.PString(
            sharedPreferences,
            "text_editing_cursor_prompt_selecting",
            ""
        ).apply { register() }
        val cursorPromptReleaseSelection = ManagedPreference.PString(
            sharedPreferences,
            "text_editing_cursor_prompt_release_selection",
            ""
        ).apply { register() }
    }

    inner class Candidates : ManagedPreferenceCategory(R.string.candidates_window, sharedPreferences) {
        val mode = enumList(
            R.string.show_candidates_window,
            "show_candidates_window",
            FloatingCandidatesMode.InputDevice
        )

        val physicalKeyboardHorizontalCandidateBar = switch(
            R.string.physical_keyboard_horizontal_candidate_bar,
            "physical_keyboard_horizontal_candidate_bar",
            false,
            R.string.physical_keyboard_horizontal_candidate_bar_summary
        )

        val orientation = enumList(
            R.string.candidates_orientation,
            "candidates_window_orientation",
            FloatingCandidatesOrientation.Automatic
        )

        val virtualKeyboardPosition = enumList(
            R.string.candidates_position,
            "virtual_keyboard_candidates_position",
            FloatingCandidatesVirtualKeyboardPosition.TopLeft
        ) {
            mode.getValue() == FloatingCandidatesMode.Always
        }

        val windowMinWidth = int(
            R.string.candidates_window_min_width,
            "candidates_window_min_width",
            0,
            0,
            640,
            "dp",
            10
        )

        val windowPadding =
            int(R.string.candidates_window_padding, "candidates_window_padding", 4, 0, 32, "dp")

        val fontSize =
            int(R.string.candidates_font_size, "candidates_window_font_size", 20, 4, 64, "sp")

        val windowRadius =
            int(R.string.candidates_window_radius, "candidates_window_radius", 0, 0, 48, "dp")

        val candidateHighlightRadius =
            int(R.string.candidate_highlight_radius, "candidate_highlight_radius", 2, 0, 48, "dp")

        val itemPaddingVertical: ManagedPreference.PInt
        val itemPaddingHorizontal: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.candidates_padding,
                R.string.vertical,
                "candidates_item_padding_vertical",
                2,
                R.string.horizontal,
                "candidates_item_padding_horizontal",
                4,
                0,
                64,
                "dp"
            )
            itemPaddingVertical = primary
            itemPaddingHorizontal = secondary
        }
    }

    inner class Clipboard : ManagedPreferenceCategory(R.string.clipboard, sharedPreferences) {
        init {
            val legacyKey = "clipboard_limit"
            val localKey = "clipboard_limit_local"
            val remoteKey = "clipboard_limit_remote"
            val mediaKey = "clipboard_limit_media"
            if (!sharedPreferences.contains(localKey) ||
                !sharedPreferences.contains(remoteKey) ||
                !sharedPreferences.contains(mediaKey)
            ) {
                val legacyValue = if (sharedPreferences.contains(legacyKey)) {
                    sharedPreferences.getInt(legacyKey, 100)
                } else {
                    100
                }
                sharedPreferences.edit {
                    if (!sharedPreferences.contains(localKey)) putInt(localKey, legacyValue)
                    if (!sharedPreferences.contains(remoteKey)) putInt(remoteKey, legacyValue)
                    if (!sharedPreferences.contains(mediaKey)) putInt(mediaKey, legacyValue)
                    remove(legacyKey)
                }
            }
            val timeoutKey = "clipboard_item_timeout"
            val timeoutValue = sharedPreferences.getInt(timeoutKey, 30)
            if (timeoutValue < 0) {
                sharedPreferences.edit {
                    putInt(timeoutKey, 0)
                }
            }
        }

        val clipboardListening = switch(R.string.clipboard_listening, "clipboard_enable", true)
        val clipboardHistoryLimitLocal = int(
            R.string.clipboard_limit_local,
            "clipboard_limit_local",
            100,
            0,
            1000,
            step = 10,
        ) { clipboardListening.getValue() }
        val clipboardHistoryLimitRemote = int(
            R.string.clipboard_limit_remote,
            "clipboard_limit_remote",
            100,
            0,
            1000,
            step = 10,
        ) { clipboardListening.getValue() }
        val clipboardHistoryLimitMedia = int(
            R.string.clipboard_limit_media,
            "clipboard_limit_media",
            100,
            0,
            1000,
            step = 10,
        ) { clipboardListening.getValue() }
        val clipboardSuggestion = switch(
            R.string.clipboard_suggestion, "clipboard_suggestion", true
        ) { clipboardListening.getValue() }
        val clipboardItemTimeout = int(
            R.string.clipboard_suggestion_timeout,
            "clipboard_item_timeout",
            30,
            0,
            600,
            "s",
            step = 5
        ) { clipboardListening.getValue() && clipboardSuggestion.getValue() }
        val clipboardReturnAfterPaste = switch(
            R.string.clipboard_return_after_paste, "clipboard_return_after_paste", false
        ) { clipboardListening.getValue() }
        val clipboardMaskSensitive = switch(
            R.string.clipboard_mask_sensitive, "clipboard_mask_sensitive", true
        ) { clipboardListening.getValue() }
        val clipboardEditInsertSpace =
            ManagedPreference.PBool(sharedPreferences, "clipboard_edit_insert_space", true)
                .apply { register() }
    }

    inner class Broadcast : ManagedPreferenceCategory(R.string.broadcast_settings, sharedPreferences) {
        val enabled = switch(R.string.broadcast_enable, "broadcast_enable", false)
    }

    inner class Symbols : ManagedPreferenceCategory(R.string.emoji_and_symbols, sharedPreferences) {
        val hideUnsupportedEmojis = switch(
            R.string.hide_unsupported_emojis,
            "hide_unsupported_emojis",
            true
        )

        val defaultEmojiSkinTone = enumList(
            R.string.default_emoji_skin_tone,
            "default_emoji_skin_tone",
            EmojiModifier.SkinTone.Default,
        )

        // Custom: number keyboard symbol slider
        val symbolSliderVisibleCount = int(
            R.string.symbol_slider_visible_count,
            "symbol_slider_visible_count",
            3,
            min = 1,
            max = 5
        )
        /**
         * 符号 / 表情 / 颜文字面板的呈现风格，见 [SymbolPanelStyle]。
         *
         * 默认 [SymbolPanelStyle.Sidebar]（左侧分组栏）：沿用既有外观，老用户升级后
         * 第一眼不变；想换 Gboard 式底栏的用户自己到「便捷功能 → 表情和符号」切。
         */
        val symbolPanelStyle = enumList(
            R.string.symbol_panel_style,
            "symbol_panel_style",
            SymbolPanelStyle.Sidebar,
        )

        /**
         * 三份符号 catalog 当前选中的文件；[UserConfigFiles.SYMBOL_CATALOG_BUILTIN]
         * 表示使用 APK 内置数据，否则是用户自备 catalog 的文件名。
         *
         * 对应 Foxy 的 `foxy_symbol_catalogs`（键为 SYMBOLS/EMOJI/KAOMOJI，默认 `__builtin__`）。
         * 这里不注册 UI：选项来自目录扫描，用 [ManagedPreference.PString] + 自定义入口。
         */
        val symbolCatalogSymbols = ManagedPreference.PString(
            sharedPreferences, "symbol_catalog_symbols", UserConfigFiles.SYMBOL_CATALOG_BUILTIN
        ).apply { register() }

        val symbolCatalogEmoji = ManagedPreference.PString(
            sharedPreferences, "symbol_catalog_emoji", UserConfigFiles.SYMBOL_CATALOG_BUILTIN
        ).apply { register() }

        val symbolCatalogKaomoji = ManagedPreference.PString(
            sharedPreferences, "symbol_catalog_kaomoji", UserConfigFiles.SYMBOL_CATALOG_BUILTIN
        ).apply { register() }

        /** 按 catalog 种类取对应的选择偏好。 */
        fun catalogPreference(type: SymbolCatalogType): ManagedPreference.PString = when (type) {
            SymbolCatalogType.Symbols -> symbolCatalogSymbols
            SymbolCatalogType.Emoji -> symbolCatalogEmoji
            SymbolCatalogType.Kaomoji -> symbolCatalogKaomoji
        }
    }

    /**
     * custom: 语音输入。
     *
     * 离线引擎为官方 sherpa-onnx（Apache-2.0，见仓库根 NOTICE.md），在线平台为内置 provider
     * （火山引擎 / 小米 MiMo，见 `data/voice/online/`）。
     * 这些偏好同时被 IME 内的语音面板与应用内「语音输入」设置页读取。
     *
     * 注意：本地离线推理跑在独立 `:asr` 进程，该进程不初始化 AppPrefs，
     * 因此 `:asr` 侧只通过 AIDL 接收模型文件路径与音频采样，不读这些偏好。
     */
    inner class Voice : ManagedPreferenceCategory(R.string.voice_input, sharedPreferences) {
        val voiceInputEnabled = switch(
            R.string.voice_input_enabled,
            "voice_input_enabled",
            false,
            summary = R.string.voice_input_enabled_summary
        )

        val voiceUseLocal = switch(
            R.string.voice_use_local,
            "voice_use_local",
            true,
            summary = R.string.voice_use_local_summary
        ) { voiceInputEnabled.getValue() }

        val voiceSimpleChinese =
            switch(R.string.voice_simple_chinese, "voice_simple_chinese", true) {
                voiceInputEnabled.getValue()
            }

        val voiceMuteDuringRecording = switch(
            R.string.voice_mute_during_recording,
            "voice_mute_during_recording",
            false
        ) { voiceInputEnabled.getValue() }

        /**
         * 当前选中的在线识别平台 id（空 = 未选择，走第一个已配置的平台）。
         *
         * 平台由 app 侧内置实现（火山引擎 / 小米 MiMo），
         * 因此这里直接存 provider id（见 `data/voice/online/OnlineAsrRegistry`）。
         */
        val voiceOnlineProviderId =
            ManagedPreference.PString(sharedPreferences, "voice_online_provider_id", "")
                .apply { register() }

        /** 火山引擎流式语音识别凭据（任选一种认证：apiKey 或 appKey+accessKey）。 */
        val voiceVolcApiKey =
            ManagedPreference.PString(sharedPreferences, "voice_volc_api_key", "")
                .apply { register() }
        val voiceVolcAppKey =
            ManagedPreference.PString(sharedPreferences, "voice_volc_app_key", "")
                .apply { register() }
        val voiceVolcAccessKey =
            ManagedPreference.PString(sharedPreferences, "voice_volc_access_key", "")
                .apply { register() }

        /** 火山引擎资源 id（默认 `volc.seedasr.sauc.duration`）。 */
        val voiceVolcResourceId =
            ManagedPreference.PString(sharedPreferences, "voice_volc_resource_id", "")
                .apply { register() }

        /** 小米 MiMo ASR 凭据（OpenAI 兼容接口，模型 `mimo-v2.5-asr`）。 */
        val voiceMiMoApiKey =
            ManagedPreference.PString(sharedPreferences, "voice_mimo_api_key", "")
                .apply { register() }

        /** MiMo ASR 语种提示（auto / zh / en，空 = auto）。 */
        val voiceMiMoLanguage =
            ManagedPreference.PString(sharedPreferences, "voice_mimo_language", "auto")
                .apply { register() }


        /** 当前选中的本地模型 id（内置清单或手动导入模型的 id）。 */
        val voiceAsrModelId = ManagedPreference.PString(
            sharedPreferences, "voice_asr_model_id", VoiceModelCatalog.DEFAULT_ID
        ).apply { register() }

        /** 调试：把语音识别期间的录音落盘。 */
        val voiceDebugRecord = ManagedPreference.PBool(
            sharedPreferences, "voice_debug_record", false
        ).apply { register() }

        init {
            groups = listOf(
                SubGroup(
                    R.string.group_voice,
                    listOf(
                        voiceInputEnabled.key,
                        voiceUseLocal.key,
                        voiceSimpleChinese.key,
                        voiceMuteDuringRecording.key,
                        voiceAsrModelId.key,
                        voiceDebugRecord.key
                    )
                )
            )
        }
    }

    /**
     * custom: 手写输入（独立输入方案）。
     *
     * 识别后端 = 系统内置引擎（小米随手写）/ 谷歌数字墨水（ML Kit），两者都是端上整段识别，
     * 不依赖外部模型文件；谷歌的中文模型随包内置（见 `MlKitBundledModel`）。
     */
    inner class Handwriting : ManagedPreferenceCategory(R.string.handwriting_input, sharedPreferences) {
        /** 手写输入总开关（工具栏手写按钮的显示条件之一）。 */
        val handwritingInputEnabled = switch(
            R.string.handwriting_input_enabled,
            "handwriting_input_enabled",
            false,
            summary = R.string.handwriting_input_enabled_summary
        )

        /** 边写边上屏（替换式）；关闭后只在点选候选时上屏。 */
        val handwritingAutoCommit = switch(
            R.string.handwriting_auto_commit,
            "handwriting_auto_commit",
            true,
            summary = R.string.handwriting_auto_commit_summary
        ) { handwritingInputEnabled.getValue() }

        /** 触控笔书写时显示浮动工具箱（撤销/重做/空格/回车/退格/键盘/关闭）。 */
        val stylusToolboxEnabled = switch(
            R.string.handwriting_stylus_toolbox,
            "stylus_toolbox_enabled",
            true,
            summary = R.string.handwriting_stylus_toolbox_summary
        ) { handwritingInputEnabled.getValue() }

        /**
         * 手写识别引擎（下拉），**声明顺序即默认优先级**：系统内置（小米随手写）→ 谷歌数字墨水。
         *
         * 选中项优先，不可用时按该顺序继续回落；手写键盘与触控笔手写共用该选择。
         */
        val handwritingEngine = enumList(
            R.string.handwriting_engine,
            "handwriting_engine",
            HandwritingEngineKind.System,
        ) { handwritingInputEnabled.getValue() }

        /**
         * 谷歌数字墨水识别语言（在模型市场 `digitalink` 分类里选中）。
         *
         * BCP-47 tag，同时就是市场里的模型 id（清单见 `DigitalInkModelCatalog`）；
         * **留空 = 跟随应用/系统语言**（中文取 `zh-Hani`）。
         */
        val handwritingDigitalInkLanguage = ManagedPreference.PString(
            sharedPreferences, "handwriting_digital_ink_language", ""
        ).apply { register() }

        init {
            migrateSystemEngineSwitch()
            cleanupLegacyKeys()
            groups = listOf(
                SubGroup(
                    R.string.group_handwriting,
                    listOf(
                        handwritingInputEnabled.key,
                        handwritingAutoCommit.key,
                        stylusToolboxEnabled.key,
                        handwritingEngine.key,
                    )
                )
            )
        }

        /**
         * 旧「优先使用系统手写引擎」开关（PBool）→ 引擎下拉的一次性迁移。
         *
         * 旧值为 false（不用系统引擎）等价于「只用谷歌数字墨水」；为 true（默认）等价于
         * 保持默认的「系统内置优先」。仅在新键还不存在时迁移一次。
         */
        private fun migrateSystemEngineSwitch() {
            val legacyKey = "handwriting_system_engine_enabled"
            if (sharedPreferences.contains(handwritingEngine.key)) return
            if (!sharedPreferences.contains(legacyKey)) return
            if (!sharedPreferences.getBoolean(legacyKey, true)) {
                handwritingEngine.setValue(HandwritingEngineKind.GoogleDigitalInk)
            }
        }

        /**
         * 一次性清理**已移除功能**留下的配置键（避免孤儿配置长期留在用户数据里）。
         *
         * - `handwriting_system_engine_enabled`：已被引擎下拉取代（先迁移再清）；
         * - `handwriting_model_id` / `handwriting_index_url`：ONNX 手写模型市场的模型 id
         *   与索引地址，模型已改走端上引擎；
         * - `handwriting_single_char_mode`：叠写切分下线后不再有该开关。
         */
        private fun cleanupLegacyKeys() {
            val marker = "handwriting_legacy_cleanup_v1"
            if (sharedPreferences.getBoolean(marker, false)) return
            sharedPreferences.edit {
                remove("handwriting_system_engine_enabled")
                remove("handwriting_model_id")
                remove("handwriting_index_url")
                remove("handwriting_single_char_mode")
                putBoolean(marker, true)
            }
        }
    }

    private val providers = mutableListOf<ManagedPreferenceProvider>()

    fun <T : ManagedPreferenceProvider> registerProvider(
        providerF: (SharedPreferences) -> T
    ): T {
        val provider = providerF(sharedPreferences)
        providers.add(provider)
        return provider
    }

    private fun <T : ManagedPreferenceProvider> T.register() = this.apply {
        registerProvider { this }
    }

    val internal = Internal().register()
    val keyboard = Keyboard().register()
    val textEditing = TextEditing().register()
    val candidates = Candidates().register()
    val clipboard = Clipboard().register()
    val broadcast = Broadcast().register()
    val symbols = Symbols().register()
    val advanced = Advanced().register()
    // custom: 语音输入
    val voice = Voice().register()
    // custom: 手写输入（独立输入方案，端上引擎：系统内置 / 谷歌数字墨水）
    val handwriting = Handwriting().register()

    @Keep
    private val onSharedPreferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null) return@OnSharedPreferenceChangeListener
            providers.forEach {
                it.fireChange(key)
            }
        }

    @RequiresApi(Build.VERSION_CODES.N)
    fun syncToDeviceEncryptedStorage() {
        val ctx = appContext.createDeviceProtectedStorageContext()
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        sp.edit {
            listOf(
                internal.verboseLog,
                internal.editorInfoInspector,
                advanced.ignoreSystemCursor,
                advanced.disableAnimation,
                advanced.vivoKeypressWorkaround,
                internal.floatingKeyboardWidthPortraitRatio,
                internal.floatingKeyboardWidthLandscapeRatio,
                internal.floatingKeyboardHeightPortraitRatio,
                internal.floatingKeyboardHeightLandscapeRatio,
                internal.floatingKeyboardXPortraitRatio,
                internal.floatingKeyboardYPortraitRatio,
                internal.floatingKeyboardXLandscapeRatio,
                internal.floatingKeyboardYLandscapeRatio,
                internal.oneHandOnRightPortrait,
                internal.oneHandOnRightLandscape,
                internal.floatingModeEnabled,
                internal.oneHandModeEnabled,
                internal.oneHandKeyboardWidthPortraitRatio,
                internal.oneHandKeyboardWidthLandscapeRatio
            ).forEach {
                it.putValueTo(this@edit)
            }
            listOf(
                keyboard,
                textEditing,
                candidates,
                clipboard,
                symbols
            ).forEach { category ->
                category.managedPreferences.forEach {
                    it.value.putValueTo(this@edit)
                }
            }
        }
    }

    companion object {
        private var instance: AppPrefs? = null

        /**
         * MUST call before use
         */
        fun init(sharedPreferences: SharedPreferences) {
            if (instance != null)
                return
            instance = AppPrefs(sharedPreferences)
            sharedPreferences.registerOnSharedPreferenceChangeListener(getInstance().onSharedPreferenceChangeListener)
        }

        fun getInstance() = instance!!
    }
}
