/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.RecentlyUsed
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Variant
import org.fcitx.fcitx5.android.input.picker.PickerPageUi
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.PopupActionListener

/**
 * 数字键盘的状态层（`Docs/KeyboardComposePlan.md` 批次 C2）。
 *
 * 由窗口持有（跨重组、跨布局切换存活），对应 View 侧 `NumberKeyboard` 的
 * `sliderSymbols` 字段与 [RecentlyUsed] 数据源。
 *
 * 与 View 侧的差异：View 的 `recentlyUsed` 是个每次访问都新建的 getter，这里持有一个实例；
 * `RecentlyUsed` 只是持久化存储的薄封装，语义相同。
 */
class NumberKeyboardState {

    /** 竖向符号滑块的符号集（可编辑，通过编辑弹窗返回）。 */
    var sliderSymbols: List<String> by mutableStateOf(SymbolSliderKey.DefaultSymbols.toList())
        private set

    /** 最近使用的符号（分体布局左侧面板的数据源）。 */
    var recentSymbols: List<String> by mutableStateOf(emptyList())
        private set

    private val recentlyUsed: RecentlyUsed by lazy {
        RecentlyUsed(PickerWindow.Key.Symbol.name, PickerPageUi.Density.High.pageSize)
    }

    /** 外显的滑块子按钮数量（`symbols.symbolSliderVisibleCount`，View 侧每次重建都即时读取）。 */
    val symbolSliderVisibleCount: Int
        get() = AppPrefs.getInstance().symbols.symbolSliderVisibleCount.getValue()

    /** 从持久化存储刷新最近符号。 */
    fun refreshRecent() {
        recentSymbols = recentlyUsed.items
    }

    /** 历史符号被点按：记入最近使用并刷新面板（View: `RecentSymbolsView.onSymbolInput`）。 */
    fun recordRecentSymbol(symbol: String) {
        recentlyUsed.insert(symbol)
        refreshRecent()
    }

    /**
     * 应用符号编辑弹窗返回的字符串。
     *
     * 按 Unicode 码点拆分，确保补充字符（emoji 等）不被拆碎 —— 与 View 侧
     * `NumberKeyboard.applySymbols` 一致。
     */
    fun applySymbols(newSymbols: String) {
        sliderSymbols = newSymbols.codePoints()
            .mapToObj { Character.toChars(it) }
            .map { String(it) }
            .toList()
    }
}

/**
 * Compose 版数字键盘（批次 C2）。
 *
 * 布局与 `NumberKeyboard.buildNormalLayout` / `buildSplitLayout` 一一对应：
 *
 * - 普通：左 15% 宽 × 75% 高的符号滑块，右侧 85% × 75% 放 1~3 行，底部 25% 通栏放第 4 行；
 * - 分体：左 45% 历史符号面板 + 10% 空白 + （15%×45% 滑块 + 85%×45% 的 1~3 行），
 *   第 4 行只占右侧 45%。
 *
 * 用 `weight` 表达这些比例：外层两段 3:1 即 75% / 25%；同层 `weight` 之和为 1 时
 * 等价于 `matchConstraintPercentWidth`。
 *
 * **历史符号面板现阶段用 `AndroidView` 包 View 版 [RecentSymbolsView]**（见 §0 的 C5 待办）：
 * 它是 fork 私有控件（249 行，含 RecyclerView + 网格布局），只在横屏分体的数字键盘出现；
 * 纯 Compose 重做留到 C5，避免在本批把风险面摊大。
 */
