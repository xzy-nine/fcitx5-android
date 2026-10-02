/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 谷歌数字墨水（Google ML Kit Digital Ink Recognition）桥。
 *
 * 与其他引擎的差别：
 * - **语言模型不随 SDK 分发**：按语言打 BCP-47 tag（中文 = `zh-Hani`），模型在**模型市场**
 *   （`DigitalInkMarketCategory`，清单见 `DigitalInkModelCatalog`）里按语言下载 —— 走
 *   `RemoteModelManager.download()`，识别本身在端上离线跑；中文那份已随包内置；
 * - 识别语言 = 设置项 `AppPrefs.handwriting.handwritingDigitalInkLanguage`（市场里选中），
 *   留空时跟随应用/系统语言（见 [languageTag]）；
 * - 输入是「整段墨迹」（多笔），输出是若干**完整文本候选**（`RecognitionCandidate`：
 *   text + score），因此不需要本项目的叠写切分；
 * - **另有手势分类器**：[classifyGesture] 用 `<tag>-x-gesture` 模型把单笔判成手势类别
 *   （输出同样是 `RecognitionCandidate`，但 `text` 是手势类名而非文字，见 [GoogleGestureLabels]），
 *   供触控笔手势在**非小米设备**上替代本地几何启发式。
 *
 * 所有 ML Kit 调用都 `runCatching`：设备无 Google Play 服务 / ML Kit 未初始化 /
 * 模型未下载时只应表现为「本引擎不可用」，按引擎链回落到下一个后端。
 */
package org.fcitx.fcitx5.android.data.handwriting

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import kotlinx.coroutines.suspendCancellableCoroutine
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import timber.log.Timber
import java.util.Locale

object GoogleDigitalInkEngine {

    private const val TAG = "GoogleDigitalInkEngine"

    /** 单次识别向引擎要的候选上限（调用方再按 topK 截断；识别器复用，故取固定值）。 */
    private const val MAX_RESULTS = 10

    /**
     * 模型对象缓存（按 tag；ML Kit 的模型对象本身无状态，可复用）。
     *
     * 用 map 而非单槽：文字 tag 与手势 tag（`<tag>-x-gesture`）会被交替查询，单槽会让两者
     * 每次都判为「换语言」而重建。只放模型对象（轻量）；识别器另有两份独立缓存。
     * 仅在 [Dispatchers.Default] 的识别/分类调用里读写，且重复构造同一模型对象无害。
     */
    private val modelCache = mutableMapOf<String, DigitalInkRecognitionModel>()

    /** 识别器缓存（构造会加载 native 算法库，必须常驻复用）。 */
    private var recognizer: DigitalInkRecognizer? = null
    private var recognizerTag: String? = null

    /** 手势分类器缓存（与文字识别器分开：模型不同、可同时持有）。 */
    /** 手势分类器缓存（与文字识别器分开：模型不同、可同时持有）。 */
    private var gestureRecognizer: DigitalInkRecognizer? = null
    private var gestureRecognizerTag: String? = null

    /** 已下载语言 tag 的集合（模型市场用；由 [refreshDownloadedModels] 刷新）。 */
    @Volatile
    private var downloadedTags: Set<String> = emptySet()

    /**
     * 当前识别语言 tag：**优先用设置项选中的语言**（模型市场里选中），
     * 未显式选择时跟随应用/系统语言。
     *
     * 跟随语言时：中文统一用 Han 脚本模型 `zh-Hani`（简体/繁体同模型），其余用语言码
     * （`en`/`ja`/`ko`…）。不支持的 tag 会在 [modelFor] 里解析失败 ⇒ 本引擎不可用。
     */
    fun languageTag(context: Context): String {
        val configured = runCatching {
            AppPrefs.getInstance().handwriting.handwritingDigitalInkLanguage.getValue()
        }.getOrNull().orEmpty()
        if (configured.isNotBlank()) return configured
        val locale = context.resources.configuration.locales.takeIf { !it.isEmpty() }?.get(0)
            ?: Locale.getDefault()
        val language = locale.language.lowercase(Locale.ROOT)
        return if (language == "zh") LANGUAGE_ZH_HANI else language
    }

