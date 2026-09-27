/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.candidates

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.keyboard.BackspaceKey
import org.fcitx.fcitx5.android.input.keyboard.ComposeKey
import org.fcitx.fcitx5.android.input.keyboard.ImageLayoutSwitchKey
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.ReturnKey
import org.fcitx.fcitx5.android.input.keyboard.preferenceState
import org.fcitx.fcitx5.android.input.keyboard.spaceAndBackspaceGestureListener
import org.fcitx.fcitx5.android.input.keyboard.spaceAndBackspaceSwipeSpec
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import top.yukonga.miuix.kmp.basic.VerticalDivider
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「左栏分类标签 + 中栏网格 + 右栏内嵌键盘」三栏骨架：**展开候选页与 Picker（符号/表情/颜文字）
 * 共用同一套实现**，两侧再各自提供中栏内容即可（展开候选 = 候选格，Picker = 符号槽位网格）。
 *
 * 共用带来的好处（也是本次统一的动机）：
 * - 布局比例（两侧各 15%、1dp 分隔线）、左栏标签键型与自动滚入视野、右栏键位与置灰规则只有一份；
 * - **右栏「删除（退格）」只有一份**，且与主键盘同口径：按下删除、长按连发、横向滑动移动光标 /
 *   删除选区（[spaceAndBackspaceSwipeSpec] + [spaceAndBackspaceGestureListener]）—— 之前 Picker
 *   侧的删除键缺这部分功能。
 *
 * 中栏由调用方决定：`columns` 给出列数（Picker 按中栏可用宽度反推并封顶；展开候选用实测列数，
 * 忽略宽度），`gridContent` 写 `LazyVerticalGrid` 的 items。
 *
 * @param tabs 左栏标签（文本 + 是否激活）；为空时不显示左栏与左分隔线（展开候选页无标签时）
 * @param columns 中栏列数，入参为中栏可用宽度（已扣掉两侧栏与分隔线）
 * @param gridScrollEnabled 中栏是否允许用户滚动（Picker 在弹层小键盘显示期间置 false）
 * @param backToKeyboardKeyDef 右栏底部额外的「返回主键盘」键；null = 不显示（展开候选页）
 */
@Composable
fun ComposeSplitCandidatesUi(
    tabs: List<SplitTab>,
    gridState: LazyGridState,
    returnDrawable: Int,
    keyActionListener: KeyActionListener?,
    popupActionListener: PopupActionListener?,
    onTabSelected: (Int) -> Unit,
    columns: (availableWidth: Dp) -> Int,
    modifier: Modifier = Modifier,
    gridScrollEnabled: Boolean = true,
    gridContentPadding: Dp = SplitGridContentPadding,
    backToKeyboardKeyDef: KeyDef? = null,
    gridContent: LazyGridScope.() -> Unit,
) {
    val composeDensity = LocalDensity.current
    val hasTabs = tabs.isNotEmpty()
    val activeTab = tabs.indexOfFirst { it.active }
    val canPageUp = rememberCanPageUp(gridState)
    val canPageDown = rememberCanPageDown(gridState)

    BoxWithConstraints(modifier.fillMaxSize()) {
        val totalWidthPx = with(composeDensity) { maxWidth.toPx() }
        val sideWidthPx = totalWidthPx * SideColumnWidthFraction
        val dividerPx = with(composeDensity) { SplitDividerThickness.toPx() }
        // 中栏可用宽度 = 总宽 − 两侧栏 − 分隔线（无标签时左栏与左分隔线不占位）
        val middleWidthPx = totalWidthPx - sideWidthPx - dividerPx -
                (if (hasTabs) sideWidthPx + dividerPx else 0f)
        val columnCount = columns(with(composeDensity) { middleWidthPx.toDp() }).coerceAtLeast(1)
        Row(Modifier.fillMaxSize()) {
            if (hasTabs) {
                SplitSidebar(
                    tabs = tabs,
                    activeIndex = activeTab,
                    onTabSelected = onTabSelected,
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(with(composeDensity) { sideWidthPx.toDp() }),
                )
                VerticalDivider(
                    thickness = SplitDividerThickness,
                    color = MiuixTheme.colorScheme.dividerLine,
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(columnCount),
                state = gridState,
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f),
                contentPadding = PaddingValues(gridContentPadding),
                userScrollEnabled = gridScrollEnabled,
                content = gridContent,
            )
            VerticalDivider(
                thickness = SplitDividerThickness,
                color = MiuixTheme.colorScheme.dividerLine,
            )
            SplitKeyboardColumn(
                returnDrawable = returnDrawable,
                pageUpEnabled = canPageUp,
                pageDownEnabled = canPageDown,
                backToKeyboardKeyDef = backToKeyboardKeyDef,
                keyActionListener = keyActionListener,
                popupActionListener = popupActionListener,
                modifier = Modifier
                    .fillMaxHeight()
                    .width(with(composeDensity) { sideWidthPx.toDp() }),
            )
        }
    }
}

