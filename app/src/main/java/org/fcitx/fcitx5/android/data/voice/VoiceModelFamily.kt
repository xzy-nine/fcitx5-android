/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 离线语音模型的**家族判定**（纯逻辑，无 Android 依赖）。
 *
 * 为什么必须前置判定：sherpa-onnx 的原生构造函数在读到与家族不符的模型元数据时走
 * `SHERPA_ONNX_EXIT`（即 `_Exit`），直接结束 `:asr` 进程——这不是可捕获的异常，
 * 因此**不允许**「先按某个家族构造、失败再换一个」的试错回退。
 *
 * 本文件只做「按文件组合形状给出候选家族」这一步；最终家族由 ONNX 元数据定夺
 * （见 `VoiceModelStore.classify`）。
 */
package org.fcitx.fcitx5.android.data.voice

import java.io.File

/**
 * sherpa-onnx 识别器家族。
 *
 * [streaming] 决定用 `OnlineRecognizer`（边送边出字）还是 `OfflineRecognizer`（整段解码）。
 */
enum class VoiceModelFamily(val streaming: Boolean) {
    /** 流式 transducer（zipformer / conformer / NeMo）：encoder+decoder+joiner。 */
    OnlineTransducer(true),

    /** 流式 paraformer：encoder+decoder。 */
    OnlineParaformer(true),

    /** 流式 zipformer2 CTC：单 model.onnx。 */
    OnlineZipformer2Ctc(true),

    /** 流式 NeMo CTC：单 model.onnx。 */
    OnlineNemoCtc(true),

    /** 流式 T-one CTC：单 model.onnx。 */
    OnlineToneCtc(true),

    /** 离线 transducer（icefall / NeMo parakeet 等）：encoder+decoder+joiner。 */
    OfflineTransducer(false),

    /** 离线 paraformer。 */
    OfflineParaformer(false),

    /** 离线 Whisper：encoder+decoder。 */
    OfflineWhisper(false),

    /** 离线 SenseVoice：单 model.onnx。 */
    OfflineSenseVoice(false),

    /** 离线 zipformer CTC：单 model.onnx（`model_type == zipformer2_ctc`）。 */
    OfflineZipformerCtc(false),

    /** 离线 NeMo EncDec CTC：单 model.onnx（`model_type` 含 `EncDec`）。 */
    OfflineNemoCtc(false),

    /** 离线 WeNet CTC：单 model.onnx（`model_type` 含 `wenet`）。 */
    OfflineWenetCtc(false),

    /** 离线 FireRed ASR：encoder+decoder（`sos`/`eos`/`max_len`）。 */
    OfflineFireRedAsr(false),

    /** 离线 FireRed ASR-2 CTC：单 model.onnx（`model_type == fire-red-asr-2-ctc`）。 */
    OfflineFireRedAsrCtc(false),

    /** 离线 MedASR CTC：单 model.onnx（`model_type == medasr_ctc`）。 */
    OfflineMedAsr(false),

    /** 离线 TeleSpeech CTC：单 model.onnx（`model_type == telespeech_ctc`）。 */
    OfflineTeleSpeech(false),

    /** 离线 Dolphin：单 model.onnx（`invstd`，注意与其他家族的 `inv_stddev` 拼写不同）。 */
    OfflineDolphin(false),
}

/** 模型文件的角色名（与 sherpa 的配置字段一一对应）。 */
object VoiceFileRole {
    const val TOKENS = "tokens"
    const val ENCODER = "encoder"
    const val DECODER = "decoder"
    const val JOINER = "joiner"
    const val MODEL = "model"
}

/**
 * 一次识别会话的完整模型描述（跨 `:asr` 进程传递的单位）。
 *
 * @param family 已判定的家族；`:asr` 侧据此**只构造一次**正确的识别器
 * @param files 角色 → 绝对路径（缺的角色不出现）
 * @param language 多语模型（Whisper / SenseVoice）的语种提示，空 = 用其默认
 * @param useInverseTextNormalization SenseVoice 的逆文本正则化开关
 */
data class VoiceModelSpec(
    val family: VoiceModelFamily,
    val files: Map<String, String>,
    val language: String = "",
    val useInverseTextNormalization: Boolean = true,
) {
    fun path(role: String): String = files[role].orEmpty()
}

/**
 * 模型目录的**文件形状**判定（纯逻辑，可单测）。
 *
 * sherpa 官方包的命名并不统一（`encoder.onnx` / `encoder-epoch-99-avg-1.int8.onnx` /
 * `model.int8.onnx`），因此这里只做后缀/子串匹配。
 */
object VoiceFileShape {

    private const val ONNX = ".onnx"
    private const val TOKENS_FILE = "tokens.txt"

    /**
     * 这些不是模型本体，扫描时排除（含 `bpe.model`、测试音频等）。
     *
     * 注意**不要**把 `tokens.txt` 列进来：它是必需的识别资源，
     * 排除它会让 [VoiceFileShape.roles] 永远返回空表。
     */
    private val AUXILIARY = listOf(
        "bpe.model", "bpe.vocab", "unigram", ".vocab", "lexicon.txt",
        "readme", "test_wav",
    )

