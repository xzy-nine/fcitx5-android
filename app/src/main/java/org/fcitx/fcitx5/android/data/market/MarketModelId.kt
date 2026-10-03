/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 模型根目录与模型 id 的合法性校验（各分类共用）。
 *
 * 模型 id 来自远程索引或内置清单，拼路径前必须校验，否则可能逃出模型根目录。
 */
package org.fcitx.fcitx5.android.data.market

import android.content.Context
import java.io.File

/** 模型落盘位置的单一真源。 */
object MarketPaths {

    /** 模型根目录：语音模型与旧手写 ONNX 引擎共用。 */
    fun modelsRoot(context: Context): File = File(context.filesDir, MODELS_DIR)

    private const val MODELS_DIR = "models"
}

/** 模型 id 的校验规则。 */
object MarketModelId {

    /** id 是否合法：非空、不以 `.` 开头、不含路径分隔符。 */
    fun isValid(id: String): Boolean =
        id.isNotBlank() && !id.startsWith(".") &&
                !id.contains('/') && !id.contains('\\')
}