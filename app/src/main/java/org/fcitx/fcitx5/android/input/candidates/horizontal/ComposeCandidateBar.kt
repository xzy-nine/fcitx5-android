/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.horizontal

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import org.fcitx.fcitx5.android.input.bar.inputFeedback
import org.fcitx.fcitx5.android.data.InputFeedbacks
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.fcitx.fcitx5.android.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.VerticalDivider
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import kotlinx.coroutines.flow.distinctUntilChanged
import org.fcitx.fcitx5.android.core.CandidateWord

/**
 * 候选栏视觉配置
 */
@Immutable
data class CandidateBarVisuals(
    val textColor: Color,
    val commentColor: Color,
    val pressHighlightColor: Color,
    val dividerColor: Color,
)

/**
 * 候选栏回调
 */
data class CandidateBarCallbacks(
    val onCandidateSelect: (Int) -> Unit,
    /** 长按候选词：回调窗口绝对坐标（x, y），供菜单锚定；由调用方完成坐标换算 */
    val onCandidateLongClick: ((Int, CandidateWord, Offset) -> Unit)? = null,
    val onExpandClick: (() -> Unit)? = null,
    val onLoadMore: (() -> Unit)? = null,
    val onScrollOffsetChanged: ((Int) -> Unit)? = null,
)

/**
 * 候选栏填充模式
 */
enum class CandidateFillMode {
    NeverFillWidth,
    AutoFillWidth,
    AlwaysFillWidth,
}

private const val LOAD_MORE_THRESHOLD = 3

/**
 * Compose 候选栏组件
 */
@Composable
fun ComposeCandidateBar(
    state: CandidateBarState,
    visuals: CandidateBarVisuals,
    callbacks: CandidateBarCallbacks,
    modifier: Modifier = Modifier,
    fillMode: CandidateFillMode = CandidateFillMode.AutoFillWidth,
    maxSpanCount: Int = 5,
    userScrollEnabled: Boolean = true,
    isExpandMode: Boolean = false,
    barHeight: Dp = 44.dp,
    showDivider: Boolean = false,
) {
    val listState = rememberLazyListState()
    val currentState by rememberUpdatedState(state)
    val callbacks by rememberUpdatedState(callbacks)

    // 懒加载监听 + 滚动 offset 同步
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            Triple(
                info.visibleItemsInfo.firstOrNull()?.index ?: 0,
                info.visibleItemsInfo.lastOrNull()?.index ?: 0,
                info.totalItemsCount
            )
        }
            .distinctUntilChanged()
            .collect { (firstVisibleIndex, lastVisibleIndex, totalItems) ->
                if (currentState is CandidateBarState.Active) {
                    callbacks.onScrollOffsetChanged?.invoke(firstVisibleIndex)
                    if (lastVisibleIndex >= totalItems - LOAD_MORE_THRESHOLD) {
                        callbacks.onLoadMore?.invoke()
                    }
                }
            }
    }

    // 当状态变为 Idle 时重置滚动位置
    LaunchedEffect(state) {
        if (state is CandidateBarState.Idle) {
            listState.scrollToItem(0)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // LazyRow 始终留在组合中（空态 items=0）：候选栏 Idle→Active 时不重新创建 LazyRow，
        // 避免首次进入组合的测量/布局延迟导致显示时整行闪烁
        CandidateRow(
            candidates = if (state is CandidateBarState.Active) state.candidates else emptyArray(),
            fillMode = fillMode,
            maxSpanCount = maxSpanCount,
            listState = listState,
            userScrollEnabled = userScrollEnabled,
            textColor = visuals.textColor,
            commentColor = visuals.commentColor,
            pressHighlightColor = visuals.pressHighlightColor,
            dividerColor = visuals.dividerColor,
            showDivider = showDivider,
            onCandidateSelect = callbacks.onCandidateSelect,
            onCandidateLongClick = callbacks.onCandidateLongClick,
            endPadding = if (callbacks.onExpandClick != null) 40.dp else 0.dp,
            modifier = Modifier.weight(1f),
        )

        // 展开/收起按钮仅在有候选时显示
        if (state is CandidateBarState.Active) {
            val expandClick = callbacks.onExpandClick
            if (expandClick != null) {
                Spacer(modifier = Modifier.width(8.dp))
                ExpandButton(
                    onClick = expandClick,
                    isExpanded = isExpandMode,
                    tint = visuals.textColor,
                )
            }
        }
    }
}

/**
 * 候选词行
 */