/** 左栏标签：文本 + 是否激活（Picker 取分类 label；展开候选页取候选分组标签的选中态）。 */
class SplitTab(val text: String, val active: Boolean = false)

/** 上翻 / 下翻键的 `LayoutSwitchAction` 目标（两个窗口的路由约定一致）。 */
object SplitCandidatesKeyboard {
    const val PageUp = "U"
    const val PageDown = "D"
}

/** 中栏「到顶 / 到底」判定：翻页键置灰与 Picker 的「到底特判末分类」共用同一口径。 */
@Composable
fun rememberCanPageUp(gridState: LazyGridState): Boolean {
    val state = remember {
        derivedStateOf {
            gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0
        }
    }
    return state.value
}

@Composable
fun rememberCanPageDown(gridState: LazyGridState): Boolean {
    val state = remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last < info.totalItemsCount - 1
        }
    }
    return state.value
}

/**
 * 回车键：图标随当前编辑框类型变化（与 `KeyboardWindow` 同口径）；`drawable == 0`
 * （尚未就绪）时保留 `ReturnKey()` 的默认图标，避免 `painterResource(0)` 崩溃。
 */
internal fun returnKeyWithDrawable(drawable: Int): KeyDef {
    val base = ReturnKey()
    if (drawable == 0) return base
    val image = base.appearance as? KeyDef.Appearance.Image ?: return base
    return KeyDef(
        KeyDef.Appearance.Image(
            src = drawable,
            percentWidth = image.percentWidth,
            variant = image.variant,
            border = image.border,
            margin = image.margin,
            viewId = image.viewId,
            soundEffect = image.soundEffect,
        ),
        base.behaviors,
        base.popup,
    )
}

// ---------------------------------------------------------------------------
// 左栏：分类标签（固定侧边栏）
// ---------------------------------------------------------------------------

/**
 * 左栏竖排标签（固定侧边栏，不可折叠、非抽屉）：`ComposeKey` 文本键，默认 `Alternative`、
 * 选中 `Accent` 高亮；单键高度 = `max(可用高/标签数, 40dp)`，放不下时可纵向滚动（横屏保护），
 * 激活标签变化时自动滚入视野（居中）。
 */
@Composable
private fun SplitSidebar(
    tabs: List<SplitTab>,
    activeIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val composeDensity = LocalDensity.current
    BoxWithConstraints(modifier) {
        val itemHeight = if (maxHeight == Dp.Infinity) {
            MinTabKeyHeight
        } else {
            maxOf(maxHeight / tabs.size.coerceAtLeast(1), MinTabKeyHeight)
        }
        val itemHeightPx = with(composeDensity) { itemHeight.toPx() }
        // viewportSize 参与 key：首次组合时布局还没发生（值为 0），布局完成后需要补一次对齐
        val viewport = scrollState.viewportSize
        LaunchedEffect(activeIndex, itemHeightPx, viewport) {
            if (viewport <= 0 || activeIndex < 0) return@LaunchedEffect
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
            tabs.forEachIndexed { index, tab ->
                SplitTabKey(
                    tab = tab,
                    keyId = SplitTabKeyIdBase + index,
                    onClick = { onTabSelected(index) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight),
                )
            }
        }
    }
}

/**
 * 单颗标签键：键面与右栏键盘同款（`ComposeKey` 文本键）。
 *
 * `def` 按 `(text, active)` 记忆：`ComposeKey` 用 `def.behaviors` 作手势节点的 key，
 * 每次重组都新建 `KeyDef` 会把进行中的按压手势重启（等于补一次 ACTION_CANCEL）。
 */
