/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.clipboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.input.bar.inputFeedback
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
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

// 条目渲染数据缓存：同时缓存摘录正文与实体 chips，首次异步计算，之后滚动复用，
// 避免主线程反复分词/截断导致的卡顿。
// key 含 text，保证条目被编辑后旧缓存自然失效（不会显示过期内容）；
// 使用有上限的 LRU，并由互斥锁保护跨线程读写（分词在 IO 线程完成）。
private class CardData(val display: String, val chips: List<ClipboardTextAnalyzer.Entity>)
private val EMPTY_CARD_DATA = CardData("", emptyList())
private const val CARD_CACHE_MAX = 256
private val cardCacheLock = Any()
private val cardCache = object : LinkedHashMap<CardCacheKey, CardData>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CardCacheKey, CardData>) =
        size > CARD_CACHE_MAX
}
private data class CardCacheKey(val id: Int, val text: String, val mask: Boolean)

private fun readCardCache(entry: ClipboardEntry, maskSensitive: Boolean): CardData? =
    synchronized(cardCacheLock) { cardCache[CardCacheKey(entry.id, entry.text, maskSensitive)] }

private fun writeCardCache(entry: ClipboardEntry, maskSensitive: Boolean, data: CardData) {
    synchronized(cardCacheLock) {
        cardCache[CardCacheKey(entry.id, entry.text, maskSensitive)] = data
    }
}

@Composable
private fun rememberCardData(
    entry: ClipboardEntry,
    maskSensitive: Boolean,
): CardData {
    return produceState<CardData>(
        initialValue = readCardCache(entry, maskSensitive) ?: EMPTY_CARD_DATA,
        key1 = entry.id,
        key2 = maskSensitive,
        key3 = entry.text,
    ) {
        val cached = readCardCache(entry, maskSensitive)
        if (cached != null) {
            value = cached
        } else {
            val masked = entry.sensitive && maskSensitive
            // 分词/截断在 IO 线程执行，避免主线程卡顿
            value = withContext(Dispatchers.IO) {
                CardData(
                    display = ClipboardAdapter.excerptText(entry.text, mask = masked),
                    chips = if (masked) emptyList() else ClipboardTextAnalyzer.analyze(entry.text),
                )
            }
            writeCardCache(entry, maskSensitive, value)
        }
    }.value
}

/** 剪切板长按菜单状态：目标条目 + 长按点（Compose 根坐标） */
private data class ClipboardMenuState(val entry: ClipboardEntry, val anchorOffset: Offset)

/**
 * 分页占位项的 LazyList key。
 *
 * [LazyPagingItems] 允许某位置的条目暂时为 null，此时无法用 `entry.id` 作 key。
 * 用独立类型包裹下标，保证与真实条目的 `Int` id 永不相撞（`Int` 与 [IndexKey] 不 equals）。
 */
private data class IndexKey(val index: Int)

/** 剪贴板主页 Compose 渲染：按 [state] 显示启用提示 / 空提示 / 条目列表。 */
@Composable
fun ClipboardListContent(
    state: ClipboardStateMachine.State,
    entries: LazyPagingItems<ClipboardEntry>,
    maskSensitive: Boolean,
    callbacks: ClipboardCallbacks,
    showDeleteAllDialog: Boolean,
    deleteAllLabel: String,
    onConfirmDeleteAll: () -> Unit,
    onCancelDeleteAll: () -> Unit,
    pendingDeleteIds: List<Int>,
    onUndoDelete: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (state) {
            ClipboardStateMachine.State.EnableListening -> EnableListeningUi(callbacks.onEnableListening)
            ClipboardStateMachine.State.AddMore -> AddMoreUi()
            ClipboardStateMachine.State.Normal -> ClipboardEntryList(
                entries,
                maskSensitive,
                callbacks,
                onRetry
            )
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
                MenuOptionRow(R.drawable.ic_baseline_delete_24, stringResource(R.string.delete)) {
                    onConfirmDeleteAll()
                }
                MenuOptionRowTextOnly(stringResource(R.string.cancel), onCancelDeleteAll)
            }
        }
        // 撤销条：删除后限时提供恢复入口，替代旧实现的 Snackbar
        if (pendingDeleteIds.isNotEmpty()) {
            UndoBar(
                count = pendingDeleteIds.size,
                onUndo = onUndoDelete,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            )
        }
    }
}

/**
 * 删除撤销条。
 *
 * 旧实现用 `Snackbar` + `realDelete()` 回调；Compose 版以悬浮条承载同一语义：
 * 显示期间可撤销（恢复软删除条目），超时或窗口销毁由窗口侧执行物理清理。
 */
@Composable
private fun UndoBar(
    count: Int,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .inputFeedback(),
        shape = RoundedCornerShape(12.dp),
        color = MiuixTheme.colorScheme.surface,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.num_items_deleted, count),
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 14.sp,
            )
            TextButton(
                text = stringResource(R.string.undo),
                onClick = onUndo,
                modifier = Modifier.inputFeedback(),
            )
        }
    }
}

