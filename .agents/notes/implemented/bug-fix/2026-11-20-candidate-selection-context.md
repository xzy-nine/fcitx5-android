# Agent Note: 候选点击凭发布上下文判定时效

Status: implemented

## Problem

候选栏偶尔无法点击上屏，空格仍能首选上屏，输入其他字符使候选变化后恢复。

候选事件的展示内容与点击的有效性是两个口径：引擎每失效一次候选就发布一个新的选择上下文（`CandidateActionContext`），而候选文本可能完全没变。键盘释放、分页模式切换、方案选项变化都会走到这条路径。

候选栏只按展示内容判定「有没有变化」：`CandidateList.equals` 比 `CandidateWord`，而 `CandidateWord` 的相等性刻意排除 `selectionTarget`；`ComposeCandidateComponent` 的 `lastCandidateData` 去重同样只比文本。于是这一发被吞掉，`_state` 保留旧列表与旧上下文，点击提交的选择目标已被引擎判过期而丢弃。空格走按键路径，不经过选择目标，因此不受影响；文本变化会重建列表并带回新上下文，于是「换字就恢复」。

## Decision

候选列表的相等性与候选事件的去重都包含选择上下文。

`CandidateList.equals` 在展示内容相同后追加 `sameSelectionTargets` 比较；`ComposeCandidateComponent` 的两个入口（列表形态与分页形态）在文本相等后再比 `sameSelectionTargets`。展示内容的相等性仍单独用于前缀判定 `startsWith`：它决定是否重置滚动位置，与点击时效无关。

候选集被引擎重推且前缀一致时，用 `replaceHead` 以本次发布的首批覆盖头部，保留其后已懒加载的候选。头部因此携带当前上下文；保留的尾部维持各自发布时的上下文，引擎判定过期时点击不生效。

## Alternatives considered

**让 `CandidateWord.equals` 包含 `selectionTarget`。** 会让每次上下文前进都触发候选项重新布局，而文本与注释完全没变；展示相等性被刻意排除该元数据正是为了避开这次重排。

**在点击时向引擎重新索取当前上下文再提交。** 取数与提交之间还有一次往返，期间候选可能再次失效；且索引语义会从「用户看到的第 N 项」漂移到「当前第 N 项」。

**只在 `CandidateList.equals` 上修，不改事件去重。** 事件在进入状态之前就被 `lastCandidateData` 丢掉，状态根本没有机会更新，因此两处都要改。

## Consequences

候选事件的去重不再只看文本：上下文前进时即使候选一字未变也会投递一次，代价是一次状态更新与一次列表相等性比较，命中重推路径时不再触发候选项重排。

保留的懒加载尾部只有在引擎重推的首批与其文本前缀一致时才被沿用；此时尾部上下文已过期，点击不生效——这是期望行为，因为引擎确实已经失效了那批候选。

## Testing

`CandidateListSelectionTest` 锁定两个原语：同文本不同版本的两个列表不相等，`replaceHead` 只覆盖头部并保留尾部内容。相邻的 `CandidateUpdatePlanTest`、`ExpandedCandidateRefreshTest` 覆盖候选栏与展开面板的既有去重语义。
