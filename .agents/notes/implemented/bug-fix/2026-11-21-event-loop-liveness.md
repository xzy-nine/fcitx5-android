# Agent Note: 事件排空循环必须活到取消为止

Status: implemented

## Problem

引擎偶发卡死：面板有反应但没有任何内容，此时从主页面进入设置会闪退，重进应用后恢复。

引擎事件经 `InputEventQueue` 交给 `EventFlowEmitter.drain`，它是该队列唯一的消费方。原实现是一条裸 `while (true) for (event in events.receive()) { flow.emit(event) }`：

1. `receive()` 或 `emit()` 抛出任何非取消异常，循环立即结束，且没有任何东西重建它。
2. 循环结束后队列不再被排空，`size` 不再下降。
3. 按键路径在进入 owner **之前**调用 `awaitCapacity()`，水位到顶后它永久挂起。

第 3 步把键盘输入卡在引擎线程之外，界面于是「有反应但没有内容」。进入设置时 `ComposeMainShell.onStop` 调用 `runIfReady { save() }`，而 `save()` 首行就是 `onOwner { started }`；owner 被同一处等待占住时，这些入口一起卡死。进程重建会重新拉起循环，于是「重进后恢复」。

收集侧有同样的失效面：`handleFcitxEvent` 在收集协程里同步跑完整个分发链，任一接收者抛异常都会结束收集协程。收集协程退出后 `MutableSharedFlow.emit` 在有订阅者时不再返回，排空循环随之停摆，最终落到同一个死结。

## Decision

排空循环只在协程被取消时结束，其余失败一律留在循环内部处理。

- `EventFlowEmitter.drain` 单批读取失败记日志后 `continue`；单帧投递失败记日志后继续同批其余事件。两者都只重抛 `CancellationException`。
- 投递走 `emitWithTimeout`，上限 `EVENT_DELIVERY_TIMEOUT_MS`（1 秒）。没有订阅者时 `emit` 立即返回，因此该上限只对「订阅者存在但不再消费」生效。
- 两处收集（`FcitxInputMethodService.onCreate` 与 `BaseInputView.setupFcitxEventHandler`）逐事件捕获异常，只重抛 `CancellationException`。

## Alternatives considered

**给 `awaitCapacity` 加超时。** 会让按键在队列积压时被静默丢弃，把「卡死」换成「丢输入」，而且掩盖了真正失效的排空方。

**让 `offer` 在水位到顶时丢弃最旧批次。** 提交与按键事件按序触达编辑器，丢弃会破坏这个顺序；`InputEventQueue` 的合并规则正是为「只合并展示类事件、绝不跨提交」而存在。

**在 `requestStop` 处修复。** 那里的 `engineScope.launch` 确实会与 `finalize` 争同一个 owner，但它只影响停止路径，不能解释面板无内容与进设置卡死；单独改它只是移走症状。

## Consequences

排空循环不再可能因单点异常静默消失，引擎 owner 也就不再被 `awaitCapacity` 永久占住。代价是投递失败时该帧对订阅者不可见：这类失败已被记录，且同一批次其余事件照常送达。

任意一帧最多占用 1 秒投递预算。订阅者正常时会立即返回，因此该上限只在订阅者停摆时生效。

## Testing

`EventFlowEmitterTest` 覆盖两点：投递抛异常后循环仍在运行且同批其余事件照常送达；投递持续失败时水位仍会回落，等待容量的调用方能继续。把 `drain` 还原为裸循环后两条用例都变红，该负向验证已实际执行。
