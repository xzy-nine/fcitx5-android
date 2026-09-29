/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 手写模型的内置清单（索引不可用时的兜底）。
 *
 * 权重托管在 ModelScope，来自 `github.com/ximeiorg/ochwpro`（MIT 代码；自用场景，
 * 归属见仓库根 NOTICE.md）。可下载清单与下载/删除逻辑由
 * [HandwritingMarketCategory] 提供（模型市场公共组件的一个分类）。
 */
package org.fcitx.fcitx5.android.data.handwriting

import org.fcitx.fcitx5.android.data.market.MarketModel
import org.fcitx.fcitx5.android.data.market.MarketModelFile

object HandwritingModelCatalog {

    /** 默认模型 id（`AppPrefs.handwriting.handwritingModelId` 的初始值）。 */
    const val DEFAULT_ID = "ochwpro"

    /** 模型文件名（与 ochwpro 仓库导出物一致，亦是索引里的 `name`）。 */
    const val MODEL_FILE = "ochwpro.onnx"
    const val CHAR_INDEX_FILE = "char_index.json"

    private const val MODELSCOPE_BASE =
        "https://www.modelscope.cn/models/bikeand/ochwpro/resolve/master"

    /** 索引不可用时的兜底清单。 */
    val builtin: List<MarketModel> = listOf(
        MarketModel(
            id = DEFAULT_ID,
            name = "ochwpro 手写模型",
            description = "基于 StrokeTransformer 的中文单字手写识别（7356 类）",
            size = "6.7 MB",
            version = "v1.0",
            files = listOf(
                MarketModelFile(
                    name = MODEL_FILE,
                    url = "$MODELSCOPE_BASE/$MODEL_FILE",
                    sha256 = "eb04d62a314c7d7bac4e34d6ce0c24137474d30ff94b929115a621385420ce13",
                ),
                MarketModelFile(
                    name = CHAR_INDEX_FILE,
                    url = "$MODELSCOPE_BASE/$CHAR_INDEX_FILE",
                    sha256 = "171cf2ac23731428ac9dd28f64be82dece5f66a0ca3d674ffb3b567b51532176",
                ),
            ),
        )
    )
}
