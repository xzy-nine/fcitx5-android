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
| 内置依赖 | 该 AAR 同时携带 ONNX Runtime（MIT，见 [`LICENSES/MIT-onnxruntime.txt`](LICENSES/MIT-onnxruntime.txt)），因此本仓库不再单独抽取 `libonnxruntime.so` |

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

## 3. 其他 JVM 依赖

`com.squareup.okhttp3`（Apache-2.0，含 `okhttp-sse`）、`com.charleskorn.kaml`（Apache-2.0）、
`org.apache.commons:commons-compress`（Apache-2.0）等由 Gradle 依赖声明引入，
构建产物的许可清单由应用内「关于 → 许可」页（AboutLibraries）自动汇总。

## 4. 随包分发

`NOTICE.md` 与 `LICENSES/` 由 `app/src/main/cpp/CMakeLists.txt` 打进
`usr/share/fcitx5/voice/`（随 APK 分发），满足 Apache-2.0 §4 / MIT 的许可与声明附带要求。
