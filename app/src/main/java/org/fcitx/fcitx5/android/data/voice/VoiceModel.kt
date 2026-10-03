/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 本地语音模型（sherpa-onnx 流式 zipformer2 transducer 导出包）的元信息与内置清单。
 *
 * 内置清单全部指向官方 sherpa-onnx release 资产（Apache-2.0 模型），
 * 远程索引不可用时用它兜底（不再依赖第三方（Xime）索引或 ModelScope 镜像）。
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

    /** 官方流式 zipformer2 transducer 模型（中文为主），索引不可用时的兜底清单。 */
    val builtin: List<MarketModel> = listOf(
        official(
            "sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30.tar.bz2",
            "中文流式 Zipformer（int8，识别质量优先）",
            "132.6 MB",
        ),
        official(
            "sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23-mobile.tar.bz2",
            "中文流式 Zipformer 14M（移动端轻量）",
            "54.3 MB",
        ),
        official(
            "sherpa-onnx-streaming-zipformer-multi-zh-hans-int8-2023-12-13.tar.bz2",
            "中文多方言流式 Zipformer（int8）",
            "62.0 MB",
        ),
    )
}
