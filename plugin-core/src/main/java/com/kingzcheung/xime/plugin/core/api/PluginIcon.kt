/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Kingz Cheung
 *
 * 移植自 Xime (https://github.com/ximeiorg/xime)，见仓库根 NOTICE.md。
 */
package com.kingzcheung.xime.plugin.core.api

/**
 * 插件可选图标（[IPluginEntryClass.getIcon] 返回值）。
 *
 * 原位于 Xime 的 `api/EmojiPlugin.kt`；本分支只移植 ASR 子集，emoji 插件契约整体未移植，
 * 但入口接口仍引用本类型，故单独保留（内容与 Xime 一致）。
 *
 * @param text 文字图标（如 "译"）
 * @param assetName 插件包 resources/ 下图片文件名（宿主负责渲染）
 */
data class PluginIcon(
    val text: String? = null,
    val assetName: String? = null
)
