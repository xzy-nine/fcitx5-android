/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 手写模型的本地存放与解析。
 *
 * 与语音共用 `filesDir/models/<id>/` 根目录（见 `data/voice/VoiceModelStore`），
 * 但解析契约不同：手写是「1 个 onnx + 1 个字符索引 json」，语音是「4 个 onnx/txt」。
 */
package org.fcitx.fcitx5.android.data.handwriting

import android.content.Context
import java.io.File

/** 一次手写推理需要的两个文件。 */
data class HandwritingModelFiles(
    val model: String,
    val charIndex: String,
)

object HandwritingModelStore {

    fun modelsRoot(context: Context): File = File(context.filesDir, "models")

    fun modelDir(context: Context, modelId: String): File =
        File(modelsRoot(context), modelId)

    /**
     * 解析模型目录下的 `ochwpro.onnx` + `char_index.json`；任一缺失返回 null。
     *
     * 先看直接子文件（索引逐文件下载后就是这个形状），凑不齐再递归找一层
     * （用户手动导入的包可能带一层目录）。
     */
    fun resolve(context: Context, modelId: String): HandwritingModelFiles? {
        val dir = modelDir(context, modelId)
        if (!dir.isDirectory) return null
        val direct = dir.listFiles()?.filter { it.isFile && it.length() > 0L }.orEmpty()
        match(direct, id = modelId)?.let { return it }
        val all = dir.walkTopDown().take(64).filter { it.isFile && it.length() > 0L }.toList()
        return match(all, id = modelId)
    }

    /**
     * 在候选文件里匹配 onnx 与字符索引；纯逻辑，便于 JVM 单测。
     *
     * 文件名优先按约定名匹配（`ochwpro.onnx` / `char_index.json`），找不到再放宽为
     * 「任意 .onnx」/「任意 .json」，以适应模型换版本后改名。
     */
    internal fun match(files: List<File>, id: String? = null): HandwritingModelFiles? {
        val model = files.firstOrNull { it.name == HandwritingModelCatalog.MODEL_FILE }
            ?: files.filter { it.name.endsWith(".onnx", ignoreCase = true) }
                .minByOrNull { it.name.length }
            ?: return null
        val index = files.firstOrNull { it.name == HandwritingModelCatalog.CHAR_INDEX_FILE }
            ?: files.filter { it.name.endsWith(".json", ignoreCase = true) }
                .minByOrNull { it.name.length }
            ?: return null
        return HandwritingModelFiles(
            model = model.absolutePath,
            charIndex = index.absolutePath,
        )
    }

    fun isReady(context: Context, modelId: String): Boolean = resolve(context, modelId) != null

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
