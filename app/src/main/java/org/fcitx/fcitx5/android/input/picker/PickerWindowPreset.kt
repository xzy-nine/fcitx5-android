/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.keyboard.ImageLayoutSwitchKey
import org.fcitx.fcitx5.android.input.keyboard.ImagePickerSwitchKey
import org.fcitx.fcitx5.android.input.keyboard.KeyboardLayoutNames
import org.fcitx.fcitx5.android.input.keyboard.TextPickerSwitchKey

fun symbolPicker(): PickerWindow = PickerWindow(
    key = PickerWindow.Key.Symbol,
    data = PickerData.Symbol,
    density = PickerDensity.High,
    switchKey = ImageLayoutSwitchKey(R.drawable.ic_number_pad, KeyboardLayoutNames.Number)
)

fun emojiPicker(): PickerWindow = PickerWindow(
    key = PickerWindow.Key.Emoji,
    data = PickerData.Emoji,
    density = PickerDensity.Medium,
    switchKey = TextPickerSwitchKey(":-)", PickerWindow.Key.Emoticon),
    popupPreview = false,
    followKeyBorder = false,
    policy = EmojiPickerPolicy()
)

fun emoticonPicker(): PickerWindow = PickerWindow(
    key = PickerWindow.Key.Emoticon,
    data = PickerData.Emoticon,
    density = PickerDensity.Low,
    switchKey = ImagePickerSwitchKey(R.drawable.ic_baseline_tag_faces_24, PickerWindow.Key.Emoji),
    popupPreview = false,
    followKeyBorder = false
)
