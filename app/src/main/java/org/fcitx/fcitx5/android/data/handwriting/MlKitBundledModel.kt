/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: **随包内置**的谷歌数字墨水模型（ML Kit digital ink）。
 *
 * ML Kit 的数字墨水模型不随 SDK 分发：正常路径由 GMS MDD 在运行时下载到应用私有目录，
 * 并把「该模型的文件组已下载」记在应用自己的 `shared_prefs/gms_icing_mdd_*`（MDD 索引）
 * 与 `files/mdd_pds_config/`。本对象把构建期内置进 `assets/` 的**同一份模型文件与 MDD 状态**
 * 在首次启动时物化回原位置，于是 ML Kit 直接认为模型已就绪 —— 无需联网下载，离线可用。
 *
 * assets 来源有两处（在 APK 内合并成同一棵树）：
 * - **模型本体**：构建期由 `fetchDigitalInkModel`（`DigitalInkModelPlugin`）从 dl.google.com
 *   拉取并校验 md5，产物挂在 `build/generated/digitalink-model/assets`（见 app/build.gradle.kts）；
 * - **MDD 状态**：随源码放在 `app/src/main/mlkit-assets/`（官方不提供下载）。
 *
 * assets 树根 = 应用 `dataDir` 下的相对路径（`files/...` 与 `shared_prefs/...`）：
 * - `files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_<ts>/`：
 *   模型本体 —— **中文文字模型**（`lstm_chinese_4x192.tflite` +
 *   `qrnn.zh.reco_20191217.fst_none.recospec.local`）与**全部手势分类器**
 *   （27 种文字 × 2 个 pack 的 `scribe.<script>.<date>.{tflite,recospec}.local`）；
 * - `shared_prefs/gms_icing_mdd_*mlkit_digital_ink_recognition.xml`：MDD 文件组索引
 *   （`isModelDownloaded` 的判定源；已包含全部 `-x-gesture` tag 的条目）；
 * - `files/mdd_pds_config/shared/`：MDD 日志状态。
 *
 * ⚠️ **必须在任何 ContentProvider 之前物化**（MDD 状态是 SharedPreferences，晚于它第一次
 * 读取就无效）：调用点是 `FcitxApplication.attachBaseContext`。已存在的文件一律不覆盖，
 * 因此真机上下载过的模型不会被内置副本顶掉；物化失败只表现为「模型未下载」，
 * 引擎链会正常回落到下一个引擎。
 */
package org.fcitx.fcitx5.android.data.handwriting

import android.content.Context
import timber.log.Timber
import java.io.File

object MlKitBundledModel {

    private const val TAG = "MlKitBundledModel"

    /** assets 内的内置模型根目录（其下路径 = 应用 dataDir 相对路径）。 */
    private const val ASSET_ROOT = "mlkit/digitalink"

    /** 模型文件所在目录（用于判断是否已物化）。 */
    private const val MODEL_DIR = "files/mlkit_digital_ink_recognition"

    /** 物化完成标记（放 filesDir；避免每次进程启动都遍历 assets）。 */
    private const val MARKER = "mlkit_bundled_model.stamp"

    /**
     * 内置模型内容的**修订号**：marker 记下它，不一致时重新物化一次。
     *
     * 增删内置 pack 时必须 +1，否则已物化过的安装会整棵树跳过。重跑时目标文件仍逐个跳过
     * （已存在的不覆盖），因此只补齐新增内容。
     */
    private const val BUNDLED_MODEL_REVISION = 2

    /** 包内是否带内置模型。 */
    fun isBundled(context: Context): Boolean = runCatching {
        context.assets.list(ASSET_ROOT)?.isNotEmpty() == true
    }.getOrDefault(false)

    /** 模型文件是否已就位（MDD datadownload 目录下存在 `.tflite`）。 */
    fun isMaterialized(context: Context): Boolean =
        File(context.dataDir, MODEL_DIR).walkTopDown().any { it.isFile && it.name.endsWith(".tflite") }

    /** 当前内置内容的修订号是否已物化过。 */
    private fun isRevisionMaterialized(context: Context): Boolean =
        runCatching {
            File(context.filesDir, MARKER).readText().trim() == BUNDLED_MODEL_REVISION.toString()
        }.getOrDefault(false)

    /**
     * 把内置模型与 MDD 状态物化到应用私有目录（幂等）。
     *
     * - 目标文件已存在则**跳过**（不覆盖真机已下载的模型 / MDD 状态）；
     * - 未内置模型（assets 为空）时直接返回；
     * - **修订号变化时重跑**（补新增的 pack，见 [BUNDLED_MODEL_REVISION]）；
     * - 全程 runCatching：物化失败只应表现为「模型未下载」，不影响启动。
     *
     * @return 本次是否真的写入了文件
     */
    fun materializeIfNeeded(context: Context): Boolean {
        if (!isBundled(context)) return false
        if (isMaterialized(context) && isRevisionMaterialized(context)) return false
        val copied = runCatching { copyAssetTree(context, ASSET_ROOT, context.dataDir) }
            .getOrElse {
                Timber.w(it, "$TAG: materialize failed")
                return false
            }
        // 即使本次没有新文件也要写 marker：修订号已对齐，避免每次启动都遍历 assets
        runCatching {
            File(context.filesDir, MARKER).writeText("$BUNDLED_MODEL_REVISION\n")
        }
        if (copied <= 0) return false
        Timber.i("$TAG: bundled digital ink model materialized, new files=%d", copied)
        return true
    }

    /** 递归复制 assets 子树到 [targetRoot]；返回本次新写入的文件数。 */
    private fun copyAssetTree(context: Context, assetPath: String, targetRoot: File): Int {
        val assets = context.assets
        val children = assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            // 叶子（assets.list 对文件返回空表）：按相对路径落盘
            val relative = assetPath.removePrefix("$ASSET_ROOT/")
            val target = File(targetRoot, relative)
            if (target.isFile && target.length() > 0L) return 0
            target.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return 1
        }
        var copied = 0
        for (child in children) {
            copied += copyAssetTree(context, "$assetPath/$child", targetRoot)
        }
        return copied
    }
}
