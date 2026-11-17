# Agent Note: 弹窗层与状态区 Compose 化

Status: implemented

## Problem

弹窗层由 `PopupEntryUi`、`PopupKeyboardUi`、`PopupMenuUi`、`PopupContainerUi` 四个 View 组成，状态区由 `StatusAreaAdapter` 与 `StatusAreaEntryUi` 承担。弹窗层还涉及窗口的 z 序与点击穿透。

## Decision

弹窗层迁到 `ComposePopupLayer`，布局计算抽到 `PopupLayoutMath.kt` 以避免改动上游文件。状态区迁到 `ComposeStatusArea` 与 `StatusAreaWindow`，更多页由 `MorePageModel` 提供纯逻辑。

接线链路：

- 弹窗状态走 `MutableStateFlow<PopupLayerState>`，由根 `Scaffold` 的 `popupHost` slot 渲染；`LocalRootPopupStates` 只承载注册，不负责绘制。
- 布局计算独立在 `PopupLayoutMath.kt`，保证纯函数可测。
- 更多页把池子放在上、固定项放在下，两段各自独立滚动。
- `StatusAreaWindow` 已移除主题、输入法选项、虚拟键盘三个跳转项，改为网格格子，不再有 `onCreateBarExtension`。

断线保留：`popup/PopupEntryUi.kt`、`PopupKeyboardUi.kt`、`PopupMenuUi.kt`、`PopupContainerUi.kt`、`status/StatusAreaAdapter.kt`、`StatusAreaEntryUi.kt`。

## Alternatives considered

**在 View 树里直接叠 ComposeView 做弹窗**。会绕过根 `Scaffold` 的 z 序，弹层可能被键盘遮挡。

**保留 View 弹窗、只迁移状态区**。弹窗与状态区共享同一套窗口挂载时序，分开迁移会让两者的层级判断分叉。

## Consequences

- 使用 `Overlay*` 的窗口必须在 Compose 根包一层 `Scaffold`，否则弹层无处渲染。
- `PopupLayoutMath.kt` 是纯函数，新增弹窗定位逻辑优先放这里而不是内联到组合。
- 状态区不再有 `onCreateBarExtension`，依赖它的旧调用点需要改为网格格子。