    private val ROLES = listOf("encoder", "decoder", "joiner", "preprocessor")

    /** 列出一个模型目录里的模型相关文件（递归，跳过隐藏与辅助文件）。 */
    fun listModelFiles(dir: File): List<File> {
        if (!dir.isDirectory) return emptyList()
        return dir.walkTopDown()
            .filter { it.isFile && it.length() > 0L && !it.name.startsWith(".") }
            .filter { f -> AUXILIARY.none { f.name.contains(it, ignoreCase = true) } }
            .toList()
    }

    fun isOnnx(file: File): Boolean = file.name.endsWith(ONNX, ignoreCase = true)

    fun findTokens(files: List<File>): File? =
        files.firstOrNull { it.name.equals(TOKENS_FILE, ignoreCase = true) }

    /**
     * 在候选里挑某个角色的 onnx。
     *
     * 优先级：`int8`（体积/速度）→ 名字最短（`model.onnx` 优于 `model.int8.opt.onnx`）。
     */
    fun pick(files: List<File>, role: String, exclude: Set<File> = emptySet()): File? =
        files.filter { isOnnx(it) && it !in exclude }
            .filter { it.name.contains(role, ignoreCase = true) }
            .sortedWith(
                compareByDescending<File> { it.name.contains("int8", ignoreCase = true) }
                    .thenBy { it.name.length }
            )
            .firstOrNull()

    /** 名字里不含任何已知角色名的 onnx（CTC / SenseVoice 等单文件模型的主模型）。 */
    fun pickUnnamed(files: List<File>, exclude: Set<File> = emptySet()): File? =
        files.filter { isOnnx(it) && it !in exclude }
            .filter { f -> ROLES.none { f.name.contains(it, ignoreCase = true) } }
            .minByOrNull { it.name.length }

    /** 按形状挑出的角色 → 文件；没有任何 onnx 时返回空表（只有 tokens 不算模型）。 */
    fun roles(dir: File): Map<String, File> {
        val files = listModelFiles(dir)
        val tokens = findTokens(files) ?: return emptyMap()
        val onnx = files.filter { isOnnx(it) }
        if (onnx.isEmpty()) return emptyMap()
        val result = linkedMapOf<String, File>()
        val used = mutableSetOf<File>()

        fun take(role: String, key: String) {
            pick(onnx, role, used)?.let {
                result[key] = it
                used += it
            }
        }

        take("encoder", VoiceFileRole.ENCODER)
        take("decoder", VoiceFileRole.DECODER)
        take("joiner", VoiceFileRole.JOINER)
        // 名字里不含任何角色名的 onnx 即单文件模型的「主模型」（CTC / SenseVoice 等）
        pickUnnamed(onnx, used)?.let {
            result[VoiceFileRole.MODEL] = it
            used += it
        }
        result[VoiceFileRole.TOKENS] = tokens
        return result
    }

    /**
     * 只看文件组合，给出**可能的**家族（有序：越靠前越常见）。
     *
     * 单文件 CTC 类模型无法仅凭文件名区分（zipformer2-ctc / nemo-ctc / t-one 都是
     * 一个 `model.onnx`），故这里给出多个候选，最终由 ONNX 元数据定夺。
     */
    fun shapeCandidates(dir: File): List<VoiceModelFamily> {
        val roles = roles(dir)
        if (roles.isEmpty()) return emptyList()
        val has = { role: String -> roles.containsKey(role) }

        return when {
            has(VoiceFileRole.ENCODER) && has(VoiceFileRole.DECODER) && has(VoiceFileRole.JOINER) ->
                listOf(VoiceModelFamily.OnlineTransducer, VoiceModelFamily.OfflineTransducer)

            has(VoiceFileRole.ENCODER) && has(VoiceFileRole.DECODER) ->
                // 双文件不一定只是 Whisper：离线 FireRed ASR 也是 encoder+decoder
                listOf(
                    VoiceModelFamily.OfflineWhisper,
                    VoiceModelFamily.OnlineParaformer,
                    VoiceModelFamily.OfflineFireRedAsr,
                )

            has(VoiceFileRole.MODEL) -> listOf(
                // 单文件家族：最终家族由元数据定夺（见 VoiceModelClassifier.detect）
                VoiceModelFamily.OfflineSenseVoice,
                VoiceModelFamily.OfflineParaformer,
                VoiceModelFamily.OnlineZipformer2Ctc,
                VoiceModelFamily.OnlineToneCtc,
                VoiceModelFamily.OnlineNemoCtc,
                VoiceModelFamily.OfflineZipformerCtc,
                VoiceModelFamily.OfflineTeleSpeech,
                VoiceModelFamily.OfflineMedAsr,
                VoiceModelFamily.OfflineFireRedAsrCtc,
                VoiceModelFamily.OfflineDolphin,
                VoiceModelFamily.OfflineNemoCtc,
                VoiceModelFamily.OfflineWenetCtc,
            )

            else -> emptyList()
        }
    }
}
