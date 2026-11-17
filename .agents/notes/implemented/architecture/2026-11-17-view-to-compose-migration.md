# Agent Note: View 到 Compose 的界面迁移

Status: implemented

## Problem

输入法界面原本全部是 View 实现：`InputView` 是 `ConstraintLayout`，工具栏由 `KawaiiBarComponent` 承担，候选栏、预编辑、Picker、状态区、弹窗、剪贴板、文本编辑各自维护一套 View 适配器。上游 fcitx5-android 持续演进这些文件，custom 分支每合并一次上游都要重新解决冲突。

需要在保留上游合并能力的前提下把界面迁到 Compose，并决定哪些 View 必须保留。

## Decision

按窗口单元逐个迁移，迁移后由 Compose 承担渲染，View 仅作为宿主或被包装的 island。各批次的接线链路与断线去向见下列记录。

| 批次 | 记录 |
|---|---|
| 横向候选栏 | [候选栏](2026-11-17-candidate-bar-compose.md) |
| 工具栏与预编辑栏 | [工具栏与预编辑栏](2026-11-17-toolbar-preedit-compose.md) |
| 弹窗层与状态区 | [弹窗与状态区](2026-11-17-popup-status-compose.md) |
| 剪贴板与文本编辑 | [剪贴板与文本编辑](2026-11-17-clipboard-editing-compose.md) |
| 主键盘与 Picker | [主键盘与 Picker](2026-11-17-keyboard-picker-compose.md) |
| 数字键盘历史符号 | [历史符号与符号滑块](2026-11-17-number-keyboard-symbols-compose.md) |
| EditorInfo 窗口 | [EditorInfo](2026-11-17-editorinfo-compose.md) |
| 基础设施与收尾批次 | [基础设施与收尾](2026-11-17-ui-foundation-and-batch.md) |

共用的形态规则：宿主是 `FrameLayout`，`InputView` 直接作子 View，覆盖层 `ComposeView` 压其上；不用 `AndroidView` 承载整棵键盘树，因为互操作会给每帧多测一遍布局。

配色分流：Compose 迁移件读 `MiuixTheme.colorScheme`，主键盘键面经 `keyboard/KeyboardVisuals.kt` 读 fcitx `Theme`。两套色源并存是刻意取舍，现状与验收入口见 [界面架构.md](../../../../Docs/界面/界面架构.md)。

## Alternatives considered

**一次性全量重写界面**。会与上游文件全面冲突，失去逐个单元回归的能力，迁移期间无法定位是哪个窗口引入的问题。

**继续在 View 上加功能**。每个新功能都要改上游高频文件，合并成本线性增长。

**用 `AndroidView` 统一承载 Compose**。省去宿主改造，但整棵键盘树每帧多测一遍布局，IME 场景下代价不可接受。

**把 fcitx `Theme` 全量替换为 `MiuixTheme`**。主键盘键面依赖 fcitx 主题的键盘取色，替换会破坏既有主题设置。

## Consequences

- 新增 IME 界面默认用 Compose；只有系统 API 强制或需要原生绘制的场合才保留 View。
- 迁移完成后的旧 View 文件按 fork 约定不删除上游文件，但已断开接线；custom 私有文件可择机清理。
- 两套色源并存带来色调不匹配风险，验收需切换浅色、深色、自定义主题（含背景图）逐项检查。
- 每新增一个承载 Compose 的窗口，都要确认 owner 链与根 `Scaffold` 的 `popupHost` 已就位。
