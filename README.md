# fcitx5-android

本项目是 [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) 的派生分支，由个人维护，仅以源码形式提供，不发布构建产物。

主程序接入直连 Rime 的输入后端，内置简体拼音方案，开箱即可输入中文；其余 Rime 方案由用户自行导入。Rime 方案决定输入规则与词典，键盘布局决定屏幕按键与手势，两者分别配置。

## 包名与安装

自行构建安装。Release 与 Debug 的 `applicationId` 同为 `org.fcitx.fcitx5.android`，签名不同，两者不能共存安装；切换需先卸载，应用内数据会丢。

覆盖升级需要同包名、同签名。换包名、签名或卸载前先备份。

## 开始使用

1. 构建并安装后，在 Android 设置中启用并选择本输入法。
2. 内置简体拼音立即可用。如需其它方案，进入「输入与候选 → 中州韵设置」导入完整 Rime 方案并部署。
3. 短按语言键切换中英文，行为由方案的 `ascii_composer` 决定；长按打开方案选单。
4. 在「键盘」和「外观」调整布局、形态、主题与字体。

## 主要功能

| 功能 | 说明 |
|---|---|
| 键盘与候选 | 分离式键盘、横屏分体数字键盘、横向候选滑动模式；候选栏与展开候选在 Compose 实现 |
| 工具栏 | 高度可调，按钮布局自定义并支持拖动，更多页兼作编辑页 |
| 布局与 Picker | 符号、表情、颜文字 Picker 左右分栏，与展开候选共用三栏骨架 |
| 语音输入 | 内置离线 sherpa-onnx 与在线火山、MiMo；支持第三方 AIDL 插件接入 |
| 手写输入 | 自带手写键盘、触控笔手写；识别引擎可走系统内置或谷歌数字墨水 |
| 模型市场 | 语音与数字墨水模型共用的下载、导入与归档解压 |
| 剪贴板与同步 | 剪贴板主页与编辑、广播配对、WebDAV 云端备份与词库合并 |
| 设置 | Compose 导航与设置界面，搜索、高亮与分组 |

各项能力的代码归属与实现约束以 [约定/](Docs/约定/) 为准。

## 开发

开发者和 AI 编程助手先读 [AGENTS.md](AGENTS.md)，界面修改再读 [界面设计约定](Docs/界面/界面设计约定.md)。

| 主题 | 入口 |
|---|---|
| 分支模型、防冲突约定、注释边界 | [约定/开发约定.md](Docs/约定/开发约定.md) |
| custom 能力清单与代码归属 | [约定/](Docs/约定/) |
| 构建命令、工具链、引擎来源 | [工程/构建与验证.md](Docs/工程/构建与验证.md) |
| 测试分层与证据选择 | [工程/测试策略.md](Docs/工程/测试策略.md) |
| 性能诊断与探针 | [工程/性能诊断.md](Docs/工程/性能诊断.md) |
| 模块与包职责 | [工程/模块结构.md](Docs/工程/模块结构.md) |
| 已知问题与约束 | [已知坑/](Docs/已知坑/) |
| 语音插件接入协议 | [集成/语音插件接入.md](Docs/集成/语音插件接入.md) |

全部文档索引见 [Docs/README.md](Docs/README.md)。

## 项目来源

本项目由三个仓库构成派生链。

1. **根父**：[`fcitx5-android/fcitx5-android`](https://github.com/fcitx5-android/fcitx5-android)（`master`）。Android 前端的原始实现，当前 `upstream` 远程指向它。
2. **精简引擎**：[`SandyYuR/fcitx5-android`](https://github.com/SandyYuR/fcitx5-android)（`fx-rime-only`）。在根父基础上把输入后端换成直连 Rime，并配套精简的前端。本仓库通过 `gitee.com/xzy_nine/direct-rime` 镜像合入，合并提交 `2411d9d13`。
3. **custom 修改**：[`xzy-nine/fcitx5-android`](https://github.com/xzy-nine/fcitx5-android)（`custom`）。在根父基础上的自有改动，当前 `origin` 远程指向它。

`fxliang/fcitx5-android` 的提交也出现在本仓库历史中，属于 `SandyYuR` 分支合并时带入的上游内容，不是独立的派生层级。

本仓库当前分支为 `custom`。`lib/fcitx5*`、`lib/libime` 等目录是上游遗留的库，当前输入链路走 `direct-rime` 模块与 [native/rime-sdk.lock.json](native/rime-sdk.lock.json) 锁定的 Rime SDK。

## 范围与限制

完整 XKB Compose、按应用行为等价与广泛设备回归仍有边界；历史测试与性能数据保留各自的版本、方案与设备条件，不作为所有设备的保证。

保留上游署名与许可证，参见 [LICENSE](LICENSE) 与应用内第三方库清单。
