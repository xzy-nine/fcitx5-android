/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.clipboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.bar.inputFeedback
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
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
 * - 芯片模式：点击切换词块选中，长按后滑动可连续拖选（见 [ClipboardSegmentFlow]），下方预览重组结果
 * - 文本模式：多行文本编辑区，由 [Text] 展示
 *
 * 底部按钮区提供 全选/反选/模式切换，以及 复制/取消/确定。
 */
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
    // 芯片区拖选到边缘时需要驱动滚动，故滚动状态与可视区范围由本层提供
    val scrollState = rememberScrollState()
    var viewportBounds by remember { mutableStateOf<Rect?>(null) }

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
                .verticalScroll(scrollState)
                .onGloballyPositioned { viewportBounds = it.boundsInWindow() }
        ) {
            if (!isTextMode) {
                ClipboardSegmentFlow(
                    segments = segments,
                    selected = selected,
                    onSelectionChange = { selected = it },
                    scrollState = scrollState,
                    viewportBounds = viewportBounds,
                )
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
                TextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    cornerRadius = 12.dp,
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
                text = stringResource(R.string.insert_space),
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
                Text(text = stringResource(if (insertSpace) R.string.on else R.string.off), fontSize = 13.sp)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 全选/反选只对分词模式有意义：文本模式下分词芯片不展示，隐藏以免误操作
            if (!isTextMode) {
                TextButton(
                    text = stringResource(R.string.select_all),
                    onClick = { selected = segments.indices.toSet() },
                    modifier = Modifier.weight(1f).inputFeedback(),
                )
                TextButton(
                    text = stringResource(R.string.invert_selection),
                    onClick = {
                        val next = segments.indices.toMutableSet()
                        next.removeAll(selected)
                        selected = next
                    },
                    modifier = Modifier.weight(1f).inputFeedback(),
                )
            }
            TextButton(
                text = stringResource(if (isTextMode) R.string.segment_mode else R.string.text_mode),
                onClick = {
                    if (!isTextMode) textInput = currentText
                    isTextMode = !isTextMode
                },
                modifier = Modifier.weight(1f).inputFeedback(),
            )
            Spacer(modifier = Modifier.weight(0.4f))
            TextButton(
                text = stringResource(R.string.copy),
                onClick = { callbacks.onCopy(currentText) },
                modifier = Modifier.weight(1f).inputFeedback(),
            )
            TextButton(
                text = stringResource(R.string.cancel),
                onClick = callbacks.onExit,
                modifier = Modifier.weight(1f).inputFeedback(),
            )
            Button(
                onClick = { callbacks.onCommit(currentText) },
                modifier = Modifier.weight(1f).inputFeedback(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(text = stringResource(R.string.ok), fontSize = 14.sp)
            }
        }
    }
}
