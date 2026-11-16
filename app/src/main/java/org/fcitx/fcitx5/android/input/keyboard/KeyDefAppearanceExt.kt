/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * 键面外观的形态转换。
 *
 * [KeyDef.Appearance.ImageAltText] 与 [KeyDef.Appearance.Image] 的绘制内容相同（都是一个图标），
 * 差异只在副标签；View 侧按图标键渲染，副标签由布局另行绘制。
 */
fun KeyDef.Appearance.ImageAltText.toImage(): KeyDef.Appearance.Image =
    KeyDef.Appearance.Image(
        src = src,
        percentWidth = percentWidth,
        variant = variant,
        border = border,
        margin = margin,
        viewId = viewId,
        soundEffect = soundEffect,
        textColor = textColor,
        textColorMonet = textColorMonet,
        altTextColor = altTextColor,
        altTextColorMonet = altTextColorMonet,
        backgroundColor = backgroundColor,
        backgroundColorMonet = backgroundColorMonet,
        shadowColor = shadowColor,
        shadowColorMonet = shadowColorMonet,
    )
