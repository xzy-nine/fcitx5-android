/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Kingz Cheung
 *
 * 移植自 Xime (https://github.com/ximeiorg/xime) 的 speech/AsrModelManager.kt，见仓库根 NOTICE.md。
 *
 * 与上游的差异（为适配 fcitx5-android）：
 *  - 选中的模型 id 由上游独立的 `asr_model` SharedPreferences 改为 AppPrefs.voice.voiceAsrModelId
 *    （这样它会出现在应用设置页、参与设置搜索与偏好备份）。
 *  - 新增 [resolveModelFiles]：把模型信息解析成 4 个绝对路径。原因：本移植的 `:asr` 进程不初始化
 *    AppPrefs，无法自己读偏好，因此由 app 侧解析好路径后经 AIDL 传入（见 AsrInferenceClient）。
 *  - [isModelReady] 从上游的「目录非空」加强为「4 个必需文件都存在且非空」。
 *  - 模型市场索引（xime_index）与内置默认模型的兜底逻辑与上游一致。
 */
package com.kingzcheung.xime.speech

import android.content.Context
import com.kingzcheung.xime.model.ModelCategory
import com.kingzcheung.xime.model.ModelManager
import com.kingzcheung.xime.model.ModelStorage
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import java.io.File

/**
 * ASR 模型管理与选择。
 *
 * 模型推理由自研的 streaming zipformer2 实现（libasr_jni.so）负责。
 * 模型清单与描述来自「模型市场」远程索引（[ModelManager]，category=asr），
 * 索引未加载时回退到内置默认模型（zipformer-zh-int8）。
 */
class AsrModelManager(private val context: Context) {

    companion object {
        /** 内置默认 ASR 模型 id。 */
        const val DEFAULT_ID = "zipformer-zh-int8"

        /** 内置默认 ASR 模型（远程索引加载前/失败时的兜底）。 */
        val DEFAULT_MODEL = AsrModelInfo(
            id = DEFAULT_ID,
            name = "中文 Zipformer int8",
            description = "Zipformer 架构，适合实时语音识别，int8 量化",
            language = "zh",
            size = "132.63MB",
            downloadUrl = "https://www.modelscope.cn/models/bikeand/asr/resolve/master/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30.tar.bz2",
            modelType = "transducer",
            files = listOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt"),
            encoderFile = "encoder.int8.onnx",
            decoderFile = "decoder.onnx",
            joinerFile = "joiner.int8.onnx"
        )

        /** 模型推理必需的 4 个文件。 */
        val REQUIRED_FILES = listOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt")

        /** tokens 文件名固定（与上游一致）。 */
        const val TOKENS_FILE = "tokens.txt"

        /** 把索引里的 ModelInfo 转换为 ASR 专用模型信息。 */
        private fun toAsrModelInfo(info: com.kingzcheung.xime.model.ModelInfo): AsrModelInfo {
            val version = info.resolvedVersion()
            val fileNames = info.files.map { it.name }
            return AsrModelInfo(
                id = info.id,
                name = info.name,
                description = info.description,
                language = "zh",
                size = version?.size ?: info.size,
                downloadUrl = info.archiveUrl ?: "",
                modelType = "transducer",
                files = fileNames,
                encoderFile = "encoder.int8.onnx",
                decoderFile = "decoder.onnx",
                joinerFile = "joiner.int8.onnx"
            )
        }
    }

    data class AsrModelInfo(
        val id: String,
        val name: String,
        val description: String = "",
        val language: String,
        val size: String,
        val downloadUrl: String,
        val modelType: String = "transducer",
        val files: List<String>,
        val encoderFile: String = "",
        val decoderFile: String = "",
        val joinerFile: String = "",
        val needsAutoPunctuation: Boolean = true
    )

    /** 一次推理需要的 4 个绝对路径。 */
    data class ModelFiles(
        val encoder: String,
        val decoder: String,
        val joiner: String,
        val tokens: String,
    )

    /** ASR 分类的模型清单（索引优先，索引未加载时用内置默认）。 */
    fun getAsrModels(): List<AsrModelInfo> {
        val fromIndex = ModelManager.getModelsByCategory(ModelCategory.ASR)
            .map { toAsrModelInfo(it) }
        return if (fromIndex.isNotEmpty()) fromIndex else listOf(DEFAULT_MODEL)
    }

    /** 所有 ASR 模型 id，用于判断某个 id 是否为已知 ASR 模型。 */
    fun getAsrModelIds(): Set<String> = getAsrModels().map { it.id }.toSet()

    /** 目录内是否 4 个必需文件都存在且非空。 */
    fun isModelReady(): Boolean = resolveModelFiles(getSelectedModelInfo()) != null

    fun getSelectedModelDir(): File {
        val modelId = getSelectedModelId()
        val dir = ModelStorage.getModelDir(context, modelId)
        // 兼容旧版：自动迁移 asr_models/<id>/ 下的模型文件
        ModelStorage.migrateLegacyForModel(context, modelId)
        return dir
    }

    fun getSelectedModelId(): String =
        AppPrefs.getInstance().voice.voiceAsrModelId.getValue().ifBlank { DEFAULT_ID }

    /** 当前选中模型的完整信息（索引优先，兜底内置默认）。 */
    fun getSelectedModelInfo(): AsrModelInfo? {
        val modelId = getSelectedModelId()
        return getAsrModels().find { it.id == modelId } ?: DEFAULT_MODEL
    }

    fun setModel(modelId: String) {
        AppPrefs.getInstance().voice.voiceAsrModelId.setValue(modelId)
    }

    /**
     * 解析模型文件路径。任意一个必需文件缺失/为空即返回 null。
     * 归档解压后首层目录会被剥掉，因此这里同时支持直接子文件与子目录（findFile 递归）。
     */
    fun resolveModelFiles(info: AsrModelInfo? = getSelectedModelInfo()): ModelFiles? {
        val model = info ?: return null
        val dir = getSelectedModelDir()
        if (!dir.exists()) return null

        val encoderName = model.encoderFile.ifBlank { REQUIRED_FILES[0] }
        val decoderName = model.decoderFile.ifBlank { REQUIRED_FILES[1] }
        val joinerName = model.joinerFile.ifBlank { REQUIRED_FILES[2] }

        val encoder = findFile(dir, encoderName) ?: return null
        val decoder = findFile(dir, decoderName) ?: return null
        val joiner = findFile(dir, joinerName) ?: return null
        val tokens = findFile(dir, TOKENS_FILE) ?: return null

        val all = listOf(encoder, decoder, joiner, tokens)
        if (all.any { !it.exists() || it.length() <= 0L }) return null

        return ModelFiles(
            encoder = encoder.absolutePath,
            decoder = decoder.absolutePath,
            joiner = joiner.absolutePath,
            tokens = tokens.absolutePath,
        )
    }

    fun findFile(dir: File, fileName: String): File? {
        val direct = File(dir, fileName)
        if (direct.exists()) return direct
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                val found = findFile(child, fileName)
                if (found != null) return found
            }
        }
        return null
    }
}
