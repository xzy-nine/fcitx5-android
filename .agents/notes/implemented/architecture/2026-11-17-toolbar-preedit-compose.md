# Agent Note: 工具栏与预编辑栏 Compose 化

Status: implemented

## Problem

工具栏由 `KawaiiBarComponent` 与多个 View 子页组成，预编辑由 `PreeditComponent` 承担。两者都在上游高频改动，且工具栏高度参与键盘尺寸计算，任何一侧的时序偏差都会让键盘跳动。

## Decision

工具栏迁到 `ComposeKawaiiBarComponent`，预编辑迁到 `ComposePreeditComponent`，宿主改为 `ComposeView` 覆盖层。

接线链路：

- 工具栏状态机 `KawaiiBarStateMachine` 驱动 `MutableStateFlow<State>`，子状态由 `evalIdleUiState()` 派生 `MutableStateFlow<IdleSubState>`；标题走 `MutableStateFlow<TitleData?>`。
- 工具栏内容由 `ToolbarContent()` 承载，通过 `ToolbarCallbacks` 与 `CommonKeyActionListener` 回调按键。
- 预编辑尺寸变化经 `onSizeChanged` 上报，`ComposePreeditComponent.heightPx` 变化后 `InputBroadcaster.onInputPanelUpdate` → `ComposePreeditComponent.onInputPanelUpdate` → `MutableStateFlow<PreeditState>`。
- 工具栏高度变化经 `notifyToolbarHeightChanged()` 递增 `_toolbarHeightVersion`，组合侧用 `key(toolbarHeightVersion)` 触发重建，再 `updateKeyboardSize()`。
- 窗口挂载由 `onWindowAttached` 与 `InputWindowManager` 协同；`UniqueViewComponent` 保证同一组件实例只挂一次。

断线保留：`bar/KawaiiBarComponent.kt`、`bar/ui/IdleUi.kt`、`CandidateUi.kt`、`TitleUi.kt`、`idle/ButtonsBarUi.kt`、`idle/ClipboardSuggestionUi.kt`、`bar/ui/idle/NumberRow.kt`（`Layout` 已抽离到 `NumberRowLayout.kt`，本类只保留别名）、`preedit/PreeditComponent.kt`。

`preedit/PreeditUi.kt` 仍活跃，被范围外的 `CandidatesView` 使用。

## Alternatives considered

**只迁移工具栏、保留 View 预编辑**。预编辑高度是键盘尺寸的输入项，两套实现交替上报会让键盘在切换时抖动。

**让工具栏高度直接读取 `AppPrefs` 并触发重组**。偏好是非响应式的，读取不会引起重组；必须经版本号显式重建。

## Consequences

- 工具栏高度变化必须经 `_toolbarHeightVersion`，否则键盘尺寸不更新。
- 预编辑的高度上报顺序影响 insets，改动时要同时验证键盘顶起与工具栏位置。
- `PreeditUi.kt` 属于 `CandidatesView` 的范围，迁移工具栏时不要顺手删除。
