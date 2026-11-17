# Agent Note: 主键盘、Picker 与数字键盘 Compose 化

Status: implemented

## Problem

主键盘 `KeyboardWindow`、Picker 窗口与数字键盘各自维护 View 布局。数字键盘横屏分体还涉及历史符号与符号滑块，View 版是 `NumberKeyboard` 与 `RecentSymbolsView`。

## Decision

主键盘由 `ComposeTextKeyboard` 承担，行数据从 `TextKeyboard` 复用；Picker 迁到 `ComposePickerPage` 与 `ComposePickerSplitUi`；数字键盘迁到 `ComposeNumberKeyboard`，历史符号在 `ComposeRecentSymbols`，符号滑块是同文件的 `ComposeSymbolSlider`。

接线链路：

- 布局名常量收敛到 `KeyboardLayoutNames`；`TextKeyboard.Name` 与 `NumberKeyboard.Name` 委托给它。
- 数字键盘行数据 `NumberKeyboardRows.kt` 为单一实例 val，View 与 Compose 共用。
- 分体布局为左 45% 历史符号、右 45% 符号滑块或 9 宫格，中间 10% 分隔。
- Picker 中栏用 `LazyVerticalGrid` 铺 `PickerGridLayout` 槽位，密度由 `PickerDensity.columnCount` 封顶。
- 数字键盘上屏走 `input/keyboard/ComposeKeyColumn.kt`。

断线保留：`keyboard/BaseKeyboard.kt`（仍被 `TextKeyboard` 与 `NumberRow` 继承）、`keyboard/NumberKeyboard.kt`、`keyboard/KeyView.kt`、`keyboard/KeyViewExt.kt`、`SymbolSliderKeyView.kt`、`keyboard/RecentSymbolsView.kt`、`picker/PickerPageUi.kt`、`PickerPagesAdapter.kt`、`PickerLayout.kt`。

半活跃：`keyboard/TextKeyboard.kt` 仍被设置页 `KeyboardPreviewUi` 实例化，`Layout` 被 `ComposeTextKeyboard` 经数据文件复用；`keyboard/CustomGestureView.kt` 仍被 View 桥接层 `PickerTabsUi` 与 `ToolButton` 依赖，不能删。

## Alternatives considered

**主键盘整体重写不复用行数据**。会让 View 预览与 Compose 键盘的键位产生分叉。

**Picker 与数字键盘分开迁移**。两者共享 `PickerDensity` 与 `PickerGridLayout` 的密度计算，分开会重复实现。

## Consequences

- 键位定义只改 `KeyboardLayoutNames` 与 `NumberKeyboardRows`，两套渲染同步生效。
- `TextKeyboard` 与 `CustomGestureView` 有活跃依赖，不能当作断线文件清理。
- Picker 密度变化要同时验证展开候选与 Picker 两侧。
