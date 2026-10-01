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
import org.fcitx.fcitx5.android.data.market.ModelIndex
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import java.io.File

object DigitalInkMarketCategory :
    BaseMarketCategory(ModelIndex.CATEGORY_DIGITAL_INK, R.string.digital_ink_models) {

    override val indexCategory: String = ModelIndex.CATEGORY_DIGITAL_INK

    /** 清单是官方语言表（内置），没有远程索引。 */
    override val usesRemoteIndex: Boolean = false

    /** 官方语言表 = 全部可下载模型（`id` 就是 BCP-47 语言 tag）。 */
    override val builtin: List<MarketModel> = DigitalInkModelCatalog.languages.map {
        MarketModel(id = it.tag, name = it.name, description = it.tag)
    }

    /** ML Kit 自己管理模型文件；这里只是分类语义上要求的目录（不落文件）。 */
    override fun targetDir(context: Context, model: MarketModel): File =
        File(context.filesDir, "mlkit_digitalink/${model.id}")

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
