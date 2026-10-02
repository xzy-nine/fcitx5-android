/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 谷歌数字墨水（ML Kit digital ink）语言模型的**模型市场分类**（`category: digitalink`）。
 *
 * 与其他分类的差别：
 * - 模型文件由 **ML Kit/GMS 自己托管**（不落在 `filesDir` 里），因此下载 / 删除 / 就绪判定
 *   全部改走 `RemoteModelManager`（见 [GoogleDigitalInkEngine]），不走文件下载器；
 * - 清单来自内置目录 [DigitalInkModelCatalog]（官方语言表），**不拉远程索引**；
 * - 「选中」= 设置识别语言 `AppPrefs.handwriting.handwritingDigitalInkLanguage`
 *   （留空则跟随应用/系统语言，见 `GoogleDigitalInkEngine.languageTag`）。
 */
package org.fcitx.fcitx5.android.data.handwriting

import android.content.Context
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.market.BaseMarketCategory
import org.fcitx.fcitx5.android.data.market.MarketDownloadState
import org.fcitx.fcitx5.android.data.market.MarketModel
import org.fcitx.fcitx5.android.data.market.MarketModelGrouping
import org.fcitx.fcitx5.android.data.market.ModelIndex
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import java.io.File
import java.util.Locale
import timber.log.Timber

