# Agent Note: 数字键盘历史符号与符号滑块 Compose 化

Status: implemented

## Problem

横屏分体数字键盘需要历史符号面板与符号滑块，View 实现是 `RecentSymbolsView`，且只被断线的 `NumberKeyboard.buildSplitLayout` 引用。

## Decision

历史符号面板迁到 `ComposeRecentSymbols`，符号滑块迁到同文件的 `ComposeSymbolSlider`；行数据沿用 `NumberKeyboardRows.kt` 的单一实例 val。

布局为左 45% 历史符号网格、右 45% 符号滑块或 9 宫格、中间 10% 分隔。上屏经 `ComposeKeyColumn` 接线。

断线保留：`keyboard/RecentSymbolsView.kt`。它是 fork 私有文件，仅被断线的 `NumberKeyboard.buildSplitLayout` 引用，可随时删除。

## Alternatives considered

**保留 `RecentSymbolsView` 并用 AndroidView 包装**。历史符号是高频交互，多一层互操作会引入测量开销。

**把符号滑块拆成独立文件**。滑块与面板共享拖动状态，同文件更易维护。

## Consequences

- 历史符号的存取路径不变，只换渲染层。
- `RecentSymbolsView.kt` 可作为清理候选，删除前确认 `buildSplitLayout` 已无调用点。
