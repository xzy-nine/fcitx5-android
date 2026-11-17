# fcitx 引擎

fcitx 引擎生命周期相关的问题与约束。

## 重启必须用 restartFcitx

重启 fcitx 必须用 `FcitxDaemon.restartFcitx()`。

`stopFcitx()` 与 `startFcitx()` 都不持 `FcitxDaemon` 的锁，分步调用时中间任意 `connect()` 都会抢先 `start()`，导致引擎在词库被覆盖期间仍在运行。表现为输入法按键有反应但无法上屏，只能重启应用。

词库与表格导入统一走 `restartFcitx()`，它在锁内原子完成 stop 与 start。只有整体用户数据导入才用 `stopFcitx()`，且随后必须重启进程。

## 词库恢复后必须重启进程

WebDAV 恢复词库后必须重启进程。

即便用 `restartFcitx()`，引擎侧已完全重启（`ReadyEvent` 正常），IME 与引擎之间的连接状态仍不会复位，表现为按键有触摸反馈但 JNI 侧收不到任何输入事件。

因此手动「下载并恢复词库」成功后走 `AppUtil.showRestartNotification` 加 `exit`，与偏好恢复一致。

`AutoDictSync` 自动恢复同样重建进程，但只在键盘隐藏时：`setKeyboardVisible` 由 `onStartInputView` 与 `onFinishInputView` 维护，退出前复查两次（恢复后与延迟 1 秒），任一次可见就放弃本次重启。且只调用 `AppUtil.exit()` 结束进程，不发通知、不启动界面。
