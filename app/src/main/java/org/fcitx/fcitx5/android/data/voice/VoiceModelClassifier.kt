/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 用 ONNX 元数据把「候选家族」收敛成**唯一**家族。
 *
 * 这一步不可省略：原生构造函数读到与家族不符的元数据时走 `SHERPA_ONNX_EXIT`（`_Exit`），
 * 直接结束 `:asr` 进程——不是可捕获的异常，所以宁可判定失败（返回 null，界面报
 * 「模型不受支持」）也不能猜一个家族去试。
 *
 * 判据取自 sherpa-onnx v1.13.8 各家族构造函数里 `SHERPA_ONNX_READ_META_DATA*` 读取的键：
 * 用**家族专属键**正向匹配，而不是用「缺某个键」反推（缺失的推断太脆弱）。
 *
 * 有几类天生读不到判据，一律**拒绝**而不是冒险：
 *  - 名字里无 encoder/decoder/joiner 的单文件 CTC：家族之间没有互斥的元数据键
 *    （zipformer2-ctc / telespeech / omnilingual / qwen3-asr 等），只有 `model_type`
 *    这类非必读键可参考，不够可靠；
 *  - Moonshine：构造只读文件名，不读任何元数据。
 * 这些家族在 sherpa 侧本来就靠**用户显式指定配置**来区分，我们无法从模型自身还原。
 */
package org.fcitx.fcitx5.android.data.voice

import java.io.File

object VoiceModelClassifier {

    /** 判定失败的原因（供界面给出可操作提示）。 */
    sealed interface Result {
        data class Ok(val spec: VoiceModelSpec) : Result

        /** 文件形状不像 sherpa 模型（缺 tokens / 没有模型文件）。 */
        data object NotAModel : Result

        /** 形状像，但元数据不足以确定家族——拒绝而不是冒险构造。 */
        data class Unknown(val candidates: List<VoiceModelFamily>) : Result
    }

    /**
     * 判定 [dir] 里模型的家族；无法确定返回 null。
     *
     * @param roles 已按文件形状挑好的角色 → 文件（见 [VoiceFileShape.roles]）
     */
    fun classify(dir: File, roles: Map<String, File>): VoiceModelSpec? =
        (classifyDetailed(dir, roles) as? Result.Ok)?.spec

    /**
     * 判定依据的人类可读描述（家族与用到的角色）。
     *
     * 家族是「错一次就杀 `:asr` 进程」的关键决策，真机排障需要能打印出来。
     * 这里**不直接打日志**：本对象是纯逻辑（可在 JVM 单测里跑），
     * 而 `android.util.Log` 在单测里是未实现的桩。
     */
    fun describe(spec: VoiceModelSpec): String =
        "family=${spec.family.name} roles=${spec.files.keys.sorted()}"

    fun classifyDetailed(dir: File, roles: Map<String, File>): Result {
        if (roles.isEmpty()) return Result.NotAModel
        val candidates = VoiceFileShape.shapeCandidates(dir)
        if (candidates.isEmpty()) return Result.NotAModel

        // 形状已唯一确定时不需要元数据（例如只有 encoder+decoder 的 Whisper 包）
        val unique = candidates.singleOrNull()
        if (unique != null) return buildSpec(roles, unique)

        val meta = readProbeMetadata(candidates, roles)

        // transducer 形状（encoder+decoder+joiner）：流式 vs 离线靠流式几何键区分
        if (candidates.contains(VoiceModelFamily.OnlineTransducer) &&
            candidates.contains(VoiceModelFamily.OfflineTransducer)
        ) {
            if (meta.isNotEmpty()) {
                return buildSpec(
                    roles,
                    if (isStreamingTransducer(meta)) VoiceModelFamily.OnlineTransducer
                    else VoiceModelFamily.OfflineTransducer,
                )
            }
        }

        val family = detect(meta)
        if (family != null && family in candidates) return buildSpec(roles, family)
        // 元数据给不出结论：宁可拒绝，也不猜一个家族去构造原生引擎
        return Result.Unknown(candidates)
    }

    /** 流式专属几何参数（离线导出包没有）。 */
    private fun hasStreamingGeometry(meta: Map<String, String>): Boolean =
        meta.containsKey("decode_chunk_len") || meta.containsKey("T")

    /**
     * transducer 是否为流式。
     *
     * 两类流式 transducer 的判据不同：
     *  - zipformer/conformer 等 icefall 导出：encoder 带流式几何参数（`decode_chunk_len`/`T`）；
     *  - NeMo（fast-conformer / parakeet）：encoder 带流式缓存维度或 `streaming_model_type`，
     *    **不带** icefall 那套几何参数——只看几何参数会把它们误判成离线。
     */
    private fun isStreamingTransducer(meta: Map<String, String>): Boolean {
        val has = { key: String -> meta.containsKey(key) }
        return hasStreamingGeometry(meta) ||
                has("cache_last_channel_dim1") ||
                has("streaming_model_type") ||
                (has("chunk_shift") && has("window_size"))
    }

