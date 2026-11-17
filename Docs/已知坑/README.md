# 已知坑

已发生的问题与不可违反的约束。本页只做索引：每条坑按其子系统归到子页，细则只在子页维护。

## 子系统索引

| 子系统 | 子页 | 覆盖内容 |
|---|---|---|
| 构建与包名 | [已知坑/构建与包名.md](构建与包名.md)) | debug 与 release 同包名、CRLF 幻影、miuix 依赖版本 |
| fcitx 引擎 | [已知坑/fcitx引擎.md](fcitx引擎.md)) | 重启方式、词库恢复后的进程重启 |
| WebDAV 与网络 | [已知坑/WebDAV与网络.md](WebDAV与网络.md)) | IO 线程要求、本地网络权限、OkHttp 版本 |
| Compose 与 miuix | [已知坑/Compose与miuix.md](Compose与miuix.md)) | ComposeView 限制、弹层宿主、owner 链、双主题、legacy 双栈 |
| 语音输入 | [已知坑/语音输入.md](语音输入.md)) | `:asr` 进程边界、ONNX runtime 打包、在线平台实现、录音权限 |
| 其它 | [已知坑/其它.md](其它.md)) | 本地 IDE 配置、上游 README 范围、新页面用 Compose |

## 相关页面

| 事实 | 位置 |
|---|---|
| custom 特性清单 | [custom特性.md](../约定/README.md) |
| 构建命令 | [构建与验证.md](../工程/构建与验证.md) |
| 界面形态现状 | [界面架构.md](../界面/界面架构.md) |
