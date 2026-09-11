/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.clipboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.fcitx.fcitx5.android.input.bar.inputFeedback
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 剪切板编辑页回调：上屏/复制/退出/插入空格变更 */
data class ClipboardEditCallbacks(
    val onCommit: (String) -> Unit,
    val onCopy: (String) -> Unit,
    val onExit: () -> Unit,
    val onInsertSpaceChange: (Boolean) -> Unit,
)

/**
 * 剪切板编辑页 Compose 渲染。
 *
 * 呈现「分词重组（芯片流）」与「纯文本」两种模式：
 * - 芯片模式：点击切换词块选中，长按改为单选该块（简化原拖选语义），下方预览重组结果
 * - 文本模式：多行文本编辑区，由 [Text] 展示
 *
 * 底部按钮区提供 全选/反选/模式切换，以及 复制/取消/确定。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ClipboardEditContent(
    segments: List<String>,
    insertSpace: Boolean,
    callbacks: ClipboardEditCallbacks,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var isTextMode by remember { mutableStateOf(false) }
    var textInput by remember { mutableStateOf("") }

    val currentText = if (isTextMode) textInput
    else remember(segments, selected, insertSpace) {
        buildString {
            segments.forEachIndexed { index, seg ->
                if (index in selected) {
                    if (insertSpace && isNotEmpty()) append(' ')
                    append(seg)
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(12.dp)) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            if (!isTextMode) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    segments.forEachIndexed { index, seg ->
                        val isSel = index in selected
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (isSel) MiuixTheme.colorScheme.primary
                                    else MiuixTheme.colorScheme.surfaceVariant
                                )
                                .border(
                                    1.dp,
                                    if (isSel) Color.Transparent else MiuixTheme.colorScheme.outline,
                                    RoundedCornerShape(6.dp),
                                )
                                .inputFeedback()
                                .combinedClickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { selected = toggleSegment(selected, index) },
                                    onLongClick = { selected = setOf(index) },
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = seg,
                                color = if (isSel) MiuixTheme.colorScheme.onPrimary
                                else MiuixTheme.colorScheme.onSurface,
                                fontSize = 16.sp,
                                maxLines = 1,
                            )
                        }
                    }
                }
                if (currentText.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = currentText,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                androidx.compose.foundation.text.BasicTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 16.sp,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "插入空格",
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 14.sp,
            )
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = { callbacks.onInsertSpaceChange(!insertSpace) },
                modifier = Modifier.inputFeedback(),
                colors = if (insertSpace) ButtonDefaults.buttonColorsPrimary()
                else ButtonDefaults.buttonColors()
            ) {
                Text(text = if (insertSpace) "开" else "关", fontSize = 13.sp)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                text = "全选",
                onClick = { selected = segments.indices.toSet() },
                modifier = Modifier.weight(1f).inputFeedback(),
            )
            TextButton(
                text = "反选",
                onClick = {
                    val next = segments.indices.toMutableSet()
                    next.removeAll(selected)
                    selected = next
                },
                modifier = Modifier.weight(1f).inputFeedback(),
            )
            TextButton(
                text = if (isTextMode) "分词模式" else "文本模式",
                onClick = {
                    if (!isTextMode) textInput = currentText
                    isTextMode = !isTextMode
                },
                modifier = Modifier.weight(1f).inputFeedback(),
            )
            Spacer(modifier = Modifier.weight(0.4f))
            TextButton(
                text = "复制",
                onClick = { callbacks.onCopy(currentText) },
                modifier = Modifier.weight(1f).inputFeedback(),
            )
            TextButton(
                text = "取消",
                onClick = callbacks.onExit,
                modifier = Modifier.weight(1f).inputFeedback(),
            )
            Button(
                onClick = { callbacks.onCommit(currentText) },
                modifier = Modifier.weight(1f).inputFeedback(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(text = "确定", fontSize = 14.sp)
            }
        }
    }
}

private fun toggleSegment(selected: Set<Int>, index: Int): Set<Int> {
    val next = selected.toMutableSet()
    if (index in next) next.remove(index) else next.add(index)
    return next
}