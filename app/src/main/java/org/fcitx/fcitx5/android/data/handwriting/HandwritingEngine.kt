/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 手写识别推理引擎（方案 A：官方 ONNX Runtime Java API）。
 *
 * 模型 = ochwpro（StrokeTransformer，输入 (T,5) + mask，输出 num_classes 个 logits，
 * 单字分类，不是 CTC）。加载时校验「输出维度 == char_index 条数」，避免模型与字符索引
 * 版本不匹配时静默串字。
 *
 * 线程模型：`OrtSession` 不做并发保护，这里用 [Mutex] 串行化推理；调用方应放在
 * 后台协程（IME 面板已经这么做）。
 */
package org.fcitx.fcitx5.android.data.handwriting

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

object HandwritingEngine {

    private const val TAG = "HandwritingEngine"
    const val DEFAULT_TOP_K = 10

    private val mutex = Mutex()

    @Volatile
    private var env: OrtEnvironment? = null

    @Volatile
    private var session: OrtSession? = null

    @Volatile
    private var chars: List<String> = emptyList()

    @Volatile
    private var numClasses: Int = 0

    @Volatile
    private var loadedModelId: String? = null

    /** 运行期探测到的输入名（data / mask），避免模型改名后需要改代码。 */
    @Volatile
    private var inputDataName: String = DEFAULT_INPUT_DATA

    @Volatile
    private var inputMaskName: String = DEFAULT_INPUT_MASK

    /** 是否已就绪（模型与字符索引都已加载）。 */
    val isReady: Boolean get() = session != null && chars.isNotEmpty()