@Composable
private fun CandidateRow(
    candidates: Array<CandidateWord>,
    fillMode: CandidateFillMode,
    maxSpanCount: Int,
    listState: androidx.compose.foundation.lazy.LazyListState,
    userScrollEnabled: Boolean,
    textColor: Color,
    commentColor: Color,
    pressHighlightColor: Color,
    dividerColor: Color,
    showDivider: Boolean,
    onCandidateSelect: (Int) -> Unit,
    onCandidateLongClick: ((Int, CandidateWord, Offset) -> Unit)?,
    endPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    // AutoFillWidth: 当候选项少于 maxSpanCount 时，计算每个 item 的最小宽度
    // 匹配旧实现: layoutMinWidth = view.width / maxSpanCount - dividerWidth
    val dividerWidthDp = 1.dp
    val shouldFill = when (fillMode) {
        CandidateFillMode.NeverFillWidth -> false
        CandidateFillMode.AutoFillWidth -> candidates.size >= maxSpanCount
        CandidateFillMode.AlwaysFillWidth -> true
    }

    BoxWithConstraints(modifier = modifier) {
        val containerWidth = maxWidth
        // 计算 itemMinWidth: 仅在 AutoFillWidth 且候选项 < maxSpanCount 时生效
        val itemMinWidthDp = when {
            fillMode == CandidateFillMode.AutoFillWidth && candidates.size < maxSpanCount -> {
                containerWidth / maxSpanCount - dividerWidthDp
            }
            else -> 0.dp
        }

        LazyRow(
            state = listState,
            userScrollEnabled = userScrollEnabled,
            contentPadding = PaddingValues(end = endPadding),
            horizontalArrangement = if (shouldFill) {
                Arrangement.SpaceEvenly
            } else if (itemMinWidthDp > 0.dp) {
                // AutoFillWidth + 少数候选项: 用 SpaceEvenly 分配等宽
                Arrangement.SpaceEvenly
            } else {
                Arrangement.spacedBy(if (showDivider) 1.dp else 4.dp)
            },
        ) {
            itemsIndexed(
                items = candidates,
                key = { index, candidate -> "$index-${candidate.text}" },
            ) { index, candidate ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CandidateItem(
                        candidate = candidate,
                        onClick = { onCandidateSelect(index) },
                        onLongClick = { offset ->
                            onCandidateLongClick?.invoke(index, candidate, offset)
                        },
                        textColor = textColor,
                        commentColor = commentColor,
                        pressHighlightColor = pressHighlightColor,
                        modifier = if (itemMinWidthDp > 0.dp) {
                            Modifier.width(itemMinWidthDp)
                        } else {
                            Modifier
                        },
                    )
                    // 分割线
                    if (showDivider && index < candidates.lastIndex) {
                        VerticalDivider(
                            modifier = Modifier.height(24.dp),
                            color = dividerColor.copy(alpha = 0.3f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单个候选项
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CandidateItem(
    candidate: CandidateWord,
    onClick: () -> Unit,
    onLongClick: (Offset) -> Unit,
    textColor: Color,
    commentColor: Color,
    pressHighlightColor: Color,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    // 最近一次按压点（本 item 局部坐标）
    val pressOffset = remember { mutableStateOf(Offset.Zero) }
    // 本 item 自身的坐标，用于把「item 局部长按点」换算成窗口绝对坐标
    val itemCoordinates = remember { mutableStateOf<LayoutCoordinates?>(null) }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(
                if (isPressed) pressHighlightColor else Color.Transparent
            )
            .inputFeedback()
            .onGloballyPositioned { itemCoordinates.value = it }
            .pointerInput(candidate.text) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pressOffset.value = down.position
                }
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = {
                    // 直接用 item 自身坐标换算，避免遗漏 item 在 LazyRow 内的偏移（含滚动）
                    val origin = itemCoordinates.value?.positionInWindow() ?: Offset.Zero
                    onLongClick(origin + pressOffset.value)
                },
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 候选词文本
        Text(
            text = candidate.text,
            color = textColor,
            fontSize = 20.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
        )

        // 注释文本（如拼音）
        if (candidate.comment.isNotBlank()) {
            if (candidate.spaceBetweenComment) {
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = candidate.comment,
                color = commentColor,
                fontSize = 20.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}

/**
 * 展开按钮
 */
@Composable
private fun ExpandButton(
    onClick: () -> Unit,
    isExpanded: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(32.dp)
            .inputFeedback(),
        cornerRadius = 16.dp,
        minWidth = 32.dp,
        minHeight = 32.dp,
    ) {
        Icon(
            if (isExpanded) MiuixIcons.ExpandLess else MiuixIcons.ExpandMore,
            contentDescription = stringResource(
                if (isExpanded) R.string.candidate_collapse else R.string.candidate_expand
            ),
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    }
}
