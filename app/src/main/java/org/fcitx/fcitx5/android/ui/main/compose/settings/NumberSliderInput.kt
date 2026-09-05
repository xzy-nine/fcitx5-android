/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.settings

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.TextField
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 公共「滑块 + 输入框」数值调整控件，所有数值型设置共用它。
 *
 * - 值统一按 [Float] 处理，同一控件同时服务整数/小数设置；[decimals] 决定显示与取整精度，
 *   [step] 决定滑块步进。
 * - 输入框在失去焦点时才提交（绝不逐字符提交），因此输入 "1"→"15" 途中不会提前被 clamp。
 * - [range] 为 null 时只显示输入框（无滑块的无限范围场景）。
 */
@Composable
fun NumberSliderInput(
    value: Float,
    onValueChange: (Float) -> Unit,
    label: String,
    range: ClosedFloatingPointRange<Float>?,
    modifier: Modifier = Modifier,
    step: Float = 1f,
    decimals: Int = 0,
    enabled: Boolean = true,
) {
    val realStep = if (step > 0f) step else 1f

    fun snap(v: Float): Float {
        if (range == null) return v
        val clamped = v.coerceIn(range.start, range.endInclusive)
        val steps = ((clamped - range.start) / realStep).roundToInt()
        return roundToDecimals(
            (range.start + steps * realStep).coerceIn(range.start, range.endInclusive),
            decimals
        )
    }

    var text by remember(value) { mutableStateOf(formatNumber(value, decimals)) }

    fun commit(raw: String): Boolean {
        val parsed = raw.trim().replace(',', '.').toFloatOrNull() ?: return false
        val snapped = snap(parsed)
        if (snapped == value) {
            text = formatNumber(snapped, decimals)
        } else {
            onValueChange(snapped)
        }
        return true
    }

    Column(modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextField(
                value = text,
                onValueChange = { text = it },
                label = label,
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { focusState ->
                        if (!focusState.isFocused && !commit(text)) {
                            text = formatNumber(value, decimals)
                        }
                    },
            )
        }
        if (range != null && range.endInclusive > range.start) {
            Slider(
                value = value.coerceIn(range.start, range.endInclusive),
                onValueChange = { f ->
                    val snapped = snap(f)
                    if (snapped != value) onValueChange(snapped)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                enabled = enabled,
                valueRange = range.start..range.endInclusive,
                steps = (((range.endInclusive - range.start) / realStep).roundToInt() - 1)
                    .coerceAtLeast(0),
            )
        }
    }
}

internal fun formatNumber(value: Float, decimals: Int): String =
    if (decimals <= 0) value.roundToInt().toString()
    else String.format(Locale.US, "%.${decimals}f", value)

private fun roundToDecimals(value: Float, decimals: Int): Float {
    if (decimals <= 0) return value.roundToInt().toFloat()
    var factor = 1f
    repeat(decimals) { factor *= 10f }
    return (value * factor).roundToInt() / factor
}