    /**
     * 加载/切换模型。同一模型重复调用直接返回；不同模型会释放旧会话。
     *
     * @return 加载成功与否（失败原因进 logcat，UI 侧表现为「模型不可用」）
     */
    suspend fun load(context: Context, modelId: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (session != null && loadedModelId == modelId) return@withLock true

            val files = HandwritingModelStore.resolve(context, modelId)
            if (files == null) {
                Timber.w("$TAG: model files not found for $modelId")
                return@withLock false
            }
            try {
                val index = parseCharIndex(File(files.charIndex))
                if (index.isEmpty()) {
                    Timber.e("$TAG: char index is empty: ${files.charIndex}")
                    return@withLock false
                }
                val ortEnv = env ?: OrtEnvironment.getEnvironment().also { env = it }
                val options = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(2)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                val newSession = ortEnv.createSession(files.model, options)

                // 维度一致性校验：模型输出类别数必须与 char_index 条数一致，
                // 否则第 N 个 logit 对应的字是错的（换模型/换索引时最常见的静默错误）
                val outInfo = newSession.outputInfo.values.firstOrNull()?.info as? TensorInfo
                val classes = outInfo?.shape?.lastOrNull()?.toInt() ?: -1
                if (classes > 0 && classes != index.size) {
                    Timber.e("$TAG: dim mismatch: model=$classes, charIndex=${index.size}")
                    runCatching { newSession.close() }
                    return@withLock false
                }

                session?.let { runCatching { it.close() } }
                session = newSession
                chars = index
                numClasses = if (classes > 0) classes else index.size
                loadedModelId = modelId
                // 输入名探测：约定名优先，否则按声明顺序取前两个（第一个是特征、第二个是 mask）
                val names = newSession.inputNames.toList()
                inputDataName = names.firstOrNull { it == DEFAULT_INPUT_DATA }
                    ?: names.getOrNull(0) ?: DEFAULT_INPUT_DATA
                inputMaskName = names.firstOrNull { it == DEFAULT_INPUT_MASK }
                    ?: names.getOrNull(1) ?: DEFAULT_INPUT_MASK
                Timber.i("$TAG: loaded $modelId, classes=$numClasses, inputs=[${names.joinToString()}]")
                true
            } catch (e: Exception) {
                Timber.e(e, "$TAG: load failed for $modelId")
                false
            }
        }
    }

    /** 卸载会话（IME 销毁时调用）。 */
    fun release() {
        runCatching { session?.close() }
        session = null
        chars = emptyList()
        numClasses = 0
        loadedModelId = null
    }

    /**
     * 单字识别：把笔画序列特征化后送模型，返回 top-k 候选（按概率降序）。
     *
     * @param strokes 笔画序列（画布坐标，顺序即时间序）
     */
    suspend fun predict(
        strokes: List<List<StrokePoint>>,
        topK: Int = DEFAULT_TOP_K,
    ): List<HandwritingCandidate> = withContext(Dispatchers.Default) {
        if (strokes.isEmpty()) return@withContext emptyList()
        mutex.withLock {
            val ortSession = session ?: return@withLock emptyList()
            val index = chars
            if (index.isEmpty()) return@withLock emptyList()

            val simplified = HandwritingPreprocess.simplifyStrokes(strokes)
            val sequence = HandwritingPreprocess.strokesToSequence(simplified)
            if (sequence.length == 0) return@withLock emptyList()
            val mask = HandwritingPreprocess.buildMask(sequence.length)

            try {
                val envRef = env ?: return@withLock emptyList()
                val dataTensor = OnnxTensor.createTensor(
                    envRef,
                    FloatBuffer.wrap(sequence.data),
                    longArrayOf(
                        1L,
                        HandwritingPreprocess.FIXED_LEN.toLong(),
                        HandwritingPreprocess.FEATURE_DIM.toLong(),
                    ),
                )
                val maskTensor = OnnxTensor.createTensor(
                    envRef,
                    arrayOf(mask),
                )
                val logits = dataTensor.use { d ->
                    maskTensor.use { m ->
                        ortSession.run(mapOf(inputDataName to d, inputMaskName to m)).use { result ->
                            (result[0].value as? Array<*>)
                                ?.firstOrNull()
                                ?.let { row -> (row as? FloatArray)?.copyOf() }
                        }
                    }
                } ?: return@withLock emptyList()

                val probabilities = softmax(logits)
                val k = min(topK, probabilities.size)
                val order = probabilities.indices
                    .sortedByDescending { probabilities[it] }
                    .take(k)
                val seen = HashSet<String>()
                val out = ArrayList<HandwritingCandidate>(k)
                for (i in order) {
                    val ch = index.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: continue
                    if (!seen.add(ch)) continue
                    out.add(HandwritingCandidate(ch, probabilities[i]))
                }
                out
            } catch (e: Exception) {
                Timber.e(e, "$TAG: predict failed")
                emptyList()
            }
        }
    }

    /** 解析 char_index.json：兼容 chars / char_index / labels / 纯 map 四种格式。 */
    internal fun parseCharIndex(file: File): List<String> {
        val json = JSONObject(file.readText().trimStart('\uFEFF'))
        return when {
            json.has("chars") -> json.getJSONArray("chars").let { arr ->
                (0 until arr.length()).map { arr.optString(it, "") }
            }

            json.has("char_index") -> expand(json.getJSONObject("char_index"))
            json.has("labels") -> json.getJSONArray("labels").let { arr ->
                (0 until arr.length()).map { arr.optString(it, "") }
            }

            else -> expand(json)
        }
    }

    private fun expand(obj: JSONObject): List<String> {
        val indexed = HashMap<Int, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = obj.optInt(key, -1)
            if (value >= 0) indexed[value] = key
        }
        val maxIndex = indexed.keys.maxOrNull() ?: return emptyList()
        val list = MutableList(maxIndex + 1) { "" }
        indexed.forEach { (idx, ch) -> if (idx in list.indices) list[idx] = ch }
        return list
    }

    private fun softmax(logits: FloatArray): FloatArray {
        if (logits.isEmpty()) return FloatArray(0)
        var maxLogit = logits[0]
        for (v in logits) if (v > maxLogit) maxLogit = v
        val out = FloatArray(logits.size)
        var sum = 0.0
        for (i in logits.indices) {
            val e = exp((logits[i] - maxLogit).toDouble())
            out[i] = e.toFloat()
            sum += e
        }
        if (sum <= 0.0) return out
        for (i in out.indices) out[i] = (out[i] / sum).toFloat()
        return out
    }

    /** 模型输入名（ochwpro 导出时的约定名；运行期探测失败时作为兜底）。 */
    private const val DEFAULT_INPUT_DATA = "data"
    private const val DEFAULT_INPUT_MASK = "mask"

    /** 供设置页展示：当前加载的模型 id。 */
    fun loadedModel(): String? = loadedModelId
}
