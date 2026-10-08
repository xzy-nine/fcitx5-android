/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 本地语音模型（sherpa-onnx 流式 zipformer2 transducer 导出包）的元信息与内置清单。
 *
 * 清单全部指向官方 sherpa-onnx release 资产（Apache-2.0 模型），不使用任何第三方索引；
 * 清单之外的模型可由用户从本地归档手动导入（见 [VoiceModelImporter]）。
 *
 * 收录口径：引擎走 `OnlineRecognizer` + transducer 四件套（encoder/decoder/joiner 的
 * `.onnx` + `tokens.txt`，见 [VoiceModelStore]），因此只收流式 transducer 导出包，
 * 且优先 int8/fp16 量化版（体积与真机 RTF 可接受）。
 */
package org.fcitx.fcitx5.android.data.voice

import org.fcitx.fcitx5.android.data.market.MarketModel

object VoiceModelCatalog {

    /** 默认模型 id（`AppPrefs.voice.voiceAsrModelId` 的初始值）。 */
    const val DEFAULT_ID = "sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30"

    private const val RELEASE_BASE =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"

    private fun official(asset: String, description: String, size: String): MarketModel =
        MarketModel(
            id = asset.removeSuffix(".tar.bz2"),
            name = asset.removeSuffix(".tar.bz2"),
            description = description,
            size = size,
            archiveUrl = "$RELEASE_BASE/$asset",
        )

    /**
     * 内置清单（手动导入之外的全部可用模型）。
     *
     * 体积一律给归档（tar.bz2）大小；解压后在应用私有目录占其约 1.1–1.3 倍。
     */
    val builtin: List<MarketModel> = listOf(
        official(
            "sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30.tar.bz2",
            "中文流式 Zipformer（int8，识别质量优先）",
            "126.5 MB",
        ),
        official(
            "sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23-mobile.tar.bz2",
            "中文流式 Zipformer 14M（移动端轻量）",
            "51.8 MB",
        ),
        official(
            "sherpa-onnx-streaming-zipformer-multi-zh-hans-int8-2023-12-13.tar.bz2",
            "中文多方言流式 Zipformer（int8）",
            "59.2 MB",
        ),
        official(
            "sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05.tar.bz2",
            "中英混说流式 Zipformer（480ms，自带标点，int8）",
            "127.7 MB",
        ),
        official(
            "sherpa-onnx-x-asr-160ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05.tar.bz2",
            "中英混说流式 Zipformer（160ms 低延迟，自带标点，int8）",
            "127.7 MB",
        ),
        official(
            "sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16-mobile.tar.bz2",
            "中英双语流式 Zipformer（移动端轻量，非量化）",
            "341.1 MB",
        ),
        // --- 整段（离线）家族：说完一段后一次解码，识别质量通常更好、延迟更高 ---
        official(
            "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
            "SenseVoice 多语整段识别（中英日韩粤，int8）",
            "155.4 MB",
        ),
        official(
            "sherpa-onnx-dolphin-base-ctc-multi-lang-int8-2025-04-02.tar.bz2",
            "Dolphin 多语整段 CTC（int8）",
            "76.9 MB",
        ),
        official(
            "sherpa-onnx-whisper-tiny.tar.bz2",
            "Whisper tiny 多语整段识别",
            "110.8 MB",
        ),
    )
}