object DigitalInkMarketCategory :
    BaseMarketCategory(ModelIndex.CATEGORY_DIGITAL_INK, R.string.digital_ink_models) {

    override val indexCategory: String = ModelIndex.CATEGORY_DIGITAL_INK

    /** 清单是官方语言表（内置），没有远程索引。 */
    override val usesRemoteIndex: Boolean = false

    /** 331 个基础语言 + 地区变体：按语言变体折叠，避免一次平铺 361 条。 */
    override val groupsByLanguageVariant: Boolean = true

    /** 官方语言表 = 全部可下载模型（`id` 就是 BCP-47 语言 tag）。 */
    override val builtin: List<MarketModel> = DigitalInkModelCatalog.languages.map {
        MarketModel(id = it.tag, name = it.name, description = it.tag)
    }

    /** ML Kit 自己管理模型文件；这里只是分类语义上要求的目录（不落文件）。 */
    override fun targetDir(context: Context, model: MarketModel): File =
        File(context.filesDir, "mlkit_digitalink/${model.id}")

    /**
     * 系统语言对应的**基础语言键**（`zh-Hani` / `en` / `ja`…）。
     *
     * 市场页用 `MarketModelGrouping.baseTag` 口径比对，因此系统是 `sr-Latn-RS` 时
     * `sr-Latn`（`sr-Latn`/`sr-Latn-RS`）整组前置；中文 → `zh-Hani` 组
     * （`zh-Hani`/`-CN`/`-HK`/`-TW`）整组前置。
     */
    override fun preferredLanguageKeys(context: Context): Set<String> {
        // 用户已在市场里显式选过语言 = 那就是「当前语言」，优先于系统语言
        val configured = runCatching {
            AppPrefs.getInstance().handwriting.handwritingDigitalInkLanguage.getValue()
        }.getOrNull().orEmpty()
        if (configured.isNotBlank()) {
            return setOf(MarketModelGrouping.baseTag(configured))
        }
        val locale = context.resources.configuration.locales.takeIf { !it.isEmpty() }?.get(0)
            ?: return emptySet()
        return setOf(
            MarketModelGrouping.baseTag(systemCandidateTags(locale).firstOrNull().orEmpty())
        ).filter { it.isNotBlank() }.toSet()
    }

    /**
     * **清单之外**的系统语言/地区变体：给出「直接下载」条目（置顶单独成行）。
     *
     * 触发条件（严格按「语言或变体不在内置清单」）：
     * 1. 系统最具体的候选 tag（如 `en-SG`）**不在** [builtin]；
     * 2. 该 tag 能经 ML Kit 解析出规范 tag（`en-SG` → `en`）。
     *
     * 于是系统是 `en-SG` 时，顶部会出现一条「English (Singapore) · en-SG」的直接下载行；
     * 它下载的实际是 ML Kit 的 `en` 模型（卡片 `id` 用规范 tag，就绪判定才认得出来）。
     * 连规范 tag 都解析不出来 = ML Kit 完全不支持该语言，不给卡片（避免死卡片）。
     */
    override fun directDownloadModels(context: Context): List<MarketModel> {
        val locale = context.resources.configuration.locales.takeIf { !it.isEmpty() }?.get(0)
            ?: return emptyList()
        val requested = systemCandidateTags(locale).firstOrNull() ?: return emptyList()
        // 清单里已有该确切 tag → 它就在对应分组里，不重复给「直接下载」
        if (builtin.any { it.id == requested }) return emptyList()
        // ML Kit 不做回落，必须自己按候选链解析出真正能下载的 tag
        val canonical = GoogleDigitalInkEngine.canonicalTagFallback(systemCandidateTags(locale))
        if (canonical == null) {
            Timber.d("digital ink: system language $requested unsupported by ML Kit, no direct row")
            return emptyList()
        }
        return listOf(
            MarketModel(
                id = canonical,
                name = DigitalInkModelCatalog.nameOf(requested),
                // 描述保留原始系统 tag，用户能看出「这条是为我的系统语言准备的」
                description = requested,
            )
        )
    }

    /** 系统 locale → 候选 tag（最具体 → 最一般；`zh` → `zh-Hani`）。 */
    private fun systemCandidateTags(locale: Locale): List<String> =
        DigitalInkSystemLanguage.candidateTags(
            language = locale.language,
            script = locale.script,
            country = locale.country,
        )

    /** 就绪 = ML Kit 认为该语言已下载（含随包内置并已物化的 `zh-Hani`）。 */
    override fun isReady(context: Context, modelId: String): Boolean =
        GoogleDigitalInkEngine.isModelReadyCached(modelId)

    /**
     * 刷新 = 只用内置清单，外加**先刷新一次「已下载语言」**再上清单
     * （本类没有远程索引；先刷新再上清单可让首次渲染就是正确状态）。
     */
    override fun refreshIndex(context: Context) {
        val appContext = context.applicationContext
        setIndexError(null)
        scope.launch {
            GoogleDigitalInkEngine.refreshDownloadedModels(appContext)
            setModels(builtin)
        }
    }

    /**
     * 下载 = `RemoteModelManager.download()`（ML Kit 不提供进度，只报开始 / 完成 / 失败）。
     */
    override fun downloadModel(context: Context, model: MarketModel) {
        if (downloadingId.value != null) return
        val appContext = context.applicationContext
        setLastError(null)
        setDownloadingId(model.id)
        setDownloadState(MarketDownloadState.Downloading(0f, 0L, 0L))
        scope.launch {
            if (GoogleDigitalInkEngine.downloadModel(appContext, model.id)) {
                setDownloadingId(null)
                setDownloadState(MarketDownloadState.Complete)
                // 让统一识别入口立刻用上（模型状态缓存同步刷新）
                HandwritingRecognition.refreshGoogleModel(appContext)
            } else {
                // 失败时保留 downloadingId：卡片继续显示错误与「重试下载」
                val reason = appContext.getString(R.string.digital_ink_download_failed)
                setLastError(reason)
                setDownloadState(MarketDownloadState.Error(reason))
            }
        }
    }

    /** 删除 = `RemoteModelManager.deleteDownloadedModel()`（异步，删完再刷新列表）。 */
    override fun deleteModel(context: Context, model: MarketModel): Boolean {
        cancelDownload()
        val appContext = context.applicationContext
        scope.launch {
            GoogleDigitalInkEngine.deleteModel(appContext, model.id)
            GoogleDigitalInkEngine.refreshDownloadedModels(appContext)
            HandwritingRecognition.refreshGoogleModel(appContext)
            setModels(builtin)
        }
        return true
    }

    /** ML Kit 的已下载状态是异步查出来的；重进子页时重新问一次再重组。 */
    override fun refreshDownloadedState(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            GoogleDigitalInkEngine.refreshDownloadedModels(appContext)
            bumpRevision()
        }
    }

    /** 当前识别语言（设置项为空时 = 跟随系统语言得出的 tag）。 */
    override fun selectedModelId(context: Context): String =
        GoogleDigitalInkEngine.languageTag(context)

    /** 选中 = 写识别语言设置；语言变化后识别器会按 tag 自动重建。 */
    override fun selectModel(context: Context, model: MarketModel) {
        AppPrefs.getInstance().handwriting.handwritingDigitalInkLanguage.setValue(model.id)
        bumpRevision()
        scope.launch { HandwritingRecognition.refreshGoogleModel(context.applicationContext) }
    }
}
