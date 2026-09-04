/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/**
 * 可折叠数值偏好行：收起时显示标题 + 当前值，点击展开后嵌入 [NumberSliderInput]
 * （输入框 + 有界时的滑块），不使用对话框。
 */
@Composable
fun ExpandableNumberPreference(
    title: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    min: Int? = null,
    max: Int? = null,
    step: Int = 1,
    suffix: String = "",
    enabled: Boolean = true,
) {
    ExpandableNumberPreference(
        title = title,
        value = value.toFloat(),
        onValueChange = { onValueChange(it.roundToInt()) },
        modifier = modifier,
        min = min?.toFloat(),
        max = max?.toFloat(),
        step = step.toFloat(),
        decimals = 0,
        suffix = suffix,
        enabled = enabled,
    )
}

/** 浮点版本，整数以外的数值型偏好也走同一套「滑块 + 输入框」。 */
@Composable
fun ExpandableNumberPreference(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    min: Float? = null,
    max: Float? = null,
    step: Float = 1f,
    decimals: Int = 1,
    suffix: String = "",
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val range = if (min != null && max != null && max > min) min..max else null

    BasicComponent(
        modifier = modifier,
        title = title,
        endActions = {
            Text(
                text = "${formatNumber(value, decimals)}$suffix",
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = if (enabled) MiuixTheme.colorScheme.onSurfaceVariantSummary
                else MiuixTheme.colorScheme.disabledOnSecondaryVariant,
            )
        },
        bottomAction = {
            AnimatedVisibility(visible = expanded && enabled) {
                NumberSliderInput(
                    value = value,
                    onValueChange = onValueChange,
                    label = title,
                    range = range,
                    step = step,
                    decimals = decimals,
                    enabled = enabled,
                )
            }
        },
        onClick = { expanded = !expanded },
        holdDownState = expanded,
        enabled = enabled,
    )
}
