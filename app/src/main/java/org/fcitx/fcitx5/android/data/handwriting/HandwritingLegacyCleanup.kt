/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 清理已移除的**手写 ONNX 引擎**留在设备上的模型文件。
 *
 * 旧实现把模型下载到 `filesDir/models/<id>/`（与语音模型同根），标志文件是
 * `ochwpro.onnx` + `char_index.json`。引擎下线后这些文件再无用途，且体积不小
 * （≈7MB），启动时按目录内容识别并删除；语音模型目录不含这两个名字，不受影响。
 */
package org.fcitx.fcitx5.android.data.handwriting

import android.content.Context
import timber.log.Timber
import java.io.File

object HandwritingLegacyCleanup {

    private const val TAG = "HandwritingLegacyCleanup"

    /** 模型根目录（与语音共用）。 */
    private const val MODELS_DIR = "models"

    /** 旧手写 ONNX 模型的标志文件（语音模型目录里不会出现）。 */
    private val LEGACY_MARKERS = listOf("ochwpro.onnx", "char_index.json")

    /**
     * 删除旧手写 ONNX 模型目录（幂等；不存在时只做两次目录列举）。
     *
     * @return 实际删除的目录数（诊断日志用）
     */
    fun removeLegacyOnnxModels(context: Context): Int {
        val modelsRoot = File(context.filesDir, MODELS_DIR)
        if (!modelsRoot.isDirectory) return 0
        var removed = 0
        modelsRoot.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            if (LEGACY_MARKERS.none { File(dir, it).isFile }) return@forEach
            if (dir.deleteRecursively()) {
                removed++
                Timber.i("$TAG: removed legacy handwriting model dir %s", dir.name)
            } else {
                Timber.w("$TAG: failed to remove legacy handwriting model dir %s", dir.name)
            }
        }
        return removed
    }
}
