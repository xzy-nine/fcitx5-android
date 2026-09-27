/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Kingz Cheung
 *
 * 移植自 Xime (https://github.com/ximeiorg/xime)，见仓库根 NOTICE.md。
 */
package com.kingzcheung.xime.plugin.core.model

enum class PluginSource {
    SYSTEM,
    FILE,
    ASSET,
    /** 从扩展商店（插件市场）远程下载安装 */
    REMOTE
}
