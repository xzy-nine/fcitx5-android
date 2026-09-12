/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.preference.SliderPreference
import kotlin.math.roundToInt

/**
 * 简洁滑块偏好：标题 + 当前值 + 滑块，无展开/收起，无输入框。
 * 类似系统设置的音量滑块样式。基于 miuix [SliderPreference]（valueText 显示当前值）。
 */

@Composable
fun SimpleSliderPreference(
    title: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    min: Int,
    max: Int,
    step: Int = 1,
    suffix: String = "",
    enabled: Boolean = true,
) {
    val range = min.toFloat()..max.toFloat()
    val realStep = if (step > 0) step else 1

    fun snap(v: Float): Int {
        val clamped = v.coerceIn(min.toFloat(), max.toFloat())
        val steps = ((clamped - min.toFloat()) / realStep).roundToInt()
        return (min + steps * realStep).coerceIn(min, max)
    }

    SliderPreference(
        modifier = modifier,
        title = title,
        valueText = "$value$suffix",
        value = value.toFloat(),
        onValueChange = { f ->
            val snapped = snap(f)
            if (snapped != value) onValueChange(snapped)
        },
        enabled = enabled,
        valueRange = range.start..range.endInclusive,
        steps = ((max - min) / realStep - 1).coerceAtLeast(0),
    )
}

/**
 * 浮点版本的简洁滑块偏好。
 */
@Composable
fun SimpleSliderPreference(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    min: Float,
    max: Float,
    step: Float = 1f,
    decimals: Int = 1,
    suffix: String = "",
    enabled: Boolean = true,
) {
    val realStep = if (step > 0f) step else 1f

    fun snap(v: Float): Float {
        val clamped = v.coerceIn(min, max)
        val steps = ((clamped - min) / realStep).roundToInt()
        return roundToDecimals(
            (min + steps * realStep).coerceIn(min, max),
            decimals
        )
    }

    SliderPreference(
        modifier = modifier,
        title = title,
        valueText = "${formatNumber(value, decimals)}$suffix",
        value = value,
        onValueChange = { f ->
            val snapped = snap(f)
            if (snapped != value) onValueChange(snapped)
        },
        enabled = enabled,
        valueRange = min..max,
        steps = (((max - min) / realStep).toInt() - 1).coerceAtLeast(0),
    )
}
