/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 手写识别模型（ochwpro，StrokeTransformer）的元信息。
 *
 * 模型本体不随 APK 分发，统一走模型市场（远程索引按 `category: handwriting` 分流）下载到
 * `filesDir/models/<id>/`；索引里给的是 `ochwpro.onnx` + `char_index.json` 两个文件的 URL。
 */
package org.fcitx.fcitx5.android.data.handwriting

/** 模型清单里的一个可下载文件（含可选校验）。 */
data class HandwritingModelFile(
    val name: String,
    val url: String,
    /** 索引里给出的 sha256（可能为空，为空时跳过校验）。 */
    val sha256: String = "",
)

/** 一个可下载的手写模型。 */
data class HandwritingModelInfo(
    val id: String,
    val name: String,
    val description: String = "",
    /** 人类可读体积（索引给的是字符串，如 "6.7 MB"）。 */
    val size: String = "",
    val version: String = "",
    val files: List<HandwritingModelFile> = emptyList(),
)

sealed class HandwritingModelState {
    data object Idle : HandwritingModelState()

    data class Downloading(
        val progress: Float,
        val bytesDownloaded: Long,
        val totalBytes: Long,
    ) : HandwritingModelState()

    data class Error(val message: String) : HandwritingModelState()
    data object Complete : HandwritingModelState()
}

object HandwritingModelCatalog {

    /** 默认模型 id（`AppPrefs.handwriting.handwritingModelId` 的初始值）。 */
    const val DEFAULT_ID = "ochwpro"

    /** 模型文件名（与 ochwpro 仓库导出物一致，亦是索引里的 `name`）。 */
    const val MODEL_FILE = "ochwpro.onnx"
    const val CHAR_INDEX_FILE = "char_index.json"

    /**
     * 内置兜底清单。
     *
     * 远程索引不可用时市场页仍要有内容可选，因此内置一条 ochwpro 条目（权重托管在
     * ModelScope，索引与模型均来自 `github.com/ximeiorg/ochwpro`，MIT 代码 + 自用场景，
     * 归属见仓库根 NOTICE.md）。
     */
    val builtin: List<HandwritingModelInfo> = listOf(
        HandwritingModelInfo(
            id = DEFAULT_ID,
            name = "ochwpro 手写模型",
            description = "基于 StrokeTransformer 的中文单字手写识别（7356 类）",
            size = "6.7 MB",
            version = "v1.0",
            files = listOf(
                HandwritingModelFile(
                    name = MODEL_FILE,
                    url = "$MODELSCOPE_BASE/$MODEL_FILE",
                    sha256 = "eb04d62a314c7d7bac4e34d6ce0c24137474d30ff94b929115a621385420ce13",
                ),
                HandwritingModelFile(
                    name = CHAR_INDEX_FILE,
                    url = "$MODELSCOPE_BASE/$CHAR_INDEX_FILE",
                    sha256 = "171cf2ac23731428ac9dd28f64be82dece5f66a0ca3d674ffb3b567b51532176",
                ),
            ),
        )
    )

    private const val MODELSCOPE_BASE =
        "https://www.modelscope.cn/models/bikeand/ochwpro/resolve/master"
}
