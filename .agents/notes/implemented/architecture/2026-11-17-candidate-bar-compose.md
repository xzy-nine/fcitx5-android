# Agent Note: 横向候选栏 Compose 化

Status: implemented

## Problem

候选栏原由 `KawaiiBarComponent` 内的 `candidateUi.root` 承载，实现是 `HorizontalCandidateComponent` 与 `HorizontalCandidateViewAdapter` 的 View 适配器组合。上游持续改动工具栏，custom 侧每合并一次都要在 View 与 Compose 两套渲染之间重新对齐。

## Decision

候选栏整体迁到 `ComposeCandidateComponent`，以 `ComposeView` 嵌入工具栏的 `candidateUi.root`，渲染由 `ComposeCandidateBar` 承担。

接线链路：

1. 候选事件：`InputBroadcaster.onCandidateUpdate` → `ComposeCandidateComponent.onCandidateUpdate` → `applyCandidates` → `MutableStateFlow<CandidateBarState>`。
2. 渲染：`collectAsState` → `ComposeCandidateBar` 重组，候选列表用 `LazyRow`。
3. 选择与长按：`CandidateItem` 的 onClick 调 `fcitx.select(index)`；onLongClick 调 `inputView.showCandidateActionMenu`。
4. 懒加载与滚动同步：`LazyListState.layoutInfo` 的 snapshotFlow → `onScrollOffsetChanged(firstVisibleIndex)` → `_state.offset` 与 `refreshExpanded` → `_expandedCandidateOffset`；`onLoadMore` → `loadMoreIfNeeded` → `fcitx.getCandidates`。
5. 展开联动：工具栏 `expandButton` 由状态机驱动，是唯一入口，调 `attachWindow(Grid|FlexboxExpandedCandidateWindow)`；展开窗口消费 `composeCandidate.total`（Idle 时为 -1）与 `expandedCandidateOffset`。
6. 滑动开关：`userScrollEnabled = horizontalCandidateSwipe` 控制横向滑动分页，默认关闭。

`ComposeCandidateComponent` 采用内联实现，勿轻拆。

## Alternatives considered

**保持 View 适配器只替换数据源**。会让候选栏继续依赖工具栏 View 树，无法复用 Compose 的状态与懒加载能力。

**把候选栏移出工具栏独立成窗口**。会改变窗口层级与输入法的 insets 计算，影响面超出本次迁移范围。

## Consequences

- 横向候选的滚动位置与展开偏移由 Compose 状态持有，`InputBroadcaster` 只负责事件入口。
- 展开候选窗口与候选栏通过 `total` 与 `expandedCandidateOffset` 协同，改动任一侧都要同时验证另一侧。
- `horizontalCandidateSwipe` 默认关闭；开启后分页由 `LazyRow` 的滚动驱动，不再走 View 的页码计算。
