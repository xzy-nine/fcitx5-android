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
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
import androidx.compose.foundation.combinedClickable
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
import org.fcitx.fcitx5.android.input.keyboard.ComposeKey
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import top.yukonga.miuix.kmp.basic.VerticalDivider
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「展开候选」页面的整体排版：左栏一条竖排标签栏（与右侧内嵌键盘同款 `ComposeKey` 竖排等宽键），
 * 其右是「候选表格 + 1dp 分隔线 + 右侧内嵌键盘」两栏。
 *
 * - 左栏标签与右栏键盘宽度均取总宽 15%，中间网格占剩余；无标签时左栏与左分隔线隐藏、网格自动占满。
 * - 列数不直接取用户偏好，而是由 [computeGridSpanCount] 按**前段候选实测宽度（em）**与列数上限反推：
 *   与 View 侧 `SpanHelper` 同口径，避免短词把列数顶满、一行词数比 View 实现多一倍（滚动掉帧）。
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

    val hasTabs = tabs.isNotEmpty()

    // 列数只由「前段候选宽度（em）+ 列数上限」决定（与 View 侧 spanCount 口径一致），
    // 不再按可用宽度反推——按宽度反推会把短词的列数顶满上限，一行词数翻倍（滚动掉帧）。
    val span = computeGridSpanCount(
        leadingTextWidthsEm = leadingWidthsEm,
        minSpan = 2,
        maxSpan = maxSpanCount.coerceAtLeast(2),
    )

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface)
    ) {
        val totalWidthPx = with(density) { maxWidth.toPx() }
        // 左栏与右键盘同取总宽 15%；无标签时左栏隐藏，省去左分隔线。
        val leftPx = if (hasTabs) totalWidthPx * 0.15f else 0f
        val keyboardPx = totalWidthPx * 0.15f

        Row(Modifier.fillMaxSize()) {
            // 左栏：竖排标签键，与右键盘同款 Alternative 变体、等高分片。
            if (hasTabs) {
                Column(
                    Modifier
                        .fillMaxHeight()
                        .width(with(density) { leftPx.toDp() })
                ) {
                    tabs.forEachIndexed { index, tab ->
                        ExpandedCandidateTabKey(
                            tab = tab,
                            index = index,
                            onTabSelected = onTabSelected,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                    }
                }
                VerticalDivider(
                    thickness = 1.dp,
                    color = MiuixTheme.colorScheme.dividerLine,
                )
            }

            // 中间：候选表格，占剩余宽度。
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

            // 右栏：内嵌键盘（上翻 / 下翻 / 退格 / 回车）。
            ComposeExpandedCandidateKeyboard(
                returnDrawable = returnDrawable,
                pageUpEnabled = canPageUp,
                pageDownEnabled = canPageDown,
                keyActionListener = keyActionListener,
                popupActionListener = popupActionListener,
                modifier = Modifier.fillMaxHeight().width(with(density) { keyboardPx.toDp() }),
            )
        }
    }
}

/**
 * 单颗候选分组标签键：与右栏内嵌键盘同款的竖排等宽 `ComposeKey`。
 *
 * - 默认用 `Variant.Alternative`（与右键盘一致）；当选中（`isCheckable && isChecked`）时换成
 *   `Variant.Accent` 高亮，沿用现有主题语义，不引入新颜色/样式。
 * - 点击经独立 `KeyActionListener` 直接回调 [onTabSelected]，与右键盘监听互不干扰。
 * - `keyId` 用独立基址，避免与右键盘的 0..3 冲突（popup 索引）。
 */
@Composable
private fun ExpandedCandidateTabKey(
    tab: CandidateAction,
    index: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val variant = if (tab.isCheckable && tab.isChecked) {
        KeyDef.Appearance.Variant.Accent
    } else {
        KeyDef.Appearance.Variant.Alternative
    }
    ComposeKey(
        def = KeyDef(
            KeyDef.Appearance.Text(
                displayText = tab.text,
                textSize = 16f,
                variant = variant,
                percentWidth = 1f,
            ),
            setOf(KeyDef.Behavior.Press(KeyAction.LayoutSwitchAction(act = "EXP_TAB_$index"))),
        ),
        keyId = ExpandedCandidateTabKeyIdBase + index,
        keyActionListener = KeyActionListener { _, _ -> onTabSelected(index) },
        modifier = modifier,
    )
}

private const val ExpandedCandidateTabKeyIdBase = 0x8000

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
                    awaitFirstDown(requireUnconsumed = false).also { pressAnchor.pressOffset = it.position }
                }
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = {
                    val origin = pressAnchor.coordinates?.positionInWindow() ?: Offset.Zero
                    val point = origin + pressAnchor.pressOffset
                    onLongClick(Rect(point.x.toInt(), point.y.toInt(), point.x.toInt(), point.y.toInt()))
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
