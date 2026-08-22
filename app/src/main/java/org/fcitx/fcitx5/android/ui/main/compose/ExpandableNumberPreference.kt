/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Collapsible number preference: collapsed shows title + current value; tapping expands an
 * inline text field (and a slider when both bounds are provided) — no dialog involved.
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
    var expanded by remember { mutableStateOf(false) }
    var text by remember(value) { mutableStateOf(value.toString()) }

    fun commit(raw: String): Boolean {
        val parsed = raw.toIntOrNull() ?: return false
        val clamped = parsed.coerceIn(min ?: Int.MIN_VALUE, max ?: Int.MAX_VALUE)
        onValueChange(clamped)
        return true
    }

    BasicComponent(
        modifier = modifier,
        title = title,
        endActions = {
            Text(
                text = "$value$suffix",
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = if (enabled) MiuixTheme.colorScheme.onSurfaceVariantSummary
                else MiuixTheme.colorScheme.disabledOnSecondaryVariant,
            )
        },
        bottomAction = {
            AnimatedVisibility(visible = expanded && enabled) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        TextField(
                            value = text,
                            onValueChange = { t ->
                                text = t
                                commit(t)
                            },
                            label = title,
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (min != null && max != null && max > min) {
                        Slider(
                            value = value.coerceIn(min, max).toFloat(),
                            onValueChange = { f ->
                                val stepped =
                                    (min + ((f - min) / step).toInt() * step)
                                        .coerceIn(min, max)
                                onValueChange(stepped)
                                text = stepped.toString()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            enabled = enabled,
                            valueRange = min.toFloat()..max.toFloat(),
                            steps = ((max - min) / step - 1).coerceAtLeast(0),
                        )
                    }
                }
            }
        },
        onClick = { expanded = !expanded },
        holdDownState = expanded,
        enabled = enabled,
    )
}
