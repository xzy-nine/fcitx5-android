/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.expanded

import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.compose.LazyPagingItems
import kotlin.math.max
import org.fcitx.fcitx5.android.core.CandidateAction
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.input.bar.inputFeedback
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.VerticalDivider
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「展开候选」页面的整体排版：顶部一条横向胶囊标签栏（miuix `TabRow`），
 * 其下是「候选表格 + 1dp 分隔线 + 右侧内嵌键盘」三栏。
 *
 * - 列数不取用户偏好，而是由 [computeGridSpanCount] 按**候选区可用宽度**与**前段候选实测文本宽度**算：
 *   前几条长候选（词组 / 句子）能整条放进格子，后续短词自然排满整行。
 * - 翻页按钮（内嵌键盘的上/下）的可用态由 [LazyGridState] 的滚动位置推导，到顶/到底置灰。
 * - 长按候选弹菜单的锚点用「窗口坐标 + 按下偏移」换算成 `Rect`，与横向候选栏同口径。
 */
@Composable
fun ComposeExpandedCandidatesUi(
    items: LazyPagingItems<CandidateWord>,
    tabs: List<CandidateAction>,
    gridState: LazyGridState,
    maxSpanCount: Int,
    returnDrawable: Int,
    keyActionListener: KeyActionListener?,
    popupActionListener: PopupActionListener?,
    onTabSelected: (Int) -> Unit,
    onCandidateSelect: (Int) -> Unit,
    onCandidateLongClick: (Int, String, Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val candidateStyle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Normal)

    // 前段候选实测文本宽度：只取首屏前 K 条，避免滚动时抖动（与 computeGridSpanCount 采样约定一致）。
    val leadingCount = items.itemCount
    val leadingWidths = remember(leadingCount, density) {
        items.itemSnapshotList
            .filterNotNull()
            .take(ExpandedCandidateLeadingSampleCount)
            .map { c ->
            val wText = measurer.measure(c.text, candidateStyle).size.width.toFloat()
            val wComment = if (c.comment.isNotBlank()) {
                measurer.measure(c.comment, candidateStyle).size.width.toFloat()
            } else {
                0f
            }
            max(wText, wComment)
        }
    }

    // 翻页按钮可用态：从网格滚动位置推导（到顶/到底置灰）。
    val canPageUp by remember {
        derivedStateOf { gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0 }
    }
    val canPageDown by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last < info.totalItemsCount - 1
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface)
    ) {
        // 顶部标签栏：无标签整条隐藏；checkable 项决定选中下标（无选中则取 0）。
        val selectedTabIndex = tabs.indexOfFirst { it.isCheckable && it.isChecked }.let { if (it < 0) 0 else it }
        if (tabs.isNotEmpty()) {
            TabRow(
                tabs = tabs.map { it.text },
                selectedTabIndex = selectedTabIndex,
                onTabSelected = onTabSelected,
                colors = TabRowDefaults.tabRowColors(
                    backgroundColor = MiuixTheme.colorScheme.surface,
                    contentColor = MiuixTheme.colorScheme.onSurface,
                    selectedBackgroundColor = MiuixTheme.colorScheme.primary,
                    selectedContentColor = MiuixTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 主体：候选表格 + 分隔线 + 内嵌键盘。用 BoxWithConstraints 拿到可用宽度再算列数。
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val totalWidthPx = with(density) { maxWidth.toPx() }
            val keyboardWidthPx = totalWidthPx * 0.15f
            val dividerPx = with(density) { 1.dp.toPx() }
            val gridWidthPx = (totalWidthPx - keyboardWidthPx - dividerPx).coerceAtLeast(0f)
            val cellPaddingPx = with(density) { 16.dp.toPx() }
            val span = computeGridSpanCount(
                availableWidthPx = gridWidthPx,
                leadingTextWidthsPx = leadingWidths,
                itemHorizontalPaddingPx = cellPaddingPx,
                minSpan = 2,
                maxSpan = maxSpanCount.coerceAtLeast(2),
            )

            Row(Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(span),
                    state = gridState,
                    modifier = Modifier.fillMaxHeight().weight(1f),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(
                        count = items.itemCount,
                        key = { index -> "$index-${items.peek(index)?.text}" },
                    ) { index ->
                        val candidate = items[index] ?: return@items
                        ExpandedCandidateCell(
                            candidate = candidate,
                            onClick = { onCandidateSelect(index) },
                            onLongClick = { anchor -> onCandidateLongClick(index, candidate.text, anchor) },
                        )
                    }
                }
                VerticalDivider(
                    thickness = 1.dp,
                    color = MiuixTheme.colorScheme.dividerLine,
                )
                ComposeExpandedCandidateKeyboard(
                    returnDrawable = returnDrawable,
                    pageUpEnabled = canPageUp,
                    pageDownEnabled = canPageDown,
                    keyActionListener = keyActionListener,
                    popupActionListener = popupActionListener,
                    modifier = Modifier.fillMaxHeight().width(with(density) { keyboardWidthPx.toDp() }),
                )
            }
        }
    }
}

/**
 * 单个候选项：单行、超长自动缩字号（[TextAutoSize.StepBased]）、带注释、按下浅色高亮 + 触感反馈，
 * 单击上屏、长按弹操作菜单（锚点用按下点 + 窗口坐标换算成零尺寸 `Rect`）。
 */
@Composable
private fun ExpandedCandidateCell(
    candidate: CandidateWord,
    onClick: () -> Unit,
    onLongClick: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressOffset = remember { mutableStateOf(Offset.Zero) }
    val itemCoordinates = remember { mutableStateOf<LayoutCoordinates?>(null) }

    Row(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isPressed) {
                    MiuixTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                } else {
                    Color.Transparent
                }
            )
            .inputFeedback()
            .onGloballyPositioned { itemCoordinates.value = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).also { pressOffset.value = it.position }
                }
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = {
                    val origin = itemCoordinates.value?.positionInWindow() ?: Offset.Zero
                    val point = origin + pressOffset.value
                    onLongClick(Rect(point.x.toInt(), point.y.toInt(), point.x.toInt(), point.y.toInt()))
                },
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = candidate.text,
            style = TextStyle(
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 20.sp,
                fontWeight = FontWeight.Normal,
            ),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 20.sp * 0.4f, maxFontSize = 20.sp, stepSize = 1.sp),
        )
        if (candidate.comment.isNotBlank()) {
            if (candidate.spaceBetweenComment) {
                Spacer(Modifier.width(4.dp))
            }
            BasicText(
                text = candidate.comment,
                style = TextStyle(
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Normal,
                ),
                maxLines = 1,
            )
        }
    }
}
