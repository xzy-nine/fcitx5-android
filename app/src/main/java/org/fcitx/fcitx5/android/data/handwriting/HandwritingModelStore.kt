/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 手写模型的本地存放与就绪判定（与语音共用 `filesDir/models/<id>/` 根目录）。
 *
 * 与语音的差别只在「就绪」的定义：手写是 1 个 onnx + 1 个字符索引 json，
 * 语音是 4 个 onnx/txt。因此这里只判断这两个文件是否存在且非空。
 */
package org.fcitx.fcitx5.android.data.handwriting

import android.content.Context
import java.io.File

object HandwritingModelStore {

    fun modelDir(context: Context, modelId: String): File =
        File(context.filesDir, "models/$modelId")

    fun modelFile(context: Context, modelId: String): File =
        File(modelDir(context, modelId), HandwritingModelCatalog.MODEL_FILE)

    fun charIndexFile(context: Context, modelId: String): File =
        File(modelDir(context, modelId), HandwritingModelCatalog.CHAR_INDEX_FILE)

    /** onnx 与字符索引都存在且非空才算就绪。 */
    fun isReady(context: Context, modelId: String): Boolean =
        modelFile(context, modelId).isUsable() && charIndexFile(context, modelId).isUsable()

    private fun File.isUsable(): Boolean = isFile && length() > 0L

    /** 该模型目录占用的字节数。 */
    fun sizeOnDisk(context: Context, modelId: String): Long {
        val dir = modelDir(context, modelId)
        if (!dir.isDirectory) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** 删除整个模型目录；返回是否成功。 */
    fun delete(context: Context, modelId: String): Boolean {
        val dir = modelDir(context, modelId)
        if (!dir.exists()) return false
        return dir.deleteRecursively()
    }
}
