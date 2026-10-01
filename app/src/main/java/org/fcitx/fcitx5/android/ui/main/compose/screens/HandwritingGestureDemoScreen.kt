/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 触控笔手势「动作演示 + 试用」页（Gboard 式设置页内演示）。
 *
 * 米系把试用放在**系统设置**里，输入法设置页只列开关；这里参考 Gboard 的做法，
 * 把演示/试用做进输入法自己的设置页：每条手势一行说明，底部画布可直接画一笔，
 * 实时显示判定结果（复用 [HandwritingGestures] 同一套判定，所见即所得）。
 *
 * 纯 Compose + 纯逻辑（不依赖模型、不触碰 IME），因此没有模型也能试用与学习动作。
 */
package org.fcitx.fcitx5.android.ui.main.compose.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.handwriting.HandwritingGestures
import org.fcitx.fcitx5.android.data.handwriting.HandwritingStrokeKind
import org.fcitx.fcitx5.android.data.handwriting.StrokePoint
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 演示页列出的手势（含画法说明）。 */
private enum class GestureDemo(val titleRes: Int, val summaryRes: Int) {
    Delete(R.string.handwriting_gesture_delete, R.string.handwriting_gesture_delete_summary),
    Select(R.string.handwriting_gesture_select, R.string.handwriting_gesture_select_summary),
    Insert(R.string.handwriting_gesture_insert, R.string.handwriting_gesture_insert_summary),
    Newline(R.string.handwriting_gesture_newline, R.string.handwriting_gesture_newline_summary),
}

@Composable
fun HandwritingGestureDemoScreen(onBack: () -> Unit) {
    PageScaffold(
        title = stringResource(R.string.handwriting_stylus_gestures),
        onBack = onBack,
        contentBottomPadding = 24.dp,
    ) {
        item {
            Text(
                text = stringResource(R.string.handwriting_stylus_gestures_summary),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        item { SmallTitle(text = stringResource(R.string.handwriting_gesture_try)) }
        item { GestureTryCanvas() }

        item { SmallTitle(text = stringResource(R.string.handwriting_stylus_gestures)) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.surfaceContainerHighest,
                ),
            ) {
                GestureDemo.entries.forEach { demo ->
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(
                            text = stringResource(demo.titleRes),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = stringResource(demo.summaryRes),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 试用画布：手指或触控笔直接画一笔，实时显示判定结果。
 *
 * 判定与 IME 内完全同一套 [HandwritingGestures]（阈值一致），因此这里学会的动作在
 * 真实书写时表现相同；纯几何判定，不依赖任何识别引擎或模型。
 */
@Composable
private fun GestureTryCanvas() {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }.toInt()

    val points = remember { mutableStateListOf<StrokePoint>() }
    var result by remember { mutableStateOf<HandwritingStrokeKind?>(null) }
    val inkColor = MiuixTheme.colorScheme.onSurface

    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .background(
                    color = MiuixTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(12.dp),
                ),
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .pointerInput(screenWidthPx) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            points.clear()
                            result = null
                            points.add(StrokePoint(down.position.x, down.position.y, 0L))
                            var time = 0L
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (change.pressed) {
                                    change.consume()
                                    time += 16L
                                    points.add(
                                        StrokePoint(change.position.x, change.position.y, time)
                                    )
                                } else {
                                    change.consume()
                                    result = HandwritingGestures.detect(points.toList(), screenWidthPx)
                                    break
                                }
                            }
                        }
                    },
            ) {
                if (points.size >= 2) {
                    for (i in 1 until points.size) {
                        drawLine(
                            color = inkColor,
                            start = Offset(points[i - 1].x, points[i - 1].y),
                            end = Offset(points[i].x, points[i].y),
                            strokeWidth = 4.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }
            if (points.isEmpty()) {
                Text(
                    text = stringResource(R.string.handwriting_gesture_demo_hint),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val label = when (result) {
                null -> ""
                HandwritingStrokeKind.Character -> stringResource(R.string.handwriting_gesture_demo_none)
                HandwritingStrokeKind.Delete -> stringResource(R.string.handwriting_gesture_delete)
                HandwritingStrokeKind.Select -> stringResource(R.string.handwriting_gesture_select)
                HandwritingStrokeKind.Insert -> stringResource(R.string.handwriting_gesture_insert)
                HandwritingStrokeKind.Newline -> stringResource(R.string.handwriting_gesture_newline)
            }
            Text(
                text = if (label.isEmpty()) "" else
                    stringResource(R.string.handwriting_gesture_demo_result) + "：" + label,
                color = MiuixTheme.colorScheme.primary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            TextButton(
                text = stringResource(R.string.handwriting_gesture_clear),
                onClick = {
                    points.clear()
                    result = null
                },
            )
        }
    }
}
