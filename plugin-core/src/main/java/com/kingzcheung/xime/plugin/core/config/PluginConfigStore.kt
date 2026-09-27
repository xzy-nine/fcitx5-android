/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Kingz Cheung
 *
 * 移植自 Xime (https://github.com/ximeiorg/xime)，见仓库根 NOTICE.md。
 */
package com.kingzcheung.xime.plugin.core.config

interface PluginConfigStore {
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun remove(key: String)
    fun keys(): Set<String>
}

object NoopPluginConfigStore : PluginConfigStore {
    override fun get(key: String): String? = null
    override fun set(key: String, value: String) {}
    override fun remove(key: String) {}
    override fun keys(): Set<String> = emptySet()
}
