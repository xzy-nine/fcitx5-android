/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import android.graphics.Typeface
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.input.keyboard.ComposeKey
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Border
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Variant
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import kotlin.math.min

/**
 * Picker 的单页符号网格（`PickerPageUi` 的 Compose 版，批次 D-1）。
 *
 * 与 View 版逐项对应：
 *
 * | View | 这里 |
 * |---|---|
 * | `density.columnCount` 列 × `rowCount` 行，行高均分 | `Column` + 每行 `weight(1f)`，行内 `width = 行宽/列数` |
 * | 末行 `CHAIN_PACKED`、`backspace` 占 15% 贴右下 | 末行 = `weight(0.85f)` 的居中格子区 + `weight(0.15f)` 的退格 |
 * | 空位 `isEnabled = false`、文字置空 | `ComposeKey(enabled = false)` + 空文本（半透明、无动作） |
 * | 单元格 = `TextKeyView`，`border = if (bordered) On else Off` | 复用 [ComposeKey]，外观/反馈一致 |
 * | 长按 → `policy.popup(item)` 弹层；按下 → `PreviewAction(raw)`；滑行 → `ChangeFocus`/`Trigger` | `def.popup = [Preview(raw), keyboard]`，**由 [ComposeKey] 原生活势逻辑覆盖** |
 * | 点按 → `CommitAction(policy.transform(item))` | `Behavior.Press(CommitAction(transform(item)))`，键面也显示 transform 后的值 |
 * | `density.autoScale` → `AutoScaleTextView.Mode.Proportional` | `ComposeKey(autoScale = true)` |
 * | 退格：`repeatEnabled` + 点击发 BackSpace | `Behavior.Press + Behavior.Repeat` |
 *
 * `policy == null`（第 0 页「最近使用」）时不挂任何弹层，对应 adapter 里把 `popupActionListener`
 * 置 null 的那条路径。
 *
 * @param keyIdBase 页内键 id 基址（同一 Picker 的多页要错开，见 `PopupComponent` 按 Int id 索引）
 */
@Composable
fun ComposePickerPage(
    items: List<String>,
    density: PickerPageUi.Density,
    bordered: Boolean,
    keyActionListener: KeyActionListener?,
    popupActionListener: PopupActionListener?,
    modifier: Modifier = Modifier,
    policy: PickerPolicy? = null,
    keyIdBase: Int = 0,
) {
    val columnCount = density.columnCount
    val border = if (bordered) Border.On else Border.Off
    // View: setItems 只对「有 policy」的页挂弹层；最近使用页无弹层
    val withPopups = policy != null && popupActionListener != null

    Column(modifier = modifier.fillMaxSize()) {
        for (row in 0 until density.rowCount) {
            val isLastRow = row == density.rowCount - 1
            val firstIndex = row * columnCount
            val lastIndex = min(firstIndex + columnCount, density.pageSize)
            if (firstIndex >= lastIndex) continue

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                // View: keyWidth = 1f / columnCount，相对父（整行）宽度
                val cellWidth = maxWidth / columnCount
                if (isLastRow && density.showBackspace) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .weight(1f - BackspaceWidthFraction)
                                .fillMaxHeight(),
                            // View: 末行首键 CHAIN_PACKED，整行向退格靠拢
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PickerCells(
                                firstIndex = firstIndex,
                                lastIndex = lastIndex,
                                items = items,
                                density = density,
                                border = border,
                                policy = policy,
                                withPopups = withPopups,
                                keyIdBase = keyIdBase,
                                cellWidth = cellWidth,
                                keyActionListener = keyActionListener,
                                popupActionListener = popupActionListener,
                            )
                        }
                        ComposeKey(
                            def = remember(border) { backspaceCellDef(border) },
                            keyId = keyIdBase + density.pageSize,
                            modifier = Modifier
                                .weight(BackspaceWidthFraction)
                                .fillMaxHeight(),
                            keyActionListener = keyActionListener,
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PickerCells(
                            firstIndex = firstIndex,
                            lastIndex = lastIndex,
                            items = items,
                            density = density,
                            border = border,
                            policy = policy,
                            withPopups = withPopups,
                            keyIdBase = keyIdBase,
                            cellWidth = cellWidth,
                            keyActionListener = keyActionListener,
                            popupActionListener = popupActionListener,
                        )
                    }
                }
            }
        }
    }
}