@Composable
fun ComposeNumberKeyboard(
    state: NumberKeyboardState,
    keyActionListener: KeyActionListener?,
    popupActionListener: PopupActionListener?,
    onSymbolSliderEdit: () -> Unit,
    modifier: Modifier = Modifier,
    split: Boolean = false,
) {
    val prefs = remember { AppPrefs.getInstance().keyboard }
    val hapticOnRepeat = prefs.hapticOnRepeat.preferenceState()
    val spaceSwipeMoveCursor = prefs.spaceSwipeMoveCursor.preferenceState()
    val view = androidx.compose.ui.platform.LocalView.current

    // 空格（MiniSpace 不算）/ 退格的键型滑行，与文本键盘同源
    val keySlot: ComposeKeySlot = { keyId, def, insets, keyModifier ->
        ComposeKey(
            def = def,
            keyId = keyId,
            modifier = keyModifier,
            insets = insets,
            keyActionListener = keyActionListener,
            popupActionListener = popupActionListener,
            swipeSpec = def.spaceAndBackspaceSwipeSpec(spaceSwipeMoveCursor),
            onSwipeGesture = remember(def, hapticOnRepeat) {
                def.spaceAndBackspaceGestureListener(
                    view = view,
                    onAction = { action ->
                        keyActionListener?.onKeyAction(action, KeyActionListener.Source.Keyboard)
                    },
                    hapticOnRepeat = hapticOnRepeat,
                )
            },
        )
    }

    val onSymbolInput: (String) -> Unit = { symbol ->
        keyActionListener?.onKeyAction(
            KeyAction.FcitxKeyAction(symbol), KeyActionListener.Source.Keyboard
        )
    }
    val onRecentSymbolInput: (String) -> Unit = { symbol ->
        onSymbolInput(symbol)
        state.recordRecentSymbol(symbol)
    }
    val onLayoutSwitch: () -> Unit = {
        keyActionListener?.onKeyAction(
            KeyAction.LayoutSwitchAction(PickerWindow.Key.Symbol.name),
            KeyActionListener.Source.Keyboard,
        )
    }

    LaunchedEffect(Unit) { state.refreshRecent() }

    if (split) {
        // 与 View 的 buildSplitLayout 一一对应：
        //   左 45% 历史符号面板（**通栏**）+ 10% 空白 + 右 45%（上 75%：6.75% 滑块 + 38.25% 三行；
        //   下 25%：第 4 行通占右侧 45%）。
        Row(modifier = modifier.fillMaxSize()) {
            ComposeRecentSymbolsPanel(
                symbols = state.recentSymbols,
                onSymbolInput = onRecentSymbolInput,
                onLayoutSwitch = onLayoutSwitch,
                modifier = Modifier.weight(0.45f).fillMaxHeight(),
            )
            Spacer(Modifier.weight(0.10f))
            Column(Modifier.weight(0.45f).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth().weight(3f)) {
                    ComposeSymbolSlider(
                        symbols = state.sliderSymbols,
                        visibleCount = state.symbolSliderVisibleCount,
                        onSymbolInput = onSymbolInput,
                        onEditClick = onSymbolSliderEdit,
                        modifier = Modifier.weight(0.15f).fillMaxHeight(),
                    )
                    Column(Modifier.weight(0.85f).fillMaxHeight()) {
                        ComposeKeyRow(NumberKeyboardRows.row1(), Modifier.fillMaxWidth().weight(1f), keyIdBase = 0, key = keySlot)
                        ComposeKeyRow(NumberKeyboardRows.row2(), Modifier.fillMaxWidth().weight(1f), keyIdBase = 100, key = keySlot)
                        ComposeKeyRow(NumberKeyboardRows.row3(), Modifier.fillMaxWidth().weight(1f), keyIdBase = 200, key = keySlot)
                    }
                }
                ComposeKeyRow(
                    row = NumberKeyboardRows.row4Split(),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    keyIdBase = 300,
                    key = keySlot,
                )
            }
        }
    } else {
        Column(modifier = modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().weight(3f)) {
                ComposeSymbolSlider(
                    symbols = state.sliderSymbols,
                    visibleCount = state.symbolSliderVisibleCount,
                    onSymbolInput = onSymbolInput,
                    onEditClick = onSymbolSliderEdit,
                    modifier = Modifier.weight(0.15f).fillMaxHeight(),
                )
                Column(Modifier.weight(0.85f).fillMaxHeight()) {
                    ComposeKeyRow(NumberKeyboardRows.row1(), Modifier.fillMaxWidth().weight(1f), keyIdBase = 0, key = keySlot)
                    ComposeKeyRow(NumberKeyboardRows.row2(), Modifier.fillMaxWidth().weight(1f), keyIdBase = 100, key = keySlot)
                    ComposeKeyRow(NumberKeyboardRows.row3(), Modifier.fillMaxWidth().weight(1f), keyIdBase = 200, key = keySlot)
                }
            }
            ComposeKeyRow(
                row = NumberKeyboardRows.row4(),
                modifier = Modifier.fillMaxWidth().weight(1f),
                keyIdBase = 300,
                key = keySlot,
            )
        }
    }
}

/**
 * 竖向符号滑块（`SymbolSliderKeyView` 的 Compose 版）。
 *
 * 每个符号一个子按钮 + 末尾一个编辑按钮，子按钮高度 = 滑块高度 / 外显段数 × 0.9
 * （露出下一个按钮的一小部分提示可滑动），整列可滚动。子按钮复用 [ComposeKey]，
 * 因此底色/圆角/按压高亮/震动与音效与键盘其他按键完全一致。
 */
@Composable
fun ComposeSymbolSlider(
    symbols: List<String>,
    visibleCount: Int,
    onSymbolInput: (String) -> Unit,
    onEditClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visuals = rememberKeyboardVisuals()
    // View: 滑块区背景用 IME 主背景色（只有子按钮保留按键底色）
    Box(modifier = modifier.background(visuals.backgroundColor)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val cellHeight = (maxHeight / visibleCount.coerceAtLeast(1)) * 0.9f
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                symbols.forEachIndexed { index, symbol ->
                    ComposeKey(
                        def = remember(symbol) { symbolCellDef(symbol) },
                        keyId = index,
                        modifier = Modifier.fillMaxWidth().height(cellHeight),
                        keyActionListener = remember(symbol) {
                            KeyActionListener { _, _ -> onSymbolInput(symbol) }
                        },
                    )
                }
                ComposeKey(
                    def = remember { editCellDef() },
                    keyId = symbols.size,
                    modifier = Modifier.fillMaxWidth().height(cellHeight),
                    keyActionListener = remember { KeyActionListener { _, _ -> onEditClick() } },
                )
            }
        }
    }
}

/**
 * 横屏分体的历史符号面板：纯 Compose 版见 [ComposeRecentSymbolsPanel]（C5）。
 */

// ---------------------------------------------------------------------------
// 子按钮外观（对应 SymbolSliderKeyView.makeSymbolButton / makeEditButton）
// ---------------------------------------------------------------------------

private fun symbolCellDef(symbol: String) = KeyDef(
    KeyDef.Appearance.Text(
        displayText = symbol,
        textSize = 26f,
        textStyle = Typeface.NORMAL,
        variant = Variant.Alternative,
    ),
    // 动作只用于占位：点击由 ComposeKey 的 keyActionListener 直接回调 onSymbolInput
    setOf(KeyDef.Behavior.Press(KeyAction.CommitAction(symbol))),
    null,
)

private fun editCellDef() = KeyDef(
    KeyDef.Appearance.Image(
        src = R.drawable.ic_baseline_edit_24,
        variant = Variant.Alternative,
    ),
    setOf(KeyDef.Behavior.Press(KeyAction.CommitAction(""))),
    null,
)
