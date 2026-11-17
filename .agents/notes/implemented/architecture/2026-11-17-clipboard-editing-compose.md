# Agent Note: 剪贴板与文本编辑 Compose 化

Status: implemented

## Problem

剪贴板由 `ClipboardUi`、`ClipboardEntryUi`、`ClipboardInstructionUi` 与 `ClipboardAdapter` 组成，文本编辑由 `TextEditingUi` 与 `TextEditingButton` 组成。剪贴板条目有长按操作菜单，文本编辑需要与编辑器光标联动。

## Decision

剪贴板迁到 `ClipboardWindow` + `ComposeClipboard` + `ClipboardEditWindow`，文本编辑迁到 `TextEditingWindow`。

接线链路：

- 候选操作菜单与剪贴板条目共用 `ComposeCandidateActionMenu` 的交互形态。
- 剪贴板摘要生成委托到 `ClipboardTextUtils.excerptText`，`ClipboardAdapter` 不再自行实现。
- 剪贴板搜索走 `ClipboardSearchDeviceTest` 覆盖的设备路径。
- 长按条目依赖 `NavigationEventDispatcherOwner`，缺失会崩进程。

断线保留：`clipboard/ClipboardUi.kt`、`ClipboardEntryUi.kt`、`ClipboardInstructionUi.kt`、`SpacesItemDecoration.kt`、`clipboard/ClipboardAdapter.kt`、`editing/TextEditingUi.kt`、`TextEditingButton.kt`。

## Alternatives considered

**剪贴板继续用 RecyclerView 适配器**。条目需要长按菜单与搜索高亮，View 侧要维护两套选中状态。

**文本编辑单独保留 View**。它与剪贴板共享窗口挂载与 insets，分开会重复实现挂载逻辑。

## Consequences

- 剪贴板条目长按必须有 owner 链支撑，否则崩在 composition 阶段。
- 摘要逻辑集中在 `ClipboardTextUtils`，修改摘要格式只改这一处。
- 文本编辑窗口依赖剪贴板的窗口挂载时序，改动时成对验证。
