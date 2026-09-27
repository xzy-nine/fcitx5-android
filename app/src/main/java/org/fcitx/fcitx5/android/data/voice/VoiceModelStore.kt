/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 * custom: 本地离线模型的存放与解析。
 */
package org.fcitx.fcitx5.android.data.voice

import android.content.Context
import java.io.File

/** 一次流式 zipformer2 transducer 推理需要的 4 个文件。 */
data class VoiceModelFiles(
    val encoder: String,
    val decoder: String,
    val joiner: String,
    val tokens: String,
)

object VoiceModelStore {

    private const val TOKENS_FILE = "tokens.txt"

    fun modelsRoot(context: Context): File = File(context.filesDir, "models")

    fun modelDir(context: Context, modelId: String): File =
        File(modelsRoot(context), modelId)

    /**
     * 解析模型目录下的 4 个文件；任一缺失返回 null。
     *
     * 先只看目录的**直接子文件**（官方包解压后就是这个形状），凑不齐再递归查找；
     * 市场页会在每次重组时按模型调用这里，避免每次都遍历整棵树。
     */
    fun resolve(context: Context, modelId: String): VoiceModelFiles? {
        val dir = modelDir(context, modelId)
        if (!dir.isDirectory) return null
        val direct = dir.listFiles()?.filter { it.isFile && it.length() > 0L }.orEmpty()
        match(direct)?.let { return it }
        val all = dir.walkTopDown().filter { it.isFile && it.length() > 0L }.toList()
        return match(all)
    }

    /** 在给定候选文件里匹配 4 个角色；纯逻辑，便于 JVM 单测。 */
    internal fun match(files: List<File>): VoiceModelFiles? {
        val tokens = files.firstOrNull { it.name.equals(TOKENS_FILE, ignoreCase = true) }
            ?: return null
        val encoder = pick(files, "encoder") ?: return null
        val decoder = pick(files, "decoder") ?: return null
        val joiner = pick(files, "joiner") ?: return null
        return VoiceModelFiles(
            encoder = encoder.absolutePath,
            decoder = decoder.absolutePath,
            joiner = joiner.absolutePath,
            tokens = tokens.absolutePath,
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

    /**
     * 在候选文件里挑出某个角色（encoder/decoder/joiner）的 onnx：
     * 名字里含角色名，优先 int8（体积/速度），其次名字最短的最可能是主文件。
     */
    private fun pick(files: List<File>, role: String): File? {
        val candidates = files.filter {
            it.name.endsWith(".onnx", ignoreCase = true) &&
                    it.name.contains(role, ignoreCase = true)
        }
        if (candidates.isEmpty()) return null
        return candidates.sortedWith(
            compareByDescending<File> { it.name.contains("int8", ignoreCase = true) }
                .thenBy { it.name.length }
        ).first()
    }
}
