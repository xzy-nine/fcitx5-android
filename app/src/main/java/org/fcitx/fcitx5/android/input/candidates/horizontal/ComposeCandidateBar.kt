/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.horizontal

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.Hide
import kotlinx.coroutines.flow.distinctUntilChanged
import org.fcitx.fcitx5.android.core.CandidateWord

/**
 * 候选栏视觉配置
 */
@Immutable
data class CandidateBarVisuals(
    val backgroundColor: Color,
    val textColor: Color,
    val commentColor: Color,
    val highlightColor: Color,
    val dividerColor: Color,
)

/**
 * 候选栏回调
 */
data class CandidateBarCallbacks(
    val onCandidateSelect: (Int) -> Unit,
    val onCandidateLongClick: ((Int, CandidateWord) -> Unit)? = null,
    val onExpandClick: (() -> Unit)? = null,
    val onHideKeyboard: (() -> Unit)? = null,
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
 * 参考 Xime 的 CandidateBar 实现，适配 fcitx5 数据结构
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
) {
    val listState = rememberLazyListState()
    val currentState by rememberUpdatedState(state)
    
    // 懒加载监听 + 滚动 offset 同步
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo }
            .distinctUntilChanged()
            .collect { layoutInfo ->
                if (currentState is CandidateBarState.Active) {
                    val firstVisibleIndex = layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0
                    callbacks.onScrollOffsetChanged?.invoke(firstVisibleIndex)
                    val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    val totalItems = layoutInfo.totalItemsCount
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
            .height(44.dp)
            .background(visuals.backgroundColor)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (state) {
            is CandidateBarState.Idle -> {
                // 空闲状态：不显示按钮（由工具栏处理）
            }
            is CandidateBarState.Active -> {
                // 候选词列表
                CandidateRow(
                    candidates = state.candidates,
                    fillMode = fillMode,
                    maxSpanCount = maxSpanCount,
                    listState = listState,
                    userScrollEnabled = userScrollEnabled,
                    textColor = visuals.textColor,
                    commentColor = visuals.commentColor,
                    highlightColor = visuals.highlightColor,
                    onCandidateSelect = callbacks.onCandidateSelect,
                    onCandidateLongClick = callbacks.onCandidateLongClick,
                    modifier = Modifier.weight(1f),
                )
                
                // 内侧：展开/收起按钮
                if (callbacks.onExpandClick != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    ExpandButton(
                        onClick = callbacks.onExpandClick,
                        isExpanded = isExpandMode,
                        tint = visuals.textColor,
                    )
                }
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
    highlightColor: Color,
    onCandidateSelect: (Int) -> Unit,
    onCandidateLongClick: ((Int, CandidateWord) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val shouldFill = when (fillMode) {
        CandidateFillMode.NeverFillWidth -> false
        CandidateFillMode.AutoFillWidth -> candidates.size >= maxSpanCount
        CandidateFillMode.AlwaysFillWidth -> true
    }
    
    LazyRow(
        state = listState,
        modifier = modifier,
        userScrollEnabled = userScrollEnabled,
        horizontalArrangement = if (shouldFill) {
            Arrangement.SpaceEvenly
        } else {
            Arrangement.spacedBy(4.dp)
        },
    ) {
        itemsIndexed(
            items = candidates,
            key = { index, candidate -> "$index-${candidate.text}" },
        ) { index, candidate ->
            CandidateItem(
                candidate = candidate,
                onClick = { onCandidateSelect(index) },
                onLongClick = {
                    onCandidateLongClick?.invoke(index, candidate)
                },
                textColor = textColor,
                commentColor = commentColor,
                isFirst = index == 0,
                highlightColor = highlightColor,
            )
        }
    }
}

/**
 * 单个候选项
 * 参考 Xime 的 CandidateItem 实现
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CandidateItem(
    candidate: CandidateWord,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    textColor: Color,
    commentColor: Color,
    isFirst: Boolean,
    highlightColor: Color,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(
                when {
                    isPressed -> highlightColor.copy(alpha = 0.2f)
                    isFirst -> highlightColor.copy(alpha = 0.1f)
                    else -> Color.Transparent
                }
            )
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 候选词文本
        Text(
            text = candidate.text,
            color = if (isFirst) highlightColor else textColor,
            fontSize = 18.sp,
            fontWeight = if (isFirst) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
        )
        
        // 注释文本（如拼音）
        if (candidate.comment.isNotBlank()) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = candidate.comment,
                color = commentColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}

/**
 * 收起键盘按钮
 */
@Composable
private fun HideKeyboardButton(
    onClick: () -> Unit,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    Box(
        modifier = modifier
            .size(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            MiuixIcons.Hide,
            contentDescription = "收起键盘",
            tint = if (isPressed) tint.copy(alpha = 0.6f) else tint,
            modifier = Modifier.size(22.dp),
        )
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
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    Box(
        modifier = modifier
            .size(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (isExpanded) MiuixIcons.ExpandLess else MiuixIcons.ExpandMore,
            contentDescription = if (isExpanded) "收起" else "展开",
            tint = if (isPressed) tint.copy(alpha = 0.6f) else tint,
            modifier = Modifier.size(22.dp),
        )
    }
}