    /** 该语言是否有对应的数字墨水模型（tag 能解析成模型标识）。 */
    fun isLanguageSupported(tag: String): Boolean =
        runCatching { DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag) }.getOrNull() != null

    /**
     * tag 经 ML Kit 解析后的**规范形式**（识别器实际使用的 tag）；不支持时 null。
     *
     * ⚠️ ML Kit 的 `fromLanguageTag` **不做回落**：`en-NZ` 这类官方表里没有的地区变体
     * 返回 null（而**不是**给出 `en`），因此需要按候选链依次尝试 —— 见
     * [canonicalTagFallback]，单点判定请用它而不是本方法。
     */
    fun canonicalTag(tag: String): String? = runCatching {
        DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag)?.languageTag
    }.getOrNull()

    /**
     * 按「最具体 → 最一般」的候选链依次解析，返回**第一个能解析出来的**规范 tag。
     *
     * 用于清单外的系统语言（`en-SG`）：ML Kit 只收录到语言级 `en`，于是这里回落到 `en`，
     * 模型市场据此显示一张**真能下载成功**的直接下载卡片（而不是一张永远失败的卡片）。
     * 整条链都解析不出来 = 该语言 ML Kit 完全不支持，返回 null。
     */
    fun canonicalTagFallback(candidates: List<String>): String? =
        candidates.firstNotNullOfOrNull { canonicalTag(it) }

    /** 单个 tag 的模型（不做回落；解析失败 = 该 tag 不在 ML Kit 表里）。 */
    fun modelForTag(tag: String): DigitalInkRecognitionModel? = modelFor(tag)

    /** 当前语言是否有对应的数字墨水模型。 */
    fun isLanguageSupported(context: Context): Boolean = modelFor(context) != null

    // ------------------------------------------------------------------
    // 手势分类（触控笔手势的来源之一）
    // ------------------------------------------------------------------

    /**
     * 手势分类器 tag：文本 tag + `-x-gesture`（官方约定，见 base-models 的 `-x-gesture` 扩展列）。
     *
     * 例：`zh-Hani` → `zh-Hani-x-gesture`、`en` → `en-x-gesture`。
     */
    fun gestureTag(languageTag: String): String = "$languageTag$GESTURE_SUFFIX"

    /** 当前识别语言是否有手势分类器（列表里 `-x-gesture` 的那一档）。 */
    fun isGestureSupported(context: Context): Boolean =
        isGestureSupportedForTag(gestureTag(languageTag(context)))

    /** 指定手势 tag 是否有对应分类器。 */
    private fun isGestureSupportedForTag(tag: String): Boolean =
        runCatching { DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag) }.getOrNull() != null

    /** 当前语言的手势分类器模型是否已下载（手势路径的判定源，与文字模型分开查询）。 */
    suspend fun isGestureModelDownloaded(context: Context): Boolean =
        isModelDownloaded(context, gestureTag(languageTag(context)))

    /**
     * 单笔墨迹 → 手势类别（谷歌手势分类器）。
     *
     * 分类器输出的是**手势类名**（`scribble`/`circle`/`caret:above`…），不是文本；取分数最高的
     * 候选交给 [GoogleGestureLabels] 映射。模型未下载 / 不支持手势 / 调用失败一律返回
     * [HandwritingStrokeKind.Character]（＝按普通笔画处理）。
     */
    suspend fun classifyGesture(context: Context, stroke: List<StrokePoint>): HandwritingStrokeKind {
        if (stroke.isEmpty()) return HandwritingStrokeKind.Character
        val tag = gestureTag(languageTag(context))
        val model = modelFor(tag) ?: return HandwritingStrokeKind.Character
        val ink = buildInk(listOf(stroke)) ?: return HandwritingStrokeKind.Character
        val client = gestureRecognizerFor(model, tag) ?: return HandwritingStrokeKind.Character
        val label = runCatching {
            client.recognize(ink).awaitValue().candidates
                .maxByOrNull { it.score ?: 0f }
                ?.text
        }.getOrElse {
            // 手势模型未下载 / 引擎内部错误：只当作「本引擎本次判不出手势」
            Timber.w(it, "$TAG: gesture classify failed")
            return HandwritingStrokeKind.Character
        }
        val kind = GoogleGestureLabels.classify(label)
        Timber.d(
            "$TAG: gesture classifier label=%s -> %s", label, kind,
        )
        return kind
    }

    // ------------------------------------------------------------------
    // 模型状态 / 下载 / 删除（模型市场用，按 tag 操作）
    // ------------------------------------------------------------------

    /** 已下载集合的**缓存判定**（同步，供市场列表刷新前使用；用 [refreshDownloadedModels] 刷新）。 */
    fun isModelReadyCached(tag: String): Boolean = tag in downloadedTags

    /** 刷新「已下载语言」缓存（一次 `getDownloadedModels` 拿到全部）。 */
    suspend fun refreshDownloadedModels(context: Context) {
        val models = runCatching {
            RemoteModelManager.getInstance()
                .getDownloadedModels(DigitalInkRecognitionModel::class.java)
                .awaitValue()
        }.getOrElse {
            Timber.w(it, "$TAG: getDownloadedModels failed")
            return
        }
        downloadedTags = models.mapNotNull { it.modelIdentifier?.languageTag }.toSet()
        Timber.d("$TAG: downloaded models = %s", downloadedTags)
    }

    /** 指定语言是否已下载（单点精确查询，无 Google Play 服务 / ML Kit 未初始化时返回 false）。 */
    suspend fun isModelDownloaded(context: Context, tag: String): Boolean {
        val model = modelFor(tag) ?: return false
        return runCatching {
            RemoteModelManager.getInstance().isModelDownloaded(model).awaitValue()
        }.getOrElse {
            Timber.w(it, "$TAG: isModelDownloaded failed")
            false
        }
    }

    /** 当前识别语言是否已下载（设置页/识别前判定）。 */
    suspend fun isModelDownloaded(context: Context): Boolean =
        isModelDownloaded(context, languageTag(context))

    /** 下载指定语言（模型市场触发）；成功后刷新已下载缓存。 */
    suspend fun downloadModel(context: Context, tag: String): Boolean {
        val model = modelFor(tag) ?: run {
            Timber.w("$TAG: no model for language $tag")
            return false
        }
        return runCatching {
            RemoteModelManager.getInstance()
                .download(model, DownloadConditions.Builder().build())
                .awaitVoid()
            Timber.i("$TAG: model downloaded for $tag")
            true
        }.getOrElse {
            Timber.w(it, "$TAG: model download failed")
            false
        }.also { if (it) refreshDownloadedModels(context) }
    }

    /** 下载当前识别语言的模型。 */
    suspend fun downloadModel(context: Context): Boolean =
        downloadModel(context, languageTag(context))

    /** 删除指定语言的模型（模型市场触发）；成功后刷新已下载缓存。 */
    suspend fun deleteModel(context: Context, tag: String): Boolean {
        val model = modelFor(tag) ?: return false
        return runCatching {
            RemoteModelManager.getInstance().deleteDownloadedModel(model).awaitVoid()
            Timber.i("$TAG: model deleted for $tag")
            true
        }.getOrElse {
            Timber.w(it, "$TAG: model delete failed")
            false
        }.also { if (it) refreshDownloadedModels(context) }
    }

    /**
     * 整段墨迹 → 文本候选（按分数降序）。
     *
     * 模型未下载 / 语言不支持 / 引擎不可用时返回空表，由引擎链回落到下一个后端。
     */
    suspend fun recognize(
        context: Context,
        strokes: List<List<StrokePoint>>,
        topK: Int,
    ): List<HandwritingCandidate> {
        val textTag = languageTag(context)
        val model = modelFor(textTag) ?: return emptyList()
        val ink = buildInk(strokes) ?: return emptyList()
        val client = recognizerFor(model, textTag) ?: return emptyList()
        return runCatching {
            val result = client.recognize(ink).awaitValue()
            result.candidates.mapNotNull { candidate ->
                val text = candidate.text
                if (text.isNullOrEmpty()) null
                else HandwritingCandidate(text, candidate.score ?: 0f)
            }.take(topK)
        }.getOrElse {
            // 未下载 / 引擎内部错误：只当作「本引擎本次没结果」
            Timber.w(it, "$TAG: recognize failed")
            emptyList()
        }
    }

    /** 释放识别器（IME 销毁时调用）。 */
    fun close() {
        runCatching { recognizer?.close() }
        recognizer = null
        recognizerTag = null
        runCatching { gestureRecognizer?.close() }
        gestureRecognizer = null
        gestureRecognizerTag = null
        modelCache.clear()
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 当前识别语言对应的模型（解析失败 = 语言不支持）。 */
    private fun modelFor(context: Context): DigitalInkRecognitionModel? =
        modelFor(languageTag(context))

    /** 指定语言对应的模型（解析失败 = 语言不支持）。 */
    private fun modelFor(tag: String): DigitalInkRecognitionModel? {
        modelCache[tag]?.let { return it }
        val model = runCatching {
            // 声明为可空：官方表里没有的 tag 返回 null（见 canonicalTag 注释）
            DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag)?.let {
                DigitalInkRecognitionModel.builder(it).build()
            }
        }.getOrElse {
            Timber.w(it, "$TAG: unsupported language tag $tag")
            null
        }
        if (model != null) modelCache[tag] = model
        return model
    }

    /** 识别器（按语言 tag 复用；语言变化时重建）。 */
    private fun recognizerFor(model: DigitalInkRecognitionModel, tag: String): DigitalInkRecognizer? {
        recognizer?.let { if (recognizerTag == tag) return it }
        val client = runCatching {
            recognizer?.let { runCatching { it.close() } }
            DigitalInkRecognition.getClient(
                DigitalInkRecognizerOptions.builder(model)
                    .setMaxResultCount(MAX_RESULTS)
                    .build()
            )
        }.getOrElse {
            Timber.w(it, "$TAG: recognizer init failed")
            null
        }
        recognizer = client
        recognizerTag = tag
        return client
    }

    /** 手势分类器（按手势 tag 复用；与文字识别器分开持有）。
     *
     * tag 由调用方显式传入（**不能**从 [modelCache] 反查：文字与手势两条路径的 tag 交替出现，
     * 靠「最近一次」判定会误命中）。
     */
    private fun gestureRecognizerFor(
        model: DigitalInkRecognitionModel,
        tag: String,
    ): DigitalInkRecognizer? {
        gestureRecognizer?.let { if (gestureRecognizerTag == tag) return it }
        val client = runCatching {
            gestureRecognizer?.let { runCatching { it.close() } }
            DigitalInkRecognition.getClient(
                DigitalInkRecognizerOptions.builder(model)
                    // 手势要的是「哪一个手势」，一个候选够用（多留几个仅用于日志/调试）
                    .setMaxResultCount(GESTURE_MAX_RESULTS)
                    .build()
            )
        }.getOrElse {
            Timber.w(it, "$TAG: gesture recognizer init failed")
            null
        }
        gestureRecognizer = client
        gestureRecognizerTag = tag
        return client
    }

    /** 笔画序列 → ML Kit 的 Ink（坐标用画布坐标；时间戳原样带上）。 */
    private fun buildInk(strokes: List<List<StrokePoint>>): Ink? {
        val ink = Ink.builder()
        var any = false
        for (stroke in strokes) {
            if (stroke.isEmpty()) continue
            val builder = Ink.Stroke.builder()
            for (point in stroke) {
                builder.addPoint(Ink.Point.create(point.x, point.y, point.timeMs))
            }
            ink.addStroke(builder.build())
            any = true
        }
        return if (any) ink.build() else null
    }

    /** `Task<T>` → 挂起函数（成功返回值，失败抛异常；不支持取消）。 */
    private suspend fun <T> Task<T>.awaitValue(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { value -> cont.resumeWith(Result.success(value)) }
        addOnFailureListener { e -> cont.resumeWith(Result.failure(e)) }
        addOnCanceledListener { cont.cancel() }
    }

    /** `Task<Void>` → 挂起函数（只关心成功/失败）。 */
    private suspend fun Task<Void>.awaitVoid(): Unit = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resumeWith(Result.success(Unit)) }
        addOnFailureListener { e -> cont.resumeWith(Result.failure(e)) }
        addOnCanceledListener { cont.cancel() }
    }

    /** 中文（Han 脚本）模型 tag。 */
    private const val LANGUAGE_ZH_HANI = "zh-Hani"

    /** 手势分类器的 tag 后缀（官方既定的 BCP-47 扩展位）。 */
    private const val GESTURE_SUFFIX = "-x-gesture"

    /** 手势分类向引擎要的候选数（只要最可信的那一个手势类别）。 */
    private const val GESTURE_MAX_RESULTS = 3
}
