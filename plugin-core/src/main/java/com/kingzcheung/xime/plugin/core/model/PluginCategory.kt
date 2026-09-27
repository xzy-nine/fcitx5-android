/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Kingz Cheung
 *
 * 移植自 Xime (https://github.com/ximeiorg/xime)，见仓库根 NOTICE.md。
 */
package com.kingzcheung.xime.plugin.core.model

enum class Activation {
    SINGLE,
    MULTI,
    NONE
}

enum class PluginCategory(
    val id: String,
    val label: String,
    val activation: Activation
) {
    EMOJI("emoji", "表情", Activation.MULTI),
    ASR("speech", "语音转文本", Activation.SINGLE),
    PREDICTION("prediction", "智能预测", Activation.MULTI),
    CLIPBOARD_SYNC("clipboard_sync", "剪贴板同步", Activation.SINGLE),
    TOOL("tool", "工具", Activation.MULTI),
    UNKNOWN("unknown", "其他", Activation.NONE);

    companion object {
        fun fromId(id: String?): PluginCategory {
            if (id.isNullOrBlank()) return UNKNOWN
            val normalized = id.trim().lowercase()
            return entries.firstOrNull { it.id == normalized } ?: UNKNOWN
        }
    }
}
