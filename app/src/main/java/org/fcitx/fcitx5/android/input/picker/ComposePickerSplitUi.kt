/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.candidates.expanded.ExpandedCandidateKeyboard
import org.fcitx.fcitx5.android.input.keyboard.BackspaceKey
import org.fcitx.fcitx5.android.input.keyboard.ComposeKey
import org.fcitx.fcitx5.android.input.keyboard.ImageLayoutSwitchKey
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.KeyboardLayoutNames
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import top.yukonga.miuix.kmp.basic.VerticalDivider
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Picker（符号 / Emoji / 颜文字）的「展开候选页」同款排版：**左栏分类标签 + 中栏上下滚动网格 +
 * 右栏竖排键盘**，取代原来的「顶部标签栏 + 横向分页网格 + 底部键盘行」。
 *
 * - 左栏与右栏同取总宽 15%（与 `ComposeExpandedCandidatesUi` 同口径），中栏占剩余宽度。
 * - 中栏是**单个连续滚动网格**（`LazyVerticalGrid`），含全部分类，「⟳最近使用」居首；
 *   点左栏标签滚动到该分类锚点，滚动时反向高亮左栏。
 * - 分类之间**换页**：插 [PickerCategoryGapRows] 行空行且下一分类从新的一行开始（见 [PickerGridLayout]），
 *   不再把两个分类直接接在一起。
 * - 格子复用 [cellDef]（与休眠的 [ComposePickerPage] 同一份外观逻辑），键面继续走键盘视觉桥
 *   （保留用户名下自定义主题/背景图，见 AGENTS.md「键盘域取色例外」）。
 * - 右栏自上而下：删除（退格） / 上翻 / 下翻 / 回车 / 返回主键盘；上翻下翻的可用态由网格滚动位置推导。
 * - 中栏列数 = 中栏可用宽度 / 最小格子宽，再由 [PickerDensity.columnCount]（旧版固定列数）封顶，
 *   避免横屏宽度大时（尤其符号 32dp）列数暴涨把格子压小；**行高固定**（不随列数/宽度变化），
 *   故宽屏只是格子变宽、可见行数只由可用高度决定（否则封顶列数后格子又宽又高，一屏只剩两行）。
 *
 * 与 `ComposeExpandedCandidatesUi` 的差异只在数据源与手势细节，三栏骨架、比例、分隔线、
 * 键型一律对齐，保证三模块（符号 / 表情 / 颜文字）与展开候选页风格统一。
 *
 * @param gridLayout 网格布局模型（分类 + 含换页空行的槽位 + 各分类锚点），由 `PickerWindow` 用
 *   [buildPickerGridLayout] 构建后传入：锚点同时也被窗口的「点标签滚动 / 初次复位」使用，
 *   放在窗口层构建可避免两处各算一份
 */
