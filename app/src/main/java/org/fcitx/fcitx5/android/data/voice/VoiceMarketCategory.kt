/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 语音模型市场分类（清单内置，支持从本地归档手动导入）。
 *
 * 清单 = [VoiceModelCatalog.builtin]（官方 sherpa-onnx release 资产，固定不变）
 * ＋ **手动导入**的模型（落在模型根目录但不在内置清单里，由 [importedModels] 现场派生）。
 */
package org.fcitx.fcitx5.android.data.voice

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.market.BaseMarketCategory
import org.fcitx.fcitx5.android.data.market.MarketArchive
import org.fcitx.fcitx5.android.data.market.MarketCategoryIds
import org.fcitx.fcitx5.android.data.market.MarketDownloadState
import org.fcitx.fcitx5.android.data.market.MarketModel
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.utils.queryFileName
import timber.log.Timber
import java.io.File
import kotlin.coroutines.coroutineContext

object VoiceMarketCategory :
    BaseMarketCategory(MarketCategoryIds.ASR, R.string.voice_model_market) {

    /** 内置清单 = 官方 release 资产（不随环境变化）。 */
    override val builtin: List<MarketModel> = VoiceModelCatalog.builtin

    /**
     * 当前清单 = 内置清单 ＋ 手动导入项。
     *
     * 导入项的目录直接落在模型根目录下，因此**每次调用都从文件系统派生**：
     * 进程重启、外部写入、导入完成都自动反映出来，不需要额外的登记表。
     * 排序仍交给市场页（按「在用 / 已下载」重新排）。
     */
    fun currentModels(context: Context): List<MarketModel> = builtin + importedModels(context)

    /** 清单外、但本地已就绪的模型：来自手动导入。 */
    private fun importedModels(context: Context): List<MarketModel> {
        val known = builtin.mapTo(HashSet()) { it.id }
        return VoiceModelStore.modelDirs(VoiceModelStore.modelsRoot(context))
            .filter { it !in known }
            .mapNotNull { id ->
                if (!VoiceModelStore.isReady(context, id)) return@mapNotNull null
                val size = VoiceModelStore.modelDir(context, id)
                    ?.let { MarketArchive.readableSize(MarketArchive.sizeOf(it)) }
                    .orEmpty()
                MarketModel(
                    id = id,
                    name = id,
                    description = context.getString(R.string.market_model_imported),
                    size = size,
                )
            }
            .sortedBy { it.id }
    }

    /** 非法 id（外部拼出来的）返回 null，由基类按「下载失败 / 不可删除」处理。 */
    override fun targetDir(context: Context, model: MarketModel): File? =
        VoiceModelStore.modelDir(context, model.id)

    /** 支持从本地归档导入清单外的模型（清单是固定集合，用户自备的模型走这条路）。 */
    override val supportsImport: Boolean = true

    private val _importingName = MutableStateFlow<String?>(null)
    override val importingName: StateFlow<String?> = _importingName.asStateFlow()

    override fun isReady(context: Context, modelId: String): Boolean =
        VoiceModelStore.resolve(context, modelId) != null

    override fun selectedModelId(context: Context): String =
        AppPrefs.getInstance().voice.voiceAsrModelId.getValue()
            .ifBlank { VoiceModelCatalog.DEFAULT_ID }

    override fun selectModel(context: Context, model: MarketModel) {
        AppPrefs.getInstance().voice.voiceAsrModelId.setValue(model.id)
    }

    override fun refreshIndex(context: Context) {
        setIndexError(null)
        setModels(currentModels(context))
    }

    override fun refreshDownloadedState(context: Context) {
        setModels(currentModels(context))
    }

    /**
     * 删除模型：删完必须**重算清单**。
     *
     * 手动导入项是每次从文件系统派生出来的，文件删掉后就该从列表里消失；
     * 若只删文件不重算，它会以「未下载」的卡片留下，而导入项没有下载地址，
     * 点「下载」只会建出一个空目录。
     */
    override fun deleteModel(context: Context, model: MarketModel): Boolean {
        val deleted = super.deleteModel(context, model)
        if (deleted) setModels(currentModels(context))
        return deleted
    }

    /**
     * 从本地归档导入一个模型（SAF `GetContent`，**不需要任何存储权限**）。
     *
     * 走与下载同一条落盘链路：[MarketArchive.installFromStream] 先解压到暂存目录，
     * 校验能解析出 transducer 四件套后才晋升，因此失败不会留下半成品，
     * 也不会破坏同名旧目录。模型 id 直接取文件名（去归档扩展名）。
     */
    override fun importModel(context: Context, uri: Uri) {
        val appContext = context.applicationContext
        runTrackedDownload(placeholderFor(appContext, uri)) { job ->
            val displayName = runCatching { appContext.contentResolver.queryFileName(uri) }
                .getOrNull()
            _importingName.value = displayName
            // 不用 runCatching：它会把协程取消也当成失败，导致「取消导入」后弹出错误
            val result = try {
                Result.success(
                    withContext(Dispatchers.IO) {
                        val fileName = displayName
                            ?: throw IllegalArgumentException(
                                appContext.getString(R.string.market_model_import_unreadable)
                            )
                        val format = MarketArchive.formatOf(fileName)
                            ?: throw IllegalArgumentException(
                                appContext.getString(
                                    R.string.market_model_import_unsupported, fileName
                                )
                            )
                        val modelId = fileName.substringBeforeLast('.')
                        val dir = VoiceModelStore.modelDir(appContext, modelId)
                            ?: throw IllegalArgumentException(
                                appContext.getString(
                                    R.string.market_model_import_unsupported, fileName
                                )
                            )
                        val input = appContext.contentResolver.openInputStream(uri)
                            ?: throw IllegalArgumentException(
                                appContext.getString(R.string.market_model_import_unreadable)
                            )
                        // 解压是长耗时 CPU 操作：把当前协程的 Job 传进去，让解压循环能检查取消
                        val coroutineCtx = coroutineContext
                        input.use { stream ->
                            MarketArchive.installFromStream(
                                input = stream,
                                format = format,
                                targetDir = dir,
                                totalBytes = 0L,
                                checkActive = { coroutineCtx.ensureActive() },
                                onProgress = { fraction ->
                                    setDownloadState(MarketDownloadState.Extracting(fraction))
                                },
                            )
                        }
                        // 解出来的目录凑不出 transducer 四件套 = 不是能用的模型：回滚
                        if (!VoiceModelStore.isReady(appContext, modelId)) {
                            dir.deleteRecursively()
                            throw IllegalArgumentException(
                                appContext.getString(R.string.market_model_import_invalid)
                            )
                        }
                        modelId
                    }
                )
            } catch (e: CancellationException) {
                // 取消是正常路径：不写任何错误状态，交给 cancelDownload 收尾
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (!isCurrentDownload(job)) return@runTrackedDownload
            result
                .onSuccess {
                    setDownloadingId(null)
                    setDownloadState(MarketDownloadState.Complete)
                    setModels(currentModels(appContext))
                }
                .onFailure { e ->
                    Timber.w(e, "voice model import failed")
                    // 保留 downloadingId，卡片继续显示错误与「重试」
                    val reason = MarketArchive.messageOf(e)
                    setLastError(reason)
                    setDownloadState(MarketDownloadState.Error(reason))
                }
        }
    }

    /** 取消导入同样要摘掉「正在导入」提示（基类只清下载状态）。 */
    override fun cancelDownload() {
        super.cancelDownload()
        _importingName.value = null
    }

    /** 导入期间卡片显示的名字（拿不到文件名时给未知）。 */
    private fun placeholderFor(context: Context, uri: Uri): MarketModel {
        val fileName = runCatching { context.contentResolver.queryFileName(uri) }.getOrNull()
        return MarketModel(
            id = fileName?.substringBeforeLast('.') ?: IMPORT_PLACEHOLDER_ID,
            name = fileName ?: context.getString(R.string.market_model_importing),
        )
    }

    private const val IMPORT_PLACEHOLDER_ID = "__import__"
}
