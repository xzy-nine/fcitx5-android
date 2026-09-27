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

data class VoiceModelFile(
    val name: String,
    val url: String,
)

/** 一个可下载的本地模型（索引与内置清单共用）。 */
data class VoiceModelInfo(
    val id: String,
    val name: String,
    val description: String = "",
    /** 人类可读体积（索引里给的是字符串）。 */
    val size: String = "",
    val version: String = "",
    /** tar.bz2 归档地址；为空时按 [files] 逐个下载。 */
    val archiveUrl: String? = null,
    val files: List<VoiceModelFile> = emptyList(),
)

sealed class VoiceModelDownloadState {
    data object Idle : VoiceModelDownloadState()
    data class Downloading(
        val progress: Float,
        val bytesDownloaded: Long,
        val totalBytes: Long,
    ) : VoiceModelDownloadState()

    data class Error(val message: String) : VoiceModelDownloadState()
    data object Complete : VoiceModelDownloadState()
}

object VoiceModelCatalog {

    /** 默认模型 id（`AppPrefs.voice.voiceAsrModelId` 的初始值）。 */
    const val DEFAULT_ID = "sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30"

    private const val RELEASE_BASE =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"

    private fun official(asset: String, description: String, size: String): VoiceModelInfo =
        VoiceModelInfo(
            id = asset.removeSuffix(".tar.bz2"),
            name = asset.removeSuffix(".tar.bz2"),
            description = description,
            size = size,
            archiveUrl = "$RELEASE_BASE/$asset",
        )

    /** 官方流式 zipformer2 transducer 模型（中文为主），索引不可用时的兜底清单。 */
    val builtin: List<VoiceModelInfo> = listOf(
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