/** 一行内的单元格（不足 `items` 的格子置灰为空，对应 View 的 `isEnabled = false`）。 */
@Composable
private fun PickerCells(
    firstIndex: Int,
    lastIndex: Int,
    items: List<String>,
    density: PickerPageUi.Density,
    border: Border,
    policy: PickerPolicy?,
    withPopups: Boolean,
    keyIdBase: Int,
    cellWidth: androidx.compose.ui.unit.Dp,
    keyActionListener: KeyActionListener?,
    popupActionListener: PopupActionListener?,
) {
    for (index in firstIndex until lastIndex) {
        val raw = items.getOrNull(index)
        val display = raw?.let { policy?.transform(it) ?: it }
        val def = remember(raw, display, border, withPopups) {
            cellDef(
                display = display,
                raw = raw,
                density = density,
                border = border,
                popupKeyboard = if (withPopups) raw?.let { policy?.popup(it) } else null,
            )
        }
        ComposeKey(
            def = def,
            keyId = keyIdBase + index,
            modifier = Modifier
                .width(cellWidth)
                .fillMaxHeight(),
            keyActionListener = keyActionListener,
            // 最近使用页（policy == null）不挂弹层，与 View 的 adapter 一致
            popupActionListener = if (withPopups) popupActionListener else null,
            enabled = raw != null,
            autoScale = density.autoScale,
        )
    }
}

// ---------------------------------------------------------------------------
// 单元格外观
// ---------------------------------------------------------------------------

/** 普通格子；`raw == null` 时为空位（无动作、半透明）。 */
private fun cellDef(
    display: String?,
    raw: String?,
    density: PickerPageUi.Density,
    border: Border,
    popupKeyboard: KeyDef.Popup.Keyboard?,
): KeyDef {
    val appearance = KeyDef.Appearance.Text(
        displayText = display.orEmpty(),
        textSize = density.textSize,
        textStyle = Typeface.NORMAL,
        variant = Variant.Normal,
        border = border,
    )
    if (raw == null) return KeyDef(appearance, emptySet(), null)
    val popup: Array<KeyDef.Popup> = if (popupKeyboard == null) {
        // 无长按弹层时仍保留按下预览（View: Preview 内容用**原始** item）
        arrayOf<KeyDef.Popup>(KeyDef.Popup.Preview(raw))
    } else {
        // 顺序与 View 的 popup 数组一致：Preview 在内、Keyboard 在外（后者先触发）
        arrayOf<KeyDef.Popup>(KeyDef.Popup.Preview(raw), popupKeyboard)
    }
    return KeyDef(
        appearance,
        setOf(KeyDef.Behavior.Press(KeyAction.CommitAction(display.orEmpty()))),
        popup,
    )
}

/** 退格格（View: `ImageKeyView` + `repeatEnabled`）。 */
private fun backspaceCellDef(border: Border): KeyDef = KeyDef(
    KeyDef.Appearance.Image(
        src = R.drawable.ic_baseline_backspace_24,
        variant = Variant.Alternative,
        border = border,
        viewId = R.id.button_backspace,
    ),
    setOf(
        KeyDef.Behavior.Press(backspaceAction),
        KeyDef.Behavior.Repeat(backspaceAction),
    ),
    null,
)

private val backspaceAction =
    KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_BackSpace))

/** 末行退格占整行宽度的比例（View: `matchConstraintPercentWidth = 0.15f`）。 */
private const val BackspaceWidthFraction = 0.15f
