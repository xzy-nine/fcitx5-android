/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.expanded

import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.compose.LazyPagingItems
import org.fcitx.fcitx5.android.core.CandidateAction
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.input.bar.inputFeedback
import org.fcitx.fcitx5.android.input.candidates.ComposeSplitCandidatesUi
import org.fcitx.fcitx5.android.input.candidates.SplitTab
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「展开候选」页面的中栏内容 + 三栏骨架调用。
 *
 * 三栏骨架（左标签栏 / 中候选表格 / 右内嵌键盘）与 Picker 共用 [ComposeSplitCandidatesUi]，
 * 本文件只负责候选特有的部分：
 *
 * - 列数不取用户偏好，而由 [computeGridSpanCount] 按**前段候选实测宽度（em）**与列数上限反推：
 *   与 View 侧 `SpanHelper` 同口径，避免短词把列数顶满、一行词数比 View 实现多一倍（滚动掉帧）；
 * - 翻页按钮（内嵌键盘的上/下）的可用态由网格滚动位置推导（到顶/到底置灰）——在共用组件里；
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
    val candidateStyle = TextStyle(fontSize = CandidateFontSize, fontWeight = FontWeight.Normal)
    // 1em 的像素宽度：把实测宽度换算成 em，与 View 侧 SpanHelper 的 1.5em 口径对齐。
    val emWidthPx = with(density) { CandidateFontSize.toPx() }

    // 前段候选实测文本宽度（单位 em）：只取首屏前 K 条，避免滚动时抖动（与 computeGridSpanCount 采样约定一致）。
    // 宽度按「文本 + 空格 + 注释」整体测量（textWithComment），与格子内实际渲染的一条文本同口径。
    // 记忆 key 用「首个候选文本」而非 itemCount：同一查询内稳定，仅在换词时重算；
    // 若按 itemCount 记忆，则每翻一页加载新数据都会触发列数重算、导致整个网格重排（翻页卡顿）。
    // firstCandidateText 走 derivedStateOf：itemSnapshotList 是 mutableStateOf，直接读会让整页
    // 在每次分页追加时都重组；派生值只在首条候选真的换掉时才通知重组。
    val firstCandidateText by remember(items) {
        derivedStateOf { items.itemSnapshotList.firstOrNull()?.text }
    }
    val leadingWidthsEm = remember(density, firstCandidateText) {
        items.itemSnapshotList
            .filterNotNull()
            .take(ExpandedCandidateLeadingSampleCount)
            .map { c -> measurer.measure(c.textWithComment(), candidateStyle).size.width / emWidthPx }
    }

    // 列数只由「前段候选宽度（em）+ 列数上限」决定（与 View 侧 spanCount 口径一致），
    // 不再按可用宽度反推——按宽度反推会把短词的列数顶满上限，一行词数翻倍（滚动掉帧）。
    val span = computeGridSpanCount(
        leadingTextWidthsEm = leadingWidthsEm,
        minSpan = 2,
        maxSpan = maxSpanCount.coerceAtLeast(2),
    )

    ComposeSplitCandidatesUi(
        tabs = remember(tabs) { tabs.map { SplitTab(it.text, it.isCheckable && it.isChecked) } },
        gridState = gridState,
        returnDrawable = returnDrawable,
        keyActionListener = keyActionListener,
        popupActionListener = popupActionListener,
        onTabSelected = onTabSelected,
        // 展开候选的列数由实测宽度决定，与中栏可用宽度无关
        columns = { _ -> span },
        modifier = modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface),
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
}

/** 展开候选文本的字号上限（与横向候选栏一致）。 */
private val CandidateFontSize = 20.sp

/** 展开候选文本超长时可缩到的最小字号。 */
private val CandidateMinFontSize = 8.sp

/**
 * 候选项长按菜单的锚点数据：按下点（指针局部坐标）+ 自身窗口坐标。
 *
 * 刻意**不用** `mutableStateOf`：这两个值只在长按回调里读，不参与重组；而
 * `onGloballyPositioned` 在滚动时每一帧都会对每个可见格子触发一次，写 snapshot 状态是纯开销。
 */
