# NOTICE — 第三方代码与许可归属

本文件登记本仓库（`custom` / `语音输入` 分支）中包含的**非上游**第三方代码来源与许可。
本文件只存在于 custom 系分支，合并上游时务必保留。

> 许可画像：本分支**不包含任何 GPL-3.0 代码**。整体分发按
> **LGPL-2.1-or-later（上游与自有代码）+ Apache-2.0 / MIT（第三方组件）** 组合处理。

## 1. 离线语音识别引擎 —— 官方 sherpa-onnx（Apache-2.0）

| 项 | 内容 |
| --- | --- |
| 上游项目 | sherpa-onnx（`https://github.com/k2-fsa/sherpa-onnx`） |
| 版本 | 1.13.8（AAR 资产：`sherpa-onnx-1.13.8.aar`） |
| 许可 | Apache License 2.0（全文见 [`LICENSES/Apache-2.0-sherpa-onnx.txt`](LICENSES/Apache-2.0-sherpa-onnx.txt)） |
| 入库位置 | `app/libs/sherpa-onnx-1.13.8.aar`（`app/build.gradle.kts` 以本地文件依赖引入） |
| 内容 | 官方 Kotlin/JNI 适配器（`com.k2fsa.sherpa.onnx.*`，`classes.jar`）+ 各 ABI 的 `libsherpa-onnx-jni/c-api/cxx-api.so` |
| 内置依赖 | 构建期由 `SherpaOrtAlignPlugin` 产出**摘掉自带 `libonnxruntime.so` 的 AAR 副本**，并把 JNI 库的 ONNX Runtime 符号版本需求改写到 1.28.0；native 运行时由 `com.microsoft.onnxruntime:onnxruntime-android`（MIT，§3）单独提供，包内仅此一份 `libonnxruntime.so` |

自有封装（LGPL-2.1-or-later，非上游代码）：`app/src/main/java/org/fcitx/fcitx5/android/data/voice/**`
（`:asr` 服务/AIDL 客户端、音频采集、会话编排、模型存放与下载、频谱、平台 provider）。

模型（运行时下载，不随仓库分发）：来自 sherpa-onnx 官方 release 资产
（`https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/...`，Apache-2.0 模型），
或用户在设置里自填的索引地址。

## 2. 在线语音识别平台

内置平台由本仓库自行按各平台**官方开发文档**实现（自有代码，LGPL-2.1-or-later）：

| 平台 | 官方接口 | 实现 |
| --- | --- | --- |
| 火山引擎（火山方舟） | 大模型流式语音识别 WebSocket 二进制协议（`sauc`，官方示例 `sauc_websocket_demo.py`） | [`online/VolcengineAsrProvider.kt`](app/src/main/java/org/fcitx/fcitx5/android/data/voice/online/VolcengineAsrProvider.kt) |
| 小米 MiMo | OpenAI 兼容 `POST /v1/chat/completions`（模型 `mimo-v2.5-asr`，`input_audio` + `asr_options` + SSE `stream:true`） | [`online/MiMoAsrProvider.kt`](app/src/main/java/org/fcitx/fcitx5/android/data/voice/online/MiMoAsrProvider.kt) |

不含任何第三方插件框架（Lua/plugin-core）代码；平台凭据存本机偏好，不上传除平台接口外的任何数据。

## 3. 手写识别 —— 系统内置引擎 + Google ML Kit Digital Ink

当前手写识别有两档后端（自有桥代码，LGPL-2.1-or-later）：

| 项 | 内容 |
| --- | --- |
| 系统内置引擎 | 小米「随手写」Pencil Engine（设备预装，`/system_ext/framework/xiaomi-pencilengine-pad.jar`），经反射调用 `recognizeText` 整段识别与 `getGoogleGesture` 手势；该 jar 由设备厂商随系统分发，**不随本仓库或 APK 分发**，可用性随设备而定 |
| Google ML Kit Digital Ink | `com.google.mlkit:digital-ink-recognition:19.0.0`（**Apache License 2.0**，随 Gradle 依赖引入；见 [`LICENSES/`](LICENSES/) 的 Apache-2.0 声明） |
| ML Kit 模型 | 识别模型不随 SDK 分发：运行时由 GMS 模型下载（`RemoteModelManager.download()`）按语言拉取，来源 `https://dl.google.com/handwriting/models/...`；**随包内置**的中文（`zh-Hani`）文字模型与手势分类器 pack 由构建期任务（`DigitalInkModelPlugin`）从同一官方地址拉取、按官方 `manifest.json` 校验 md5 后打进 assets |
| 模型使用条款 | 模型文件由 Google 托管并按 Google 的服务条款与隐私政策提供，仅供端上离线推理；下载/使用行为同样受 Google Play 服务条款约束。其余语言的模型由用户在应用内按需下载，模型市场清单为内置的官方语言表 |
| ONNX Runtime | `com.microsoft.onnxruntime:onnxruntime-android`（**MIT**，见 [`LICENSES/MIT-onnxruntime.txt`](LICENSES/MIT-onnxruntime.txt)）在本分支仅作为**语音引擎（§1）的 native 运行时**提供 `libonnxruntime.so`； |

自有实现（LGPL-2.1-or-later，非上游代码）：

- `app/src/main/java/org/fcitx/fcitx5/android/data/handwriting/**`（引擎统一入口 / 系统引擎反射桥 / 谷歌数字墨水桥 / 内置模型物化）
- `app/src/main/java/org/fcitx/fcitx5/android/data/market/**`（模型市场公共组件：索引解析 / 下载器 / 分类接口）
- `app/src/main/java/org/fcitx/fcitx5/android/input/handwriting/**`（IME 覆盖层面板与画布、触控笔手写会话）
- `build-logic/convention/src/main/kotlin/DigitalInkModelPlugin.kt`（构建期拉取内置模型）

## 4. 其他 JVM 依赖

`com.squareup.okhttp3`（Apache-2.0，含 `okhttp-sse`）、`com.charleskorn.kaml`（Apache-2.0）、
`org.apache.commons:commons-compress`（Apache-2.0）等由 Gradle 依赖声明引入，
构建产物的许可清单由应用内「关于 → 许可」页（AboutLibraries）自动汇总。

## 5. 随包分发

`NOTICE.md` 与 `LICENSES/` 由 `app/src/main/cpp/CMakeLists.txt` 打进
`usr/share/fcitx5/voice/`（随 APK 分发），满足 Apache-2.0 §4 / MIT 的许可与声明附带要求。
其中 MIT 段覆盖 `onnxruntime-android`。
