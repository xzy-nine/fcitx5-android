/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Kingz Cheung
 *
 * 移植自 Xime (https://github.com/ximeiorg/xime)，见仓库根 NOTICE.md。
 */
package com.kingzcheung.xime.plugin.core.lua

import com.kingzcheung.xime.plugin.core.api.IPluginEntryClass
import com.kingzcheung.xime.plugin.core.api.PluginIcon
import com.kingzcheung.xime.plugin.core.config.IPluginConfigurable
import com.kingzcheung.xime.plugin.core.config.UiNode
import com.kingzcheung.xime.plugin.core.config.UiNodeType
import com.kingzcheung.xime.plugin.core.model.PluginContext
import org.luaj.vm2.LuaValue

/**
 * Lua 脚本插件的宿主侧适配器基类：实现通用接口（入口生命周期 + 设置项 + 图标），
 * 具体能力接口（AsrPlugin）由按插件类别派生的子类实现，
 * 保证 `instance is AsrPlugin` 只对 speech 类型插件成立。
 */
open class LuaPluginAdapter(
    protected val runtime: LuaScriptRuntime,
    protected val pluginContext: PluginContext
) : IPluginEntryClass, IPluginConfigurable {

    override fun getSettingsSchema(): List<UiNode> {
        val result = runtime.call("getSettingsSchema")
        if (!result.istable()) return emptyList()
        // 设置字段必须带 key（configStore 绑定）；无 key 的展示节点（SECTION/DIVIDER 等）
        // 由渲染层容忍，这里仅过滤无法绑定的节点
        return parseUiNodes(result).filter { !it.key.isNullOrBlank() }
    }

    override suspend fun onAction(action: String): String? {
        if (action.isBlank()) return "未知操作"
        val result = runtime.call(action)
        if (result.isnil()) return null
        val msg = result.tojstring()
        return if (msg.isBlank()) null else msg
    }

    override fun getOptions(key: String): List<String>? {
        val result = runtime.call("getOptions", LuaValue.valueOf(key))
        if (!result.istable()) return null
        return stringList(result)
    }

    /**
     * 统一声明式 UI 节点解析（设置表单 getSettingsSchema 与面板 getPanelState.ui 共用一套
     * 契约）。Lua 节点 table → [UiNode]，字段：
     * `{ type, key?, label?, value?, defaultValue?, options?, placeholder?, helpText?,
     *   unit?, style?, section?, required? }`。
     *
* 旧字段名兼容（v0.2.0 存量插件）：title→label、content→value、actionId→key、
 * type 的 "action" → BUTTON、BUTTON 的 action→key。未知 type 丢弃。
     * 节点数上限 [MAX_UI_NODES]（防插件撑爆面板/表单）。
     */
    protected fun parseUiNodes(value: LuaValue): List<UiNode> {
        if (!value.istable()) return emptyList()
        val nodes = ArrayList<UiNode>()
        for (node in LuaScriptRuntime.tableToList(value)) {
            if (!node.istable()) continue
            val m = LuaScriptRuntime.tableToMap(node)
            nodes += UiNode(
                type = parseUiNodeType(m["type"]?.tojstring()),
                key = (m["key"] ?: m["actionId"] ?: m["action"])?.tojstring()?.takeIf { it.isNotBlank() },
                label = (m["label"] ?: m["title"])?.tojstring(),
                value = (m["value"] ?: m["content"])?.tojstring(),
                defaultValue = m["defaultValue"]?.tojstring(),
                options = stringList(m["options"] ?: LuaValue.NIL),
                placeholder = m["placeholder"]?.tojstring(),
                helpText = m["helpText"]?.tojstring(),
                unit = m["unit"]?.tojstring(),
                style = m["style"]?.tojstring(),
                section = m["section"]?.tojstring(),
                required = m["required"]?.toboolean() ?: false,
            )
            if (nodes.size >= MAX_UI_NODES) break
        }
        return nodes
    }

    private fun parseUiNodeType(type: String?): UiNodeType = when (type?.lowercase()) {
        "textarea" -> UiNodeType.TEXTAREA
        "secret" -> UiNodeType.SECRET
        "select" -> UiNodeType.SELECT
        "multi_select" -> UiNodeType.MULTI_SELECT
        "switch" -> UiNodeType.SWITCH
        "number" -> UiNodeType.NUMBER
        "action", "button" -> UiNodeType.BUTTON // 旧契约 "action" 兼容
        "section" -> UiNodeType.SECTION
        "metric" -> UiNodeType.METRIC
        "divider" -> UiNodeType.DIVIDER
        else -> UiNodeType.TEXT // 未知 type 降级为普通文本
    }

    private fun stringList(value: LuaValue): List<String> {
        if (!value.istable()) return emptyList()
        return LuaScriptRuntime.tableToList(value).mapNotNull { it.tojstring() }
    }

    override fun getIcon(): PluginIcon? {
        val result = runtime.call("getIcon")
        if (!result.istable()) return null
        val map = LuaScriptRuntime.tableToMap(result)
        val text = map["text"]?.tojstring()?.takeIf { it.isNotBlank() }
        if (text != null) return PluginIcon(text = text)
        val assetName = map["assetName"]?.tojstring()
            ?.takeIf {
                it.isNotBlank() && com.kingzcheung.xime.plugin.core.runtime.installer.InstallerManager.isValidResourcePath(it)
            }
        if (assetName != null) return PluginIcon(assetName = assetName)
        return null
    }

    override fun onLoad(context: PluginContext) {
        if (runtime.load()) {
            runtime.callOnLoad()
        }
    }

    override fun onUnload() {
        runtime.close()
    }

    /**
     * 通用配置就绪判定：所有 required 配置字段均已有值。
     * Lua 插件不再需要实现 isConfigured——配置状态由宿主统一判定。
     */
    open fun isConfigured(): Boolean {
        val schema = getSettingsSchema()
        if (schema.isEmpty()) return true
        return schema.none { it.required && it.key != null && pluginContext.configStore.get(it.key).isNullOrBlank() }
    }

    companion object {
        /** 声明式 UI 节点数上限（防插件撑爆设置表单）。 */
        private const val MAX_UI_NODES = 64
    }
}