@Composable
private fun ClipboardEntryList(
    entries: LazyPagingItems<ClipboardEntry>,
    maskSensitive: Boolean,
    callbacks: ClipboardCallbacks,
    onRetry: () -> Unit,
) {
    // 当前长按条目 + 长按位置 → 显示锚定操作菜单
    var menuState by remember { mutableStateOf<ClipboardMenuState?>(null) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(
            count = entries.itemCount,
            key = { index -> entries.peek(index)?.id ?: IndexKey(index) }) { index ->
            // 分页下条目可能因刷新暂时为 null（占位/被移除），跳过以免 NPE。
            // 此时 key 退化为 IndexKey，与任何真实条目 id（Int）都不会相等。
            val entry = entries[index] ?: return@items
            ClipboardEntryCard(
                entry = entry,
                maskSensitive = maskSensitive,
                onPaste = { callbacks.onPaste(entry) },
                onChipClick = { callbacks.onPasteText(it) },
                onLongPressAction = { offset -> menuState = ClipboardMenuState(entry, offset) },
            )
        }
        when (val append = entries.loadState.append) {
            is LoadState.Loading -> item(key = "paging_loading") { PagingFooterLoading() }
            is LoadState.Error -> item(key = "paging_error") {
                PagingFooterError(onRetry = onRetry)
            }
            // 首屏（refresh）失败时 append 不会触发，单独提示并提供重试
            else -> Unit
        }
        if (entries.loadState.refresh is LoadState.Error) {
            item(key = "paging_refresh_error") { PagingFooterError(onRetry = onRetry) }
        }
    }
    menuState?.let { state ->
        ClipboardActionMenu(state.entry, callbacks, state.anchorOffset) { menuState = null }
    }
}

/** 分页加载中页脚（追加下一页时显示） */
@Composable
private fun PagingFooterLoading() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.loading),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 13.sp,
        )
    }
}

/** 分页加载失败页脚：给出重试入口 */
@Composable
private fun PagingFooterError(onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            text = stringResource(R.string.retry),
            onClick = onRetry,
            modifier = Modifier.inputFeedback(),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClipboardEntryCard(
    entry: ClipboardEntry,
    maskSensitive: Boolean,
    onPaste: () -> Unit,
    onChipClick: (String) -> Unit,
    onLongPressAction: (Offset) -> Unit,
) {
    val cardData = rememberCardData(entry, maskSensitive)
    val display = cardData.display
    val chips = cardData.chips
    val containerCoords = remember { mutableStateOf<LayoutCoordinates?>(null) }
    // 触摸锚点：用于长按菜单跟随点击位置浮现
    val tapHandler = Modifier
        .onGloballyPositioned { containerCoords.value = it }
        .pointerInput(entry.id) {
            detectTapGestures(
                onTap = { onPaste() },
                onLongPress = { offset ->
                    val rootOffset = containerCoords.value?.localToRoot(offset) ?: offset
                    onLongPressAction(rootOffset)
                },
            )
        }
    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .inputFeedback()
                .then(tapHandler),
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
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
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
private fun EnableListeningUi(onEnable: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
    ) {
        Text(
            text = stringResource(R.string.clipboard_listening_disabled),
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
                text = stringResource(R.string.enable_listening),
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
            text = stringResource(R.string.clipboard_empty_hint),
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
    anchorOffset: Offset,
    onDismiss: () -> Unit,
) {
    AnchoredMenu(anchorOffset, onDismiss) {
        val pinned = entry.pinned
        MenuOptionRow(R.drawable.ic_baseline_push_pin_24, if (pinned) stringResource(R.string.unpin) else stringResource(R.string.pin)) {
            if (pinned) callbacks.onUnpin(entry.id) else callbacks.onPin(entry.id)
        }
        MenuOptionRow(R.drawable.ic_baseline_edit_24, stringResource(R.string.edit)) { callbacks.onEdit(entry.id) }
        MenuOptionRow(R.drawable.ic_baseline_share_24, stringResource(R.string.share)) { callbacks.onShare(entry) }
        MenuOptionRow(R.drawable.ic_baseline_delete_24, stringResource(R.string.delete)) { callbacks.onDelete(entry.id) }
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
private fun AnchoredMenu(
    anchorOffset: Offset,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    var menuSize by remember { mutableStateOf<IntSize>(IntSize.Zero) }
    var containerSize by remember { mutableStateOf<IntSize>(IntSize.Zero) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .background(MiuixTheme.colorScheme.windowDimming)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.TopStart,
    ) {
        Surface(
            modifier = Modifier
                .offset {
                    // 期望以长按点为左上角，偏移 8dp；越界则收回窗口内
                    val targetX = anchorOffset.x + 8.dp.toPx()
                    val targetY = anchorOffset.y + 8.dp.toPx()
                    val maxX = (containerSize.width - menuSize.width).coerceAtLeast(0)
                    val maxY = (containerSize.height - menuSize.height).coerceAtLeast(0)
                    IntOffset(
                        targetX.toInt().coerceIn(0, maxX),
                        targetY.toInt().coerceIn(0, maxY),
                    )
                }
                .onSizeChanged { menuSize = it }
                .widthIn(min = 160.dp)
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
            .widthIn(min = 132.dp)
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