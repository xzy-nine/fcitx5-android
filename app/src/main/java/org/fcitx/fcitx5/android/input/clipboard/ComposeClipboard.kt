/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.clipboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.input.bar.inputFeedback
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 剪贴板条目长按操作/确认等交互回调 */
data class ClipboardCallbacks(
    val onPaste: (ClipboardEntry) -> Unit,
    val onPasteText: (String) -> Unit,
    val onPin: (Int) -> Unit,
    val onUnpin: (Int) -> Unit,
    val onEdit: (Int) -> Unit,
    val onShare: (ClipboardEntry) -> Unit,
    val onDelete: (Int) -> Unit,
    val onEnableListening: () -> Unit,
)

// 实体提取结果缓存（按 entryId），避免滚动重绑时反复分词
private val chipsCache = mutableMapOf<Int, List<ClipboardTextAnalyzer.Entity>>()

/** 剪贴板主页 Compose 渲染：按 [state] 显示启用提示 / 空提示 / 条目列表。 */
@Composable
fun ClipboardListContent(
    state: ClipboardStateMachine.State,
    entries: List<ClipboardEntry>,
    maskSensitive: Boolean,
    callbacks: ClipboardCallbacks,
    showDeleteAllDialog: Boolean,
    deleteAllLabel: String,
    onConfirmDeleteAll: () -> Unit,
    onCancelDeleteAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (state) {
            ClipboardStateMachine.State.EnableListening -> EnableListeningUi(callbacks.onEnableListening)
            ClipboardStateMachine.State.AddMore -> AddMoreUi()
            ClipboardStateMachine.State.Normal -> ClipboardEntryList(entries, maskSensitive, callbacks)
        }
        if (showDeleteAllDialog) {
            CenteredOverlay(onDismiss = onCancelDeleteAll) {
                Text(
                    text = deleteAllLabel,
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                )
                MenuOptionRow(R.drawable.ic_baseline_delete_24, "删除") {
                    onConfirmDeleteAll()
                    onCancelDeleteAll()
                }
                MenuOptionRowTextOnly("取消", onCancelDeleteAll)
            }
        }
    }
}

@Composable
private fun ClipboardEntryList(
    entries: List<ClipboardEntry>,
    maskSensitive: Boolean,
    callbacks: ClipboardCallbacks,
) {
    // 当前长按条目 → 显示操作菜单
    var menuEntry by remember { mutableStateOf<ClipboardEntry?>(null) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = entries, key = { it.id }) { entry ->
            ClipboardEntryCard(
                entry = entry,
                maskSensitive = maskSensitive,
                onPaste = { callbacks.onPaste(entry) },
                onChipClick = { callbacks.onPasteText(it) },
                onLongPressAction = { menuEntry = entry },
            )
        }
    }
    menuEntry?.let { entry ->
        ClipboardActionMenu(entry, callbacks) { menuEntry = null }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClipboardEntryCard(
    entry: ClipboardEntry,
    maskSensitive: Boolean,
    onPaste: () -> Unit,
    onChipClick: (String) -> Unit,
    onLongPressAction: () -> Unit,
) {
    val chips = rememberChips(entry, maskSensitive)
    val display = remember(entry, maskSensitive) {
        ClipboardAdapter.excerptText(
            entry.text,
            mask = entry.sensitive && maskSensitive,
        )
    }
    val interactionSource = remember { MutableInteractionSource() }
    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .inputFeedback()
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onPaste,
                    onLongClick = onLongPressAction,
                ),
            shape = RoundedCornerShape(12.dp),
            color = MiuixTheme.colorScheme.surfaceVariant,
        ) {
            Column(modifier = Modifier.padding(14.dp, 10.dp, 14.dp, 10.dp)) {
                Text(
                    text = display,
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (chips.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        chips.forEach { entity ->
                            val show =
                                entity.value.take(12) + if (entity.value.length > 12) "…" else ""
                            Surface(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .inputFeedback()
                                    .clickable { onChipClick(entity.value) },
                                shape = RoundedCornerShape(10.dp),
                                color = MiuixTheme.colorScheme.secondaryContainer,
                            ) {
                                Text(
                                    text = show,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        // 钉住标记叠加在右下角
        if (entry.pinned) {
            Icon(
                painter = painterResource(R.drawable.ic_outline_push_pin_24),
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(12.dp)
                    .padding(end = 2.dp, bottom = 2.dp),
            )
        }
    }
}

@Composable
private fun rememberChips(
    entry: ClipboardEntry,
    maskSensitive: Boolean,
): List<ClipboardTextAnalyzer.Entity> {
    return produceState(initialValue = chipsCache[entry.id] ?: emptyList(), key1 = entry.id) {
        if (value.isEmpty() && !(entry.sensitive && maskSensitive)) {
            value = withContext(Dispatchers.IO) {
                runCatching { ClipboardTextAnalyzer.analyze(entry.text) }
                    .getOrDefault(emptyList())
            }.also { chipsCache[entry.id] = it }
        }
    }.value
}

@Composable
private fun EnableListeningUi(onEnable: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
    ) {
        Text(
            text = "剪贴板监听未开启",
            color = MiuixTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            modifier = Modifier.padding(12.dp, 8.dp),
        )
        Surface(
            modifier = Modifier
                .align(Alignment.End)
                .inputFeedback()
                .clickable(onClick = onEnable),
            shape = RoundedCornerShape(8.dp),
            color = MiuixTheme.colorScheme.primary,
        ) {
            Text(
                text = "开启监听",
                color = MiuixTheme.colorScheme.onPrimary,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun AddMoreUi() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_baseline_content_paste_24),
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(90.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "复制内容后会自动出现在这里",
            color = MiuixTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ClipboardActionMenu(
    entry: ClipboardEntry,
    callbacks: ClipboardCallbacks,
    onDismiss: () -> Unit,
) {
    CenteredOverlay(onDismiss = onDismiss) {
        val pinned = entry.pinned
        MenuOptionRow(R.drawable.ic_baseline_push_pin_24, if (pinned) "取消置顶" else "置顶") {
            if (pinned) callbacks.onUnpin(entry.id) else callbacks.onPin(entry.id)
        }
        MenuOptionRow(R.drawable.ic_baseline_edit_24, "编辑") { callbacks.onEdit(entry.id) }
        MenuOptionRow(R.drawable.ic_baseline_share_24, "分享") { callbacks.onShare(entry) }
        MenuOptionRow(R.drawable.ic_baseline_delete_24, "删除") { callbacks.onDelete(entry.id) }
    }
}

@Composable
private fun CenteredOverlay(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background.copy(alpha = 0.4f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(min = 200.dp)
                .clip(RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            color = MiuixTheme.colorScheme.surface,
            shadowElevation = 8.dp,
        ) {
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun MenuOptionRow(
    iconResId: Int,
    text: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .inputFeedback()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            painter = painterResource(iconResId),
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = text,
            color = MiuixTheme.colorScheme.onSurface,
            fontSize = 15.sp,
        )
    }
}

@Composable
private fun MenuOptionRowTextOnly(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .inputFeedback()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        Text(
            text = text,
            color = MiuixTheme.colorScheme.primary,
            fontSize = 15.sp,
        )
    }
}