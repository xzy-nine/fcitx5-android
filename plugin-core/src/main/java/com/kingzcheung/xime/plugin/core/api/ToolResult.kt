/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Kingz Cheung
 *
 * 移植自 Xime (https://github.com/ximeiorg/xime)，见仓库根 NOTICE.md。
 */
package com.kingzcheung.xime.plugin.core.api

/**
 * 工具面板结果显示方式：宿主按插件元数据（manifest.capabilities.tool.display）决策
 * 结果交互（直接上屏 or 纯展示面板），插件侧不再返回。
 * manifest 取值小写：`direct` | `passive`（与 inputMode 风格一致）。
 *
 * 原位于 Xime 的 `api/ToolPlugin.kt`；本分支只移植 ASR 子集，tool 插件契约（ToolPlugin /
 * ToolPanelState）整体未移植，但 `PluginCapabilities.ToolCapabilities.display` 与
 * manifest 解析（CapabilitiesConfig / ToolCapabilitiesConfig）仍引用本枚举，故单独保留。
 */
enum class ToolResult {
    /** 结果生成结束直接上屏（如 AI 翻译）。 */
    DIRECT,

    /** 纯展示面板（InfoPanel）：无输入框、无生成动作，点击节点不上屏。 */
    PASSIVE,
}
