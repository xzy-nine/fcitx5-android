# NOTICE — 第三方代码与许可归属

本文件登记本仓库（`custom` / `语音输入` 分支）中包含的**非上游**第三方代码来源与许可。
本文件只存在于 custom 系分支，合并上游时务必保留。

## 1. 语音输入核心 —— 移植自 Xime

| 项 | 内容 |
| --- | --- |
| 上游项目 | Xime（`https://github.com/ximeiorg/xime`），Android 输入法，2.8.0-beta2 |
| 许可 | GNU General Public License v3.0（全文见 [`LICENSES/GPL-3.0-or-later.txt`](LICENSES/GPL-3.0-or-later.txt)） |
| 版权 | Copyright © 2026 Kingz Cheung |
| 商标 | Xime 名称、Logo 等品牌资产**不在** GPLv3 覆盖范围内（见上游 `TRADEMARKS.md`）。本仓库**不使用**其名称与品牌资产 |

移植位置（均以 `SPDX-License-Identifier: GPL-3.0-or-later` 头部标注来源）：

- `app/src/main/cpp/asr/**` —— 离线流式 zipformer2 ASR 的 JNI 桥、模型、特征提取、识别器
- `app/src/main/aidl/com/kingzcheung/xime/service/**` —— 离线识别服务 AIDL
- `app/src/main/java/com/kingzcheung/xime/**` —— 离线 ASR Kotlin 侧（`speech` / `service` / `model` / `util` / `settings`）
- `plugin-core/**` —— Lua 插件框架（ASR 子集）
- `app/src/main/assets/plugins/{funasr-asr,volc-asr}/**` —— 在线 ASR Lua 插件

> 说明：为便于与上游 Xime 对比同步、并避免改动 JNI 符号名（`Java_com_kingzcheung_xime_speech_AsrNative_*`，6 个）
> 与 AIDL 包名，上述移植代码**刻意保留 Xime 的原包名**。

### 许可后果（重要）

本仓库根 [`LICENSE`](LICENSE) 为 **LGPL-2.1**（上游 fcitx5-android）。LGPL-2.1-or-later 允许按
「GPL v2 或更新版本」再许可，而 GPLv2-or-later 可升级为 GPLv3；因此与上表 GPL-3.0 代码**可以组合**，
但**整体分发（APK）须按 GPL-3.0-or-later 处理**：保留版权与许可声明、提供对应源码。
`com.kingzcheung.xime.*`、`plugin-core/`、`app/src/main/cpp/asr/**` 下的文件单独看即 GPL-3.0-or-later。

## 2. 随源码一同收录的原生第三方库

| 组件 | 版本 | 许可 | 位置 |
| --- | --- | --- | --- |
| kaldi-native-fbank | 1.22.3 | Apache-2.0（[`LICENSES/Apache-2.0-kaldi-native-fbank.txt`](LICENSES/Apache-2.0-kaldi-native-fbank.txt)） | `app/src/main/cpp/asr/third_party/knf/kaldi-native-fbank-1.22.3/` |
| kissfft | 131.2.0 | BSD-3-Clause（[`LICENSES/BSD-3-Clause-kissfft.txt`](LICENSES/BSD-3-Clause-kissfft.txt)）；`kissfft_i32.hh` / `kissfft.hh` 为 Unlicense（见同目录 `LICENSES/`） | `app/src/main/cpp/asr/third_party/knf/kissfft/` |

## 3. 构建期获取的原生第三方库

| 组件 | 版本 | 许可 | 获取方式 |
| --- | --- | --- | --- |
| ONNX Runtime | 1.28.0 | MIT（[`LICENSES/MIT-onnxruntime.txt`](LICENSES/MIT-onnxruntime.txt)） | Gradle 任务从 Maven Central 解析 `com.microsoft.onnxruntime:onnxruntime-android:1.28.0`（AAR），抽取 `jni/<abi>/libonnxruntime.so`；**C++ 头文件已随仓库收录**（`app/src/main/cpp/asr/onnxruntime/include/`，17 个文件） |

## 4. 算法参考（未收录源码）

`app/src/main/cpp/asr/*.cc|h` 为独立实现，算法参考：

- **sherpa-onnx**（`https://github.com/k2-fsa/sherpa-onnx`），Apache-2.0

相关声明保留在各源文件头部的 `// Reference algorithm: sherpa-onnx ...` 注释中。

## 5. 其他 JVM 依赖

`com.github.houbb:opencc4j`（Apache-2.0）、`org.luaj:luaj-jse`（MIT）、`com.charleskorn.kaml`（Apache-2.0）、
`org.apache.commons:commons-compress`（Apache-2.0）、`com.squareup.okhttp3`（Apache-2.0）等
由 Gradle 依赖声明引入，构建产物的许可清单由应用内「关于 → 许可」页（AboutLibraries）自动汇总。
