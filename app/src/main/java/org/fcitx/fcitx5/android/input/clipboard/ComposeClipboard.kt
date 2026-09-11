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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.input.bar.LongPressDelayProvider
import org.fcitx.fcitx5.android.input.bar.inputFeedback
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

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
    // 长按菜单状态提到这一层，并以 lambda 形式延迟读取（见 [ClipboardActionMenuHost]）。
    // 列表只拿到稳定的 onLongPress 回调，因此长按不再触发列表 / 卡片的整体重组。
    val menuState = remember { mutableStateOf<ClipboardMenuState?>(null) }
    val menuVisible = remember { mutableStateOf(false) }
    val onLongPress = remember(menuState, menuVisible) {
        { entry: ClipboardEntry, offset: Offset ->
            menuState.value = ClipboardMenuState(entry, offset)
            menuVisible.value = true
        }
    }
    Box(modifier = modifier.fillMaxSize()) {
        when (state) {
            ClipboardStateMachine.State.EnableListening -> EnableListeningUi(callbacks.onEnableListening)
            ClipboardStateMachine.State.AddMore -> AddMoreUi()
            ClipboardStateMachine.State.Normal -> ClipboardEntryList(
                entries,
                maskSensitive,
                callbacks,
                onRetry,
                onLongPress,
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
        // 菜单宿主与列表平级：自身读取状态 → 重组范围收敛到弹层，不触碰列表。
        ClipboardActionMenuHost(
            state = { menuState.value },
            visible = { menuVisible.value },
            callbacks = callbacks,
            onDismissRequest = { menuVisible.value = false },
            onDismissFinished = { menuState.value = null },
        )
    }
}

/**
 * 长按菜单宿主。
 *
 * 状态以 lambda 传入（延迟读取），因此只有本 composable 会因菜单状态变化而重组，
 * [ClipboardEntryList] 与各卡片完全不受影响。
 *
 * 注意：本 composable **不得引入额外的布局节点**。`ListPopupLayout` 的锚点取自其内部
 * `Spacer` 的**父布局**坐标（即窗口根），若这里包一层 wrapContent 容器，锚点会退化，
 * `PopupPositionProvider` 里的窗口坐标换算随之出错。
 */
@Composable
private fun ClipboardActionMenuHost(
    state: () -> ClipboardMenuState?,
    visible: () -> Boolean,
    callbacks: ClipboardCallbacks,
    onDismissRequest: () -> Unit,
    onDismissFinished: () -> Unit,
) {
    val current = state() ?: return
    ClipboardActionMenu(
        entry = current.entry,
        callbacks = callbacks,
        anchorOffset = current.anchorOffset,
        show = visible(),
        onDismissRequest = onDismissRequest,
        onDismissFinished = onDismissFinished,
    )
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
        modifier = modifier.inputFeedback(),
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
    onLongPress: (ClipboardEntry, Offset) -> Unit,
) {
    // 长按阈值统一走偏好（AppPrefs.keyboard.longPressDelay），不再依赖各 ROM 不一的
    // 系统 ViewConfiguration.getLongPressTimeout()。
    LongPressDelayProvider {
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
                    callbacks = callbacks,
                    onLongPress = onLongPress,
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
        CircularProgressIndicator(
            progress = null,
            size = 24.dp,
            strokeWidth = 3.dp,
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
    callbacks: ClipboardCallbacks,
    onLongPress: (ClipboardEntry, Offset) -> Unit,
) {
    val cardData = rememberCardData(entry, maskSensitive)
    val display = cardData.display
    val chips = cardData.chips
    val containerCoords = remember { mutableStateOf<LayoutCoordinates?>(null) }
    // 回调在卡片内部 remember：对外参数保持稳定（entry / Boolean / 稳定的 callbacks /
    // 稳定的 onLongPress），卡片因此可被 skip，不再因列表重组而全量重跑。
    val onPaste = remember(entry, callbacks) { { callbacks.onPaste(entry) } }
    val onChipClick = remember(callbacks) { { text: String -> callbacks.onPasteText(text) } }
    val onLongPressAction = remember(entry, onLongPress) {
        { offset: Offset -> onLongPress(entry, offset) }
    }
    // pointerInput 的 key 只有 entry.id，回调必须走 rememberUpdatedState 取最新值，
    // 否则 callbacks / onLongPress 变化后手势仍会调用旧闭包。
    val currentOnPaste by rememberUpdatedState(onPaste)
    val currentOnLongPressAction by rememberUpdatedState(onLongPressAction)
    // 触摸锚点：用于长按菜单跟随点击位置浮现
    val tapHandler = Modifier
        .onGloballyPositioned { containerCoords.value = it }
        .pointerInput(entry.id) {
            detectTapGestures(
                onTap = { currentOnPaste() },
                onLongPress = { offset ->
                    val rootOffset = containerCoords.value?.localToRoot(offset) ?: offset
                    currentOnLongPressAction(rootOffset)
                },
            )
        }
    Box(modifier = Modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .inputFeedback()
                .then(tapHandler),
            cornerRadius = 12.dp,
            insideMargin = PaddingValues(start = 14.dp, top = 10.dp, end = 14.dp, bottom = 10.dp),
            colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant),
            // 按压反馈交给 miuix 组件自带的 Sink（按下轻微缩小），不再手写压暗。
            // 不传 onClick/onLongPress，避免 miuix 内部的 combinedClickable 抢占手势，
            // 从而保留 tapHandler 提供的「长按点坐标」用于菜单锚定。
            pressFeedbackType = PressFeedbackType.Sink,
        ) {
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
                            onClick = { onChipClick(entity.value) },
                            modifier = Modifier.inputFeedback(),
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
        Button(
            onClick = onEnable,
            modifier = Modifier
                .align(Alignment.End)
                .inputFeedback(),
            colors = ButtonDefaults.buttonColorsPrimary(),
        ) {
            Text(
                text = stringResource(R.string.enable_listening),
                fontSize = 14.sp,
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

/**
 * 长按操作菜单：miuix Overlay 系列弹层（`OverlayListPopup`）。
 *
 * 该弹层不依赖系统 Dialog（内部只用 `AnimatedVisibility` + `Box` + `zIndex`），
 * 因此可在 IME 浮窗内使用；但它的宿主 `MiuixPopupHost` 必须由窗口根部的 miuix
 * `Scaffold` 提供（见 `ClipboardWindow.Content`）。
 *
 * 自带能力：窗口外点击 / 返回手势关闭、`ListPopupContent` 的 `surfaceContainer` 背景 +
 * 16dp 圆角 + clip-reveal 入场动画。
 *
 * **`enableWindowDim = false`**：miuix 在压暗模式下会额外组合一层
 * `AnimatedVisibility { Box(fillMaxSize().pointerInput{consume 全部事件}.background(windowDimming)) }`，
 * 即一块覆盖整个 IME 窗口的半透明层 + 全窗事件吞噬器，随 300ms 入场动画逐帧整窗重绘。
 * IME 本身已是浮层，压暗没有意义；而 `ListPopupLayout` 的 KDoc 明确「outside-tap dismiss
 * 与 enableWindowDim 无关」，因此关掉压暗**不损失**点击外部关闭。
 *
 * 定位沿用旧行为：以长按点为菜单左上角（外扩 8dp），越界则收回窗口内。
 */
@Composable
private fun ClipboardActionMenu(
    entry: ClipboardEntry,
    callbacks: ClipboardCallbacks,
    anchorOffset: Offset,
    show: Boolean,
    onDismissRequest: () -> Unit,
    onDismissFinished: () -> Unit,
) {
    val pinned = entry.pinned
    // anchorOffset 是 Compose 根坐标，而 PopupPositionProvider 要求窗口坐标。
    // 弹层的父级是铺满整个窗口的容器，其 anchorBounds 左上角即「根原点在窗口中的位置」，
    // 用它做一次换算即可，无需额外依赖 View 层级。
    val positionProvider = remember(anchorOffset) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowBounds: IntRect,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
                popupMargin: IntRect,
                alignment: PopupPositionProvider.Align,
            ): IntOffset {
                val maxX = (windowBounds.right - popupContentSize.width - popupMargin.right)
                    .coerceAtLeast(windowBounds.left)
                val maxY = (windowBounds.bottom - popupContentSize.height - popupMargin.bottom)
                    .coerceAtLeast(windowBounds.top)
                return IntOffset(
                    x = (anchorBounds.left + anchorOffset.x.toInt() + popupMargin.left)
                        .coerceIn(windowBounds.left, maxX),
                    y = (anchorBounds.top + anchorOffset.y.toInt() + popupMargin.top)
                        .coerceIn(windowBounds.top, maxY),
                )
            }

            override fun getMargins(): PaddingValues = PaddingValues(8.dp)
        }
    }
    val optionSize = 4
    OverlayListPopup(
        show = show,
        popupPositionProvider = positionProvider,
        alignment = PopupPositionProvider.Align.TopStart,
        // 关掉全窗压暗层（见上方 KDoc）：省掉一整层覆盖 IME 窗口的逐帧动画绘制，
        // 同时保留「点击窗口外关闭」。
        enableWindowDim = false,
        onDismissRequest = onDismissRequest,
        onDismissFinished = onDismissFinished,
    ) {
        ListPopupColumn {
            ActionMenuItem(
                iconResId = R.drawable.ic_baseline_push_pin_24,
                text = if (pinned) stringResource(R.string.unpin) else stringResource(R.string.pin),
                optionSize = optionSize,
                index = 0,
            ) {
                if (pinned) callbacks.onUnpin(entry.id) else callbacks.onPin(entry.id)
            }
            ActionMenuItem(
                iconResId = R.drawable.ic_baseline_edit_24,
                text = stringResource(R.string.edit),
                optionSize = optionSize,
                index = 1,
            ) { callbacks.onEdit(entry.id) }
            ActionMenuItem(
                iconResId = R.drawable.ic_baseline_share_24,
                text = stringResource(R.string.share),
                optionSize = optionSize,
                index = 2,
            ) { callbacks.onShare(entry) }
            ActionMenuItem(
                iconResId = R.drawable.ic_baseline_delete_24,
                text = stringResource(R.string.delete),
                optionSize = optionSize,
                index = 3,
            ) { callbacks.onDelete(entry.id) }
        }
    }
}

/**
 * 弹层菜单中的一行，复用 miuix [DropdownImpl]：
 * 自带 `selectable` + `LocalIndication` 按压高亮、图标格与行内边距规范。
 */
@Composable
private fun ActionMenuItem(
    iconResId: Int,
    text: String,
    optionSize: Int,
    index: Int,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val iconTint = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val item = DropdownItem(
        text = text,
        icon = { modifier ->
            Icon(
                painter = painterResource(iconResId),
                contentDescription = null,
                tint = iconTint,
                modifier = modifier.size(20.dp),
            )
        },
    )
    DropdownImpl(
        item = item,
        optionSize = optionSize,
        isSelected = false,
        index = index,
        onSelectedIndexChange = {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            onClick()
        },
    )
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
                .widthIn(min = 200.dp),
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