/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 * custom: 本地离线模型的存放与解析。
 *
 * 解析分两步：先按文件形状挑出角色（[VoiceFileShape]），再由 [VoiceModelClassifier]
 * 读 ONNX 元数据定家族——后者必须在构造原生识别器**之前**完成，见该文件说明。
 */
package org.fcitx.fcitx5.android.data.voice

import android.content.Context
import org.fcitx.fcitx5.android.data.market.MarketArchive
import org.fcitx.fcitx5.android.data.market.MarketModelId
import org.fcitx.fcitx5.android.data.market.MarketPaths
import java.io.File

object VoiceModelStore {

    fun modelsRoot(context: Context): File = MarketPaths.modelsRoot(context)

    /**
     * 模型根目录下的全部子目录名（不含临时/备份目录）。
     *
     * 模型 id 由目录名唯一确定：下载与手动导入都把模型直接落在 `<modelsRoot>/<id>/`，
     * 中途失败的暂存目录带探针文件，一律排除。
     */
    fun modelDirs(root: File): List<String> {
        if (!root.isDirectory) return emptyList()
        return root.listFiles()
            .orEmpty()
            .filter {
                it.isDirectory &&
                        isValidModelId(it.name) &&
                        // 暂存/备份目录带探针文件，且绝不能当成模型列出来
                        !File(it, MarketArchive.STAGING_MARKER).exists()
            }
            .map { it.name }
            .sorted()
    }

    /** 模型 id 是否合法：非空、不以 `.` 开头、不含路径分隔符。 */
    fun isValidModelId(modelId: String): Boolean = MarketModelId.isValid(modelId)

    /**
     * 模型目录：id 来自清单或导入归档名，须先校验再拼路径，
     * 拒绝空串、`.`/`..` 段与任意路径分隔符，防止逃出 [modelsRoot]。
     * 非法 id 返回 null（市场页可能渲染任意条目，不能抛异常炸掉重组）。
     */
    fun modelDir(context: Context, modelId: String): File? {
        if (!isValidModelId(modelId)) return null
        return File(modelsRoot(context), modelId)
    }

    /**
     * 目录是否「具备可识别模型的最少文件」（tokens + 至少一个 onnx）。
     *
     * 这是**形状**判定，不涉及家族兼容性；市场页用它决定「已下载」。
     */
    fun rolesOf(context: Context, modelId: String): Map<String, File> {
        val dir = modelDir(context, modelId) ?: return emptyMap()
        return VoiceFileShape.roles(dir)
    }

    fun isReady(context: Context, modelId: String): Boolean = rolesOf(context, modelId).isNotEmpty()

    /**
     * 解析成一个可直接交给 `:asr` 的 [VoiceModelSpec]。
     *
     * 读 ONNX 元数据需要打开模型，开销不大但也不轻，故市场页用 [isReady]
     * （只看文件形状），只有真正要起会话时才调这里。
     * 无法判定家族（元数据缺失/非 sherpa 模型）时返回 null，由会话层报错。
     */
    fun resolve(context: Context, modelId: String): VoiceModelSpec? {
        val dir = modelDir(context, modelId) ?: return null
        val roles = VoiceFileShape.roles(dir)
        if (roles.isEmpty()) return null
        return VoiceModelClassifier.classify(dir, roles)
    }

    /** 带原因的解析：供会话层区分「没下载」与「下了但家族判不出」两种情况。 */
    fun resolveDetailed(context: Context, modelId: String): VoiceModelClassifier.Result {
        val dir = modelDir(context, modelId) ?: return VoiceModelClassifier.Result.NotAModel
        val roles = VoiceFileShape.roles(dir)
        if (roles.isEmpty()) return VoiceModelClassifier.Result.NotAModel
        return VoiceModelClassifier.classifyDetailed(dir, roles)
    }
}
