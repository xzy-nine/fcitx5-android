/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Kingz Cheung
 *
 * 移植自 Xime (https://github.com/ximeiorg/xime) 的 settings/KeysConfigHelper.kt，
 * 见仓库根 NOTICE.md。
 *
 * 与上游的差异：上游 KeysConfigHelper 是一个 1300+ 行的键盘/主题配置聚合器；
 * 本移植只需要其中的「模型市场索引地址」（xime_index.base_urls）这一小片，
 * 因此这里只保留该数据模型与合并读取逻辑（读取语义与上游逐字一致：
 * 优先 xime.custom.yaml，其次 xime.yaml，均在 filesDir/rime/ 与 assets 两处查找）。
 * 其余字段（color_schemes / style / metadata）不再建模——kaml 以 strictMode=false
 * 解析，未知字段直接忽略，不影响既有 yaml 的兼容性。
 */
package com.kingzcheung.xime.settings

import android.content.Context
import android.util.Log
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

@Serializable
data class XimeIndexConfig(
    @SerialName("base_urls")
    val baseUrls: List<String> = listOf("https://index.ximei.me/")
)

@Serializable
data class XimeConfig(
    @SerialName("xime_index")
    val ximeIndex: XimeIndexConfig? = null,
)

object KeysConfigHelper {

    private const val TAG = "KeysConfigHelper"
    private const val XIME_CONFIG_FILE = "xime.yaml"
    private const val XIME_CUSTOM_CONFIG_FILE = "xime.custom.yaml"

    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

    /** 模型市场索引地址（默认指向 Xime 官方索引，与上游一致）。 */
    fun loadXimeIndexConfig(context: Context): XimeIndexConfig {
        val merged = loadMergedConfig(context)
        return merged.ximeIndex ?: XimeIndexConfig()
    }

    private fun loadMergedConfig(context: Context): XimeConfig {
        val default = parseConfig(readAssetText(context, XIME_CONFIG_FILE))
        val custom = readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
            ?.let { parseConfig(it) }
            ?: readAssetText(context, XIME_CUSTOM_CONFIG_FILE)
                ?.let { parseConfig(it) }
        return mergeConfig(default, custom)
    }

    private fun parseConfig(content: String?): XimeConfig? {
        if (content == null) return null
        return try {
            yaml.decodeFromString(XimeConfig.serializer(), content)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse xime config", e)
            null
        }
    }

    private fun mergeConfig(default: XimeConfig?, custom: XimeConfig?): XimeConfig {
        if (custom == null) return default ?: XimeConfig()
        if (default == null) return custom
        return XimeConfig(ximeIndex = custom.ximeIndex ?: default.ximeIndex)
    }

    private fun readAssetText(context: Context, fileName: String): String? {
        return try {
            context.assets.open(fileName).use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { it.readText() }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 从用户数据目录 (context.filesDir/rime/) 读取文件。 */
    private fun readUserDataText(context: Context, fileName: String): String? {
        val file = File(context.filesDir, "rime/$fileName")
        if (!file.exists()) return null
        return try {
            file.readText().trimStart('\uFEFF')
        } catch (e: Exception) {
            Log.w(TAG, "readUserDataText: failed", e)
            null
        }
    }
}