private class PressAnchorHolder {
    var pressOffset: Offset = Offset.Zero
    var coordinates: LayoutCoordinates? = null
}

/**
 * 单个候选项：单行、超长自动缩字号（[ExpandedCandidateAutoSize]）、带注释、按下浅色高亮 + 触感反馈，
 * 单击上屏、长按弹操作菜单（锚点用按下点 + 窗口坐标换算成零尺寸 `Rect`）。
 *
 * 文本与注释合成**一条** `BasicText`（注释用主题色区分，与 View 侧 `CandidateItemUi` 的
 * `buildSpannedString` 同口径）：既省一半文本节点/测量，又保证超长时两者一起缩小，
 * 不会出现「文本缩了、注释被截断」。
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
    // 按下点与自身窗口坐标只在「长按弹菜单」时才读，用普通持有者即可：
    // 写成 mutableStateOf 会让每个可见格子**每帧布局**都写一次 snapshot 状态（滚动时的纯开销）。
    val pressAnchor = remember { PressAnchorHolder() }
    val textColor = MiuixTheme.colorScheme.onSurface
    val commentColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val content = remember(candidate, textColor, commentColor) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = textColor)) { append(candidate.text) }
            if (candidate.comment.isNotBlank()) {
                if (candidate.spaceBetweenComment) append(' ')
                withStyle(SpanStyle(color = commentColor)) { append(candidate.comment) }
            }
        }
    }

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
            .onGloballyPositioned { pressAnchor.coordinates = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).also {
                        pressAnchor.pressOffset = it.position
                    }
                }
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = {
                    val origin = pressAnchor.coordinates?.positionInWindow() ?: Offset.Zero
                    val point = origin + pressAnchor.pressOffset
                    onLongClick(
                        Rect(
                            point.x.toInt(),
                            point.y.toInt(),
                            point.x.toInt(),
                            point.y.toInt()
                        )
                    )
                },
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = content,
            style = TextStyle(fontSize = CandidateFontSize, fontWeight = FontWeight.Normal),
            maxLines = 1,
            autoSize = ExpandedCandidateAutoSize,
        )
    }
}

/**
 * 展开候选文本的自动缩字号：与 View 侧 `AutoScaleTextView(Mode.Proportional)` 同思路，
 * 用「可用宽度 / 自然宽度」的**比例一步**算出目标字号。
 *
 * 不用 [TextAutoSize.StepBased]：它对每次测量做二分搜索（`⌈log2(字号区间 / 步长)⌉` 次完整排版，
 * 20sp→8sp/1sp 约 4~5 次），格子多时滚动明显掉帧；本实现只需 1 次探测 + 1 次最终排版。
 *
 * 单例：`equals` 用引用比较，符合 [TextAutoSize] 对性能敏感路径的约定。
 */
private object ExpandedCandidateAutoSize : TextAutoSize {

    override fun TextAutoSizeLayoutScope.getFontSize(
        constraints: Constraints,
        text: AnnotatedString,
    ): TextUnit {
        val availableWidth = constraints.maxWidth
        // 先用无界宽度量一次拿「自然宽度」：有界测量在溢出时只会返回约束宽度，求不出比例。
        val naturalWidth = performLayout(
            constraints = Constraints(maxWidth = Constraints.Infinity),
            text = text,
            fontSize = CandidateFontSize,
        ).size.width
        if (availableWidth <= 0 || naturalWidth <= 0 || naturalWidth <= availableWidth) {
            return CandidateFontSize
        }
        // 字号与文本宽度近似线性，按比例一步缩到位并钳在 [min, max] 内
        val scaledPx = CandidateFontSize.toPx() * availableWidth / naturalWidth
        return scaledPx.coerceIn(CandidateMinFontSize.toPx(), CandidateFontSize.toPx()).toSp()
    }

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = System.identityHashCode(this)
}
