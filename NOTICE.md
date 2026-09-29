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

## 3. 手写识别（ochwpro 模型 + 官方 ONNX Runtime Java API）

| 项 | 内容 |
| --- | --- |
| 模型 | ochwpro（StrokeTransformer，7356 类中文单字手写） |
| 模型来源 | [ximeiorg/ochwpro](https://github.com/ximeiorg/ochwpro)（**MIT**，其 `model.py`/`dataset.py`/`export_onnx.py` 为训练侧参考实现）；权重托管于 ModelScope `bikeand/ochwpro` |
| 本仓库用法 | **仅使用成品权重**，不复制 Xime（GPL-3.0）的任何运行时代码：JNI 推理、叠写切分、Compose 画布均为本仓库按公开契约独立实现（LGPL-2.1-or-later） |
| 模型分发 | 运行时由模型市场按 `category: handwriting` 下载（`https://index.ximei.me/models/index.yaml` 或用户自填索引），**不随 APK 分发** |
| 推理运行时 | `com.microsoft.onnxruntime:onnxruntime-android`（**MIT**，见 [`LICENSES/MIT-onnxruntime.txt`](LICENSES/MIT-onnxruntime.txt)）；其自带的 `libonnxruntime.so` 在打包期被排除，实际使用 sherpa-onnx AAR 内置的那一份（§1） |
| 训练数据 | 上游权重基于 CASIA-OLHWDB（学术申请制数据集），**本仓库不再训练、不再分发该数据集**；若后续改为自训权重，需重新评估数据授权 |

> 权重与索引的许可状态：ximeiorg/ochwpro 的**代码**为 MIT，但其**权重**仓库（ModelScope）未声明许可，
> 且 xime-index 为 CC BY-NC-SA 4.0。本分支为个人非商业 fork，按非商业使用；**若将来商用需先取得
> 权重作者授权，或改用自训权重**（自训只需 `ochwpro` 的 MIT 代码 + 自采数据）。

自有实现（LGPL-2.1-or-later，非上游代码）：

- `app/src/main/java/org/fcitx/fcitx5/android/data/market/**`（模型市场公共组件：索引解析 / 下载器 / 分类接口）
- `app/src/main/java/org/fcitx/fcitx5/android/data/handwriting/**`（特征工程 / 叠写 DP 切分 / ONNX 会话 / 模型存放）
- `app/src/main/java/org/fcitx/fcitx5/android/input/handwriting/**`（IME 覆盖层面板与画布）

## 4. 其他 JVM 依赖

`com.squareup.okhttp3`（Apache-2.0，含 `okhttp-sse`）、`com.charleskorn.kaml`（Apache-2.0）、
`org.apache.commons:commons-compress`（Apache-2.0）等由 Gradle 依赖声明引入，
构建产物的许可清单由应用内「关于 → 许可」页（AboutLibraries）自动汇总。

## 5. 随包分发

`NOTICE.md` 与 `LICENSES/` 由 `app/src/main/cpp/CMakeLists.txt` 打进
`usr/share/fcitx5/voice/`（随 APK 分发），满足 Apache-2.0 §4 / MIT 的许可与声明附带要求。
其中 MIT 段同时覆盖手写识别用到的 ONNX Runtime（§3）。