@Composable
internal fun ComposePickerSplitUi(
    gridLayout: PickerGridLayout,
    density: PickerDensity,
    bordered: Boolean,
    gridState: LazyGridState,
    gridScrollEnabled: Boolean,
    returnKeyDef: KeyDef,
    keyActionListener: KeyActionListener?,
    popupActionListener: PopupActionListener?,
    policy: PickerPolicy?,
    onCategorySelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sections = gridLayout.sections
    val composeDensity = LocalDensity.current
    val border = if (bordered) {
        KeyDef.Appearance.Border.On
    } else {
        KeyDef.Appearance.Border.Off
    }
    // 密度 → 格子最小宽度 + 纵横比（符号/Emoji 正方，颜文字宽扁以容纳长文本）。
    // remember：PickerCellSpec 每次调用都是新实例，不记忆会让网格 DSL 每次重组都重跑
    val cellSpec = remember(density) { density.cellSpec() }
    val minCellWidthPx = with(composeDensity) { cellSpec.minWidth.toPx() }
    val labels = remember(sections) { sections.map { it.category.label } }

    // 上/下翻键可用态：从网格滚动位置推导，到顶 / 到底置灰（同展开候选页）
    val canPageUp by remember {
        derivedStateOf {
            gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0
        }
    }
    val canPageDown by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last < info.totalItemsCount - 1
        }
    }

    // 反向联动：首可见槽位所属分类 → 左栏高亮；滚动到底时特判为末分类
    // （末分类通常无法贴顶，靠「不再能下翻且不在顶部」判定才点得亮）
    val sectionStarts = gridLayout.sectionStarts
    val activeCategory by remember(sections) {
        derivedStateOf {
            if (sections.isEmpty()) return@derivedStateOf 0
            val info = gridState.layoutInfo
            // 尚未布局 / 内容为空：按顶部分类处理，避免闪现「末分类」高亮
            if (info.totalItemsCount <= 0) return@derivedStateOf 0
            val atTop = gridState.firstVisibleItemIndex == 0 &&
                gridState.firstVisibleItemScrollOffset == 0
            if (!canPageDown && !atTop) return@derivedStateOf sections.lastIndex
            val first = info.visibleItemsInfo.firstOrNull()?.index ?: 0
            sectionStarts.indexOfLast { it <= first }.coerceAtLeast(0)
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val totalWidthPx = with(composeDensity) { maxWidth.toPx() }
        val sideWidthPx = totalWidthPx * SideColumnWidthFraction
        // 列数 = 中栏可用宽度 / 最小格子宽，再用**旧版固定列数**（[PickerDensity.columnCount]）封顶：
        // 横屏可用宽度大，只按 32dp（符号）反推会得出十几列，格子被压得又小又密；封顶后
        // 横屏的列数、格子宽度与改造前的固定列数布局一致。
        val middleWidthPx = totalWidthPx - sideWidthPx * 2 - 2 * with(composeDensity) { 1.dp.toPx() }
        val columnCount = (middleWidthPx / minCellWidthPx).toInt()
            .coerceIn(1, density.columnCount.coerceAtLeast(1))
        // 换页空行的高度 = 空行数 × 固定行高（与列数无关，故宽屏也不会被拉高）
        val gapHeight = cellSpec.height * PickerCategoryGapRows
        Row(Modifier.fillMaxSize()) {
            PickerSplitSidebar(
                labels = labels,
                activeIndex = activeCategory,
                onCategorySelected = onCategorySelected,
                modifier = Modifier
                    .fillMaxHeight()
                    .width(with(composeDensity) { sideWidthPx.toDp() }),
            )
            VerticalDivider(
                thickness = 1.dp,
                color = MiuixTheme.colorScheme.dividerLine,
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(columnCount),
                state = gridState,
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f),
                contentPadding = PaddingValues(GridContentPadding),
                userScrollEnabled = gridScrollEnabled,
            ) {
                items(
                    count = gridLayout.slots.size,
                    key = { index ->
                        val slot = gridLayout.slots[index]
                        if (slot.isGap) {
                            "gap-$index"
                        } else {
                            "$index-${sections[slot.section].items[slot.itemIndex]}"
                        }
                    },
                    // 换页空行占满一整行（下一个分类因此从新的一行开始）
                    span = { index ->
                        if (gridLayout.slots[index].isGap) GridItemSpan(maxLineSpan) else GridItemSpan(1)
                    },
                ) { index ->
                    val slot = gridLayout.slots[index]
                    if (slot.isGap) {
                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(gapHeight),
                        )
                        return@items
                    }
                    val section = sections[slot.section]
                    val raw = section.items[slot.itemIndex]
                    // 「⟳最近使用」节：不做 transform、不挂弹层
                    val sectionPolicy = if (section.hasPopups) policy else null
                    val display = sectionPolicy?.transform(raw) ?: raw
                    // 长按弹层必须按项记忆：`KeyDef.Popup.Keyboard.Explicit`（emoji 肤色）是**引用相等**
                    // 的普通类，每次重组都会新建实例 → 下面 remember 重跑 → 新 KeyDef → 新 behaviors Set
                    // → `ComposeKey` 的手势节点重启（等价 ACTION_CANCEL）→ 手势 finally 里的
                    // `dismissPreview()` 发 DismissAction 把刚弹出的浮窗关掉，表现为「长按浮窗只闪一下」。
                    val popupKeyboard = remember(raw, sectionPolicy) { sectionPolicy?.popup(raw) }
                    val def = remember(raw, display, border, density, popupKeyboard) {
                        cellDef(
                            display = display,
                            raw = raw,
                            density = density,
                            border = border,
                            popupKeyboard = popupKeyboard,
                        )
                    }
                    ComposeKey(
                        def = def,
                        // 网格按下标分配 keyId（0..N），与左栏 0x8000 / 右栏 0x9000 段错开
                        keyId = index,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(cellSpec.height),
                        keyActionListener = keyActionListener,
                        popupActionListener = if (section.hasPopups) {
                            popupActionListener
                        } else {
                            null
                        },
                        autoScale = density.autoScale,
                    )
                }
            }
            VerticalDivider(
                thickness = 1.dp,
                color = MiuixTheme.colorScheme.dividerLine,
            )
            PickerSplitKeyboard(
                returnKeyDef = returnKeyDef,
                pageUpEnabled = canPageUp,
                pageDownEnabled = canPageDown,
                keyActionListener = keyActionListener,
                popupActionListener = popupActionListener,
                modifier = Modifier
                    .fillMaxHeight()
                    .width(with(composeDensity) { sideWidthPx.toDp() }),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 左栏：分类标签（固定侧边栏）
// ---------------------------------------------------------------------------

/**
 * 左栏竖排分类标签（固定侧边栏，不可折叠、非抽屉）：`ComposeKey` 文本键，默认 `Alternative`、
 * 选中 `Accent` 高亮（与 `ExpandedCandidateTabKey` 同款键型）。
 *
 * - 响应式：单键高度 = `max(可用高度 / 标签数, 40.dp)`；放不下时可纵向滚动（横屏保护）。
 * - 激活标签变化时自动滚动进视野（居中，钳在合法滚动范围内）。
 */
@Composable
private fun PickerSplitSidebar(
    labels: List<String>,
    activeIndex: Int,
    onCategorySelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val composeDensity = LocalDensity.current
    BoxWithConstraints(modifier) {
        // 单键高度：等高均分，但不低于可点按下限；高度无界（不应发生）时退回下限
        val itemHeight = if (maxHeight == Dp.Infinity) {
            MinTabKeyHeight
        } else {
            maxOf(maxHeight / labels.size.coerceAtLeast(1), MinTabKeyHeight)
        }
        val itemHeightPx = with(composeDensity) { itemHeight.toPx() }
        // viewportSize 参与 key：首次组合时布局还没发生（值为 0），布局完成后需要补一次对齐
        val viewport = scrollState.viewportSize
        LaunchedEffect(activeIndex, itemHeightPx, viewport) {
            if (viewport <= 0) return@LaunchedEffect
            // 令激活标签居中；coerce 到 [0, maxValue] 保证横屏/短列表下不越界
            val target = activeIndex * itemHeightPx - (viewport - itemHeightPx) / 2f
            scrollState.animateScrollTo(
                target.coerceIn(0f, scrollState.maxValue.toFloat()).toInt()
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {
            labels.forEachIndexed { index, label ->
                PickerTabKey(
                    label = label,
                    index = index,
                    active = index == activeIndex,
                    onCategorySelected = onCategorySelected,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight),
                )
            }
        }
    }
}

/**
 * 单颗分类标签键：键面与右栏键盘同款（`ComposeKey` 文本键），点击经独立监听回调分类下标。
 *
 * `def` 按 `(label, active)` 记忆：`ComposeKey` 用 `def.behaviors` 作手势节点的 key，
 * 每次重组都新建 `KeyDef` 会把进行中的按压手势重启（等于补一次 ACTION_CANCEL）。
 */
@Composable
private fun PickerTabKey(
    label: String,
    index: Int,
    active: Boolean,
    onCategorySelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val def = remember(label, active) {
        KeyDef(
            KeyDef.Appearance.Text(
                displayText = label,
                textSize = TabTextSize,
                variant = if (active) {
                    KeyDef.Appearance.Variant.Accent
                } else {
                    KeyDef.Appearance.Variant.Alternative
                },
                percentWidth = 1f,
            ),
            setOf(KeyDef.Behavior.Press(KeyAction.LayoutSwitchAction(act = "PICKER_TAB"))),
        )
    }
    ComposeKey(
        def = def,
        keyId = SidebarKeyIdBase + index,
        // 标签点击直接回调分类下标，与网格/右栏的按键监听互不干扰
        keyActionListener = KeyActionListener { _, _ -> onCategorySelected(index) },
        modifier = modifier,
        autoScale = true,
    )
}

// ---------------------------------------------------------------------------
// 右栏：竖排键盘（删除 / 上翻 / 下翻 / 回车 / 返回主键盘）
// ---------------------------------------------------------------------------

/**
 * 右栏竖排键盘，自上而下：**删除（退格） / 上翻 / 下翻 / 回车 / 返回主键盘**。
 *
 * 各键 `def` 一律 [remember]：这些预设每次调用都是新实例（`behaviors` 也是新 Set），
 * 不记忆会让 `ComposeKey` 的手势节点在每次重组时重启（等价 ACTION_CANCEL），
 * 长按弹层（回车 → Emoji 菜单）会因此被关掉。
 */
@Composable
private fun PickerSplitKeyboard(
    returnKeyDef: KeyDef,
    pageUpEnabled: Boolean,
    pageDownEnabled: Boolean,
    keyActionListener: KeyActionListener?,
    popupActionListener: PopupActionListener?,
    modifier: Modifier = Modifier,
) {
    val backKey = remember {
        ImageLayoutSwitchKey(
            R.drawable.ic_baseline_arrow_back_24,
            KeyboardLayoutNames.Text,
            variant = KeyDef.Appearance.Variant.Accent,
        )
    }
    val pageUpKey = remember {
        ImageLayoutSwitchKey(
            R.drawable.ic_baseline_arrow_upward_24,
            to = ExpandedCandidateKeyboard.PageUp,
            variant = KeyDef.Appearance.Variant.Alternative,
        )
    }
    val pageDownKey = remember {
        ImageLayoutSwitchKey(
            R.drawable.ic_baseline_arrow_downward_24,
            to = ExpandedCandidateKeyboard.PageDown,
            variant = KeyDef.Appearance.Variant.Alternative,
        )
    }
    val backspaceKey = remember { BackspaceKey() }
    Column(modifier) {
        ComposeKey(
            def = backspaceKey,
            keyId = SplitKeyboardKeyIdBase,
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            keyActionListener = keyActionListener,
            popupActionListener = popupActionListener,
        )
        ComposeKey(
            def = pageUpKey,
            keyId = SplitKeyboardKeyIdBase + 1,
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            keyActionListener = keyActionListener,
            popupActionListener = popupActionListener,
            enabled = pageUpEnabled,
        )
        ComposeKey(
            def = pageDownKey,
            keyId = SplitKeyboardKeyIdBase + 2,
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            keyActionListener = keyActionListener,
            popupActionListener = popupActionListener,
            enabled = pageDownEnabled,
        )
        ComposeKey(
            def = returnKeyDef,
            keyId = SplitKeyboardKeyIdBase + 3,
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            keyActionListener = keyActionListener,
            popupActionListener = popupActionListener,
        )
        ComposeKey(
            def = backKey,
            keyId = SplitKeyboardKeyIdBase + 4,
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            keyActionListener = keyActionListener,
            popupActionListener = popupActionListener,
        )
    }
}

// ---------------------------------------------------------------------------
// 密度映射
// ---------------------------------------------------------------------------

/**
 * 格子尺寸规格：**最小宽度**（决定列数）+ **固定行高**（与列数/宽度无关）。
 *
 * 行高刻意不按「格子宽 ÷ 纵横比」算：那样列数越少（宽屏封顶后）格子越宽越高，
 * 一屏只能挤下两行。固定行高后宽屏只是格子变宽，可见行数只由可用高度决定。
 */
private class PickerCellSpec(val minWidth: Dp, val height: Dp)

/**
 * 密度 → 格子规格：符号 32dp / Emoji 44dp / 颜文字 76dp 宽 × 36dp 高。
 *
 * 行高取「最窄格子的宽度」（颜文字按其宽扁比例 76/2.2 ≈ 35dp，取整 36dp）：
 * 窄屏时格子仍是原设计的方形观感，宽屏时变成宽扁格，行数不被列数吃掉。
 * 列数不取 [PickerDensity.columnCount]，而是按可用宽度反推后用其封顶，
 * 故只保留字号/缩放（[cellDef] 仍读 [PickerDensity.textSize] / [PickerDensity.autoScale]）。
 */
private fun PickerDensity.cellSpec(): PickerCellSpec = when (this) {
    PickerDensity.High -> PickerCellSpec(minWidth = 32.dp, height = 32.dp)
    PickerDensity.Medium -> PickerCellSpec(minWidth = 44.dp, height = 44.dp)
    PickerDensity.Low -> PickerCellSpec(minWidth = 76.dp, height = 36.dp)
}

/** 左栏与右栏各占总宽的比例（与展开候选页一致）。 */
private const val SideColumnWidthFraction = 0.15f

/** 中栏网格的四周内边距。 */
private val GridContentPadding = 4.dp

/** 左栏标签字号。 */
private const val TabTextSize = 14f

/** 左栏标签的最小高度（横屏标签多时可滚动，保证仍可点按）。 */
private val MinTabKeyHeight = 40.dp

/** 左栏标签 keyId 段（`PopupComponent` 按 Int id 索引，需与网格 0..N、右栏段错开）。 */
private const val SidebarKeyIdBase = 0x8000

/** 右栏键盘 keyId 段。 */
private const val SplitKeyboardKeyIdBase = 0x9000
