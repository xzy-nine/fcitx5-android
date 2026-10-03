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
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesOrientation
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode
import org.fcitx.fcitx5.android.input.keyboard.KeyboardHeightPercentBase
import org.fcitx.fcitx5.android.input.keyboard.LangSwitchBehavior
import org.fcitx.fcitx5.android.input.keyboard.SpaceLongPressBehavior
import org.fcitx.fcitx5.android.input.keyboard.SwipeSymbolDirection
import org.fcitx.fcitx5.android.input.picker.PickerWindow
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
    }

    inner class Advanced : ManagedPreferenceCategory(R.string.advanced, sharedPreferences) {
        val ignoreSystemCursor = switch(R.string.ignore_sys_cursor, "ignore_system_cursor", false)
        val hideKeyConfig = switch(R.string.hide_key_config, "hide_key_config", true)
        val disableAnimation = switch(R.string.disable_animation, "disable_animation", false)
        val vivoKeypressWorkaround = switch(
            R.string.vivo_keypress_workaround,
            "vivo_keypress_workaround",
            // there's some feedback that this workaround is no longer necessary on Origin OS 4, which based on Android 14
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE && DeviceUtil.isVivoOriginOS
        )
        val ignoreSystemWindowInsets = switch(
            R.string.ignore_system_window_insets, "ignore_system_window_insets", false
        )
        val keyboardHeightPercentBase = enumList(
            R.string.keyboard_height_percent_base,
            "keyboard_height_percent_base",
            KeyboardHeightPercentBase.DisplayMetrics
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

        val preferredVoiceInput = voiceInputPreference(
            R.string.preferred_voice_input, "preferred_voice_input", ""
        ) {
            spaceKeyLongPressBehavior.getValue() == SpaceLongPressBehavior.VoiceInput
        }

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
        val spaceSwipeMoveCursor =
            switch(R.string.space_swipe_move_cursor, "space_swipe_move_cursor", true)
        val showLangSwitchKey =
            switch(R.string.show_lang_switch_key, "show_lang_switch_key", true)
        val langSwitchKeyBehavior = enumList(
            R.string.lang_switch_key_behavior,
            "lang_switch_key_behavior",
            LangSwitchBehavior.Enumerate
        ) { showLangSwitchKey.getValue() }

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
            val (primary, secondary) = twinInt(
                R.string.keyboard_side_padding,
                R.string.portrait,
                "keyboard_side_padding",
                5,
                R.string.landscape,
                "keyboard_side_padding_landscape",
                17,
                0,
                300,
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
                    langSwitchKeyBehavior.key,
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

    }

    inner class Candidates :
        ManagedPreferenceCategory(R.string.candidates_window, sharedPreferences) {
        val mode = enumList(
            R.string.show_candidates_window,
            "show_candidates_window",
            FloatingCandidatesMode.InputDevice
        )

        val orientation = enumList(
            R.string.candidates_orientation,
            "candidates_window_orientation",
            FloatingCandidatesOrientation.Automatic
        )

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
        val clipboardListening = switch(R.string.clipboard_listening, "clipboard_enable", true)
        val clipboardHistoryLimit = int(
            R.string.clipboard_limit,
            "clipboard_limit",
            10,
        ) { clipboardListening.getValue() }
        val clipboardSuggestion = switch(
            R.string.clipboard_suggestion, "clipboard_suggestion", true
        ) { clipboardListening.getValue() }
        val clipboardItemTimeout = int(
            R.string.clipboard_suggestion_timeout,
            "clipboard_item_timeout",
            30,
            -1,
            Int.MAX_VALUE,
            "s"
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
    }

    /**
     * custom: 语音输入。
     *
     * 离线引擎为官方 sherpa-onnx（Apache-2.0，见仓库根 NOTICE.md），在线平台为内置 provider
     * （火山引擎 / 小米 MiMo，见 `data/voice/online/`）；页面结构参考 whisperIME。
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
         * 平台由 app 侧内置实现（火山引擎 / 小米 MiMo），不再是可安装的 Lua 插件，
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


        /** 当前选中的本地模型 id（模型市场索引里的 id）。 */
        val voiceAsrModelId = ManagedPreference.PString(
            sharedPreferences, "voice_asr_model_id", VoiceModelCatalog.DEFAULT_ID
        ).apply { register() }

        /** 模型市场索引地址覆盖（空 = 用 xime.yaml 里的 xime_index.base_urls）。 */
        val voiceIndexUrl = ManagedPreference.PString(sharedPreferences, "voice_index_url", "")
            .apply { register() }

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
                        voiceIndexUrl.key,
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
    val candidates = Candidates().register()
    val clipboard = Clipboard().register()
    val broadcast = Broadcast().register()
    val symbols = Symbols().register()
    val advanced = Advanced().register()
    // custom: 语音输入（Xime 核心移植）
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
                advanced.vivoKeypressWorkaround
            ).forEach {
                it.putValueTo(this@edit)
            }
            listOf(
                keyboard,
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
