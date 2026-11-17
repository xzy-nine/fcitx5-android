# 界面

IME 与设置界面的实现形态与设计约定。

## 索引

| 页面 | 回答什么 |
|---|---|
| [界面架构.md](界面架构.md) | 宿主形态、已 Compose 化的组件、仍为 View 的组件与成因 |
| [界面设计约定.md](界面设计约定.md) | 配色、布局与间距、交互边界、视觉验收 |

## 边界

配色分流：Compose 迁移件读 `MiuixTheme.colorScheme`，主键盘键面经 `keyboard/KeyboardVisuals.kt` 读 fcitx `Theme`。约束与例外见 [已知坑/Compose与miuix.md](../已知坑/Compose与miuix.md))。

View 到 Compose 的迁移决策与逐批次接线见 [.agents/notes/implemented/architecture/](../../.agents/notes/implemented/architecture/)。