@Composable
private fun SplitTabKey(
    tab: SplitTab,
    keyId: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val def = remember(tab.text, tab.active) {
        KeyDef(
            KeyDef.Appearance.Text(
                displayText = tab.text,
                textSize = TabTextSize,
                variant = if (tab.active) {
                    KeyDef.Appearance.Variant.Accent
                } else {
                    KeyDef.Appearance.Variant.Alternative
                },
                percentWidth = 1f,
            ),
            setOf(KeyDef.Behavior.Press(KeyAction.LayoutSwitchAction(act = "SPLIT_TAB"))),
        )
    }
    ComposeKey(
        def = def,
        keyId = keyId,
        // 标签点击直接回调下标，与网格/右栏的按键监听互不干扰
        keyActionListener = KeyActionListener { _, _ -> onClick() },
        modifier = modifier,
        autoScale = true,
    )
}

@Composable
private fun SplitKeyboardColumn(
    returnDrawable: Int,
    pageUpEnabled: Boolean,
    pageDownEnabled: Boolean,
    backToKeyboardKeyDef: KeyDef?,
    keyActionListener: KeyActionListener?,
    popupActionListener: PopupActionListener?,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val prefs = remember { AppPrefs.getInstance().keyboard }
    val hapticOnRepeat = prefs.hapticOnRepeat.preferenceState()
    val spaceSwipeMoveCursor = prefs.spaceSwipeMoveCursor.preferenceState()

    val backspaceKey = remember { BackspaceKey() }
    val returnKey = remember(returnDrawable) { returnKeyWithDrawable(returnDrawable) }
    val pageUpKey = remember {
        ImageLayoutSwitchKey(
            R.drawable.ic_baseline_arrow_upward_24,
            to = SplitCandidatesKeyboard.PageUp,
            variant = KeyDef.Appearance.Variant.Alternative,
        )
    }
    val pageDownKey = remember {
        ImageLayoutSwitchKey(
            R.drawable.ic_baseline_arrow_downward_24,
            to = SplitCandidatesKeyboard.PageDown,
            variant = KeyDef.Appearance.Variant.Alternative,
        )
    }
    // 删除键的滑行（移动光标 / 删除选区）与主键盘一致；监听器经 rememberUpdatedState 取最新值
    val listenerState = rememberUpdatedState(keyActionListener)
    val backspaceSwipeSpec = remember(backspaceKey, spaceSwipeMoveCursor) {
        backspaceKey.spaceAndBackspaceSwipeSpec(spaceSwipeMoveCursor)
    }
    val backspaceGesture = remember(backspaceKey, hapticOnRepeat) {
        backspaceKey.spaceAndBackspaceGestureListener(
            view = view,
            onAction = { action ->
                listenerState.value?.onKeyAction(action, KeyActionListener.Source.Keyboard)
            },
            hapticOnRepeat = hapticOnRepeat,
        )
    }
    Column(modifier) {
        ComposeKey(
            def = backspaceKey,
            keyId = SplitKeyboardKeyIdBase,
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            keyActionListener = keyActionListener,
            popupActionListener = popupActionListener,
            swipeSpec = backspaceSwipeSpec,
            onSwipeGesture = backspaceGesture,
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
            def = returnKey,
            keyId = SplitKeyboardKeyIdBase + 3,
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            keyActionListener = keyActionListener,
            popupActionListener = popupActionListener,
        )
        if (backToKeyboardKeyDef != null) {
            ComposeKey(
                def = backToKeyboardKeyDef,
                keyId = SplitKeyboardKeyIdBase + 4,
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                keyActionListener = keyActionListener,
                popupActionListener = popupActionListener,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 常量
// ---------------------------------------------------------------------------

/** 左栏与右栏各占总宽的比例。 */
private const val SideColumnWidthFraction = 0.15f

/** 中栏与两侧栏之间的分隔线粗细。 */
private val SplitDividerThickness = 1.dp

/** 中栏网格的四周内边距（两个窗口同口径）。 */
private val SplitGridContentPadding = 4.dp

/** 左栏标签字号。 */
private const val TabTextSize = 14f

/** 左栏标签的最小高度（横屏标签多时可滚动，保证仍可点按）。 */
private val MinTabKeyHeight = 40.dp

/** 左栏标签 keyId 段（`PopupComponent` 按 Int id 索引，需与中栏/右栏段错开）。 */
private const val SplitTabKeyIdBase = 0x8000

/** 右栏键盘 keyId 段。 */
private const val SplitKeyboardKeyIdBase = 0x9000