    /**
     * 读「最可能带判据」的那些文件的元数据并**合并**。
     *
     * 判据会横跨多个文件：transducer 的流式几何参数写在 encoder，而 `vocab_size` /
     * `context_size` 在 decoder，NeMo 的 `pred_rnn_layers` 也在 decoder。只看一个文件
     * 会把在线与离线 transducer 判成同一类。
     */
    private fun readProbeMetadata(
        candidates: List<VoiceModelFamily>,
        roles: Map<String, File>,
    ): Map<String, String> {
        val merged = LinkedHashMap<String, String>()
        val probed = LinkedHashSet<String>()
        for (family in candidates) {
            val file = roles[probeRole(family)] ?: continue
            if (!probed.add(file.absolutePath)) continue
            merged.putAll(OnnxMetadata.read(file))
        }
        // 兜底：候选里没有多文件家族时，仍试一下主模型/encoder
        if (merged.isEmpty()) {
            for (role in listOf(VoiceFileRole.ENCODER, VoiceFileRole.MODEL)) {
                roles[role]?.let { merged.putAll(OnnxMetadata.read(it)) }
            }
        }
        return merged
    }

    private fun buildSpec(roles: Map<String, File>, family: VoiceModelFamily) = Result.Ok(
        VoiceModelSpec(
            family = family,
            files = roles.mapValues { (_, file) -> file.absolutePath },
            // Whisper/SenseVoice 的语种交给引擎按模型能力自行判定；逆文本正则化默认开
            language = "",
            useInverseTextNormalization = true,
        )
    )

    /** 读元数据时优先看哪个文件（多文件家族的判据都在 encoder 上）。 */
    private fun probeRole(family: VoiceModelFamily): String = when (family) {
        VoiceModelFamily.OnlineTransducer,
        VoiceModelFamily.OnlineParaformer,
        VoiceModelFamily.OfflineTransducer,
        VoiceModelFamily.OfflineWhisper,
        VoiceModelFamily.OfflineFireRedAsr,
        -> VoiceFileRole.ENCODER

        else -> VoiceFileRole.MODEL
    }

    /**
     * 按 sherpa 的元数据键判定家族；判不出返回 null。
     *
     * 判定顺序即优先级：越靠前的键越具排他性。
     */
    internal fun detect(meta: Map<String, String>): VoiceModelFamily? {
        if (meta.isEmpty()) return null
        val has = { key: String -> meta.containsKey(key) }
        val value = { key: String -> meta[key].orEmpty() }

        // --- 离线 Whisper：n_mels 为其独有 ---
        if (has("n_mels")) return VoiceModelFamily.OfflineWhisper

        // --- FireRed ASR：sos/eos/max_len ---
        if (has("sos") && has("eos") && has("max_len")) return VoiceModelFamily.OfflineFireRedAsr

        // --- 流式 zipformer2 家族：流式几何参数 ---
        // （online zipformer2 transducer / ctc 与 zipformer2-transducer 共用这些键）
        val streamingGeometry = has("decode_chunk_len") || has("T")
        if (streamingGeometry && has("encoder_dims")) {
            // 有 joiner 才是 transducer；单文件的是 zipformer2 CTC
            return if (has("context_size")) VoiceModelFamily.OnlineTransducer
            else VoiceModelFamily.OnlineZipformer2Ctc
        }

        // --- NeMo 流式（fast-conformer / parakeet）：缓存维度 ---
        if (has("cache_last_channel_dim1")) return VoiceModelFamily.OnlineTransducer
        if (has("streaming_model_type")) return VoiceModelFamily.OnlineTransducer

        // --- T-one CTC：独有 state_dim/sample_rate ---
        if (has("state_dim") || has("frame_length_ms")) return VoiceModelFamily.OnlineToneCtc

        // --- 流式 NeMo CTC：chunk_shift + 无 decoder ---
        if (has("chunk_shift") && has("window_size")) return VoiceModelFamily.OnlineNemoCtc

        // --- 流式 paraformer：encoder_output_size 为其（相对离线）独有 ---
        if (has("encoder_output_size")) return VoiceModelFamily.OnlineParaformer

        // --- SenseVoice：ITN 与语种 token 键 ---
        // 必须排在离线 paraformer 之前：SenseVoice 同样带 lfr_window_size 与
        // neg_mean/inv_stddev，靠 ITN/语种键才能与 paraformer 区分开
        if (has("with_itn") || has("without_itn") || has("lang_zh")) {
            return VoiceModelFamily.OfflineSenseVoice
        }

        // --- 离线 transducer：无流式几何参数，但有 decoder 的 vocab_size/context_size ---
        if (has("vocab_size") && has("context_size")) {
            return VoiceModelFamily.OfflineTransducer
        }

        // --- 离线 paraformer：lfr + 归一化统计量，且无 encoder_output_size ---
        if (has("lfr_window_size") && (has("neg_mean") || has("inv_stddev"))) {
            return VoiceModelFamily.OfflineParaformer
        }

        // --- FireRed ASR：sos/eos/max_len ---
        if (has("sos") && has("eos") && has("max_len")) return VoiceModelFamily.OfflineFireRedAsr

        // --- 显式 model_type 家族 ---
        when {
            value("model_type") == "fire-red-asr-2-ctc" -> return VoiceModelFamily.OfflineFireRedAsrCtc
            value("model_type") == "zipformer2_ctc" -> return VoiceModelFamily.OfflineZipformerCtc
            value("model_type") == "telespeech_ctc" -> return VoiceModelFamily.OfflineTeleSpeech
        }

        // --- Dolphin：invstd（与其它家族的 inv_stddev 拼写不同） ---
        if (has("invstd")) return VoiceModelFamily.OfflineDolphin

        // --- 离线 NeMo CTC / WeNet 离线：键高度重叠，只能靠 model_type 区分 ---
        val modelType = value("model_type")
        if (modelType.startsWith("EncDec")) return VoiceModelFamily.OfflineNemoCtc
        if (modelType.contains("wenet", ignoreCase = true)) return VoiceModelFamily.OfflineWenetCtc

        return null
    }
}
