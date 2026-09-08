/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core.data

import org.fcitx.fcitx5.android.core.data.PluginDescriptor.Companion.pluginPackagePrefix

/**
 * Metadata of a plugin, at `res/xml/plugin.xml`
 */
data class PluginDescriptor(
    /**
     * Must have [pluginPackagePrefix] prefix
     *
     * custom: 不再区分 debug/release 包名后缀，插件包名统一为 prefix + 插件名
     */
    val packageName: String,
    /**
     * For future incompatible updates
     */
    val apiVersion: String,
    /**
     * May provide gettext domain
     */
    val domain: String?,
    /**
     * Can use string resource, e.g. `@string/description`
     */
    val description: String,
    /**
     * Contains IPC service with action `${mainApplicationId}.plugin.SERVICE`. Default to `false`.
     */
    val hasService: Boolean,
    val versionName: String,
    val nativeLibraryDir: String
) {
    val name = packageName.removePrefix(pluginPackagePrefix)

    companion object {
        const val pluginPackagePrefix = "org.fcitx.fcitx5.android.plugin."
        const val pluginAPI = "0.1"
    }
}