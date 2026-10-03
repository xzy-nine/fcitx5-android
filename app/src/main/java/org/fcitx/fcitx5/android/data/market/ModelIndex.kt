/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 模型市场的远程索引。
 *
 * 拉取 `<base>/models/index.yaml`，由调用方给出 **category**（分类注册表见
 * [MarketCategories]）；索引不可用时由各分类回落到自己的内置清单。
 */
package org.fcitx.fcitx5.android.data.market

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit

object ModelIndex {

    private const val TAG = "ModelIndex"
    private const val DEFAULT_BASE_URL = "https://index.ximei.me/"
    private const val INDEX_PATH = "models/index.yaml"

    /** 分类 id（= 索引里的 `category` 字段值）。 */
    const val CATEGORY_ASR = "asr"

    /**
     * 数字墨水分类 id：**没有远程索引**（清单是官方语言表，内置在
     * `DigitalInkModelCatalog`），仅用于路由与注册表。
     */
    const val CATEGORY_DIGITAL_INK = "digitalink"

    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** 索引地址：分类给出的覆盖值 → 内置默认端点。 */
    private fun baseUrl(context: android.content.Context, category: String): String {
        val override = runCatching {
            MarketCategories.of(category).indexBaseUrlOverride(context)
        }.getOrNull().orEmpty()
        return override.ifBlank { DEFAULT_BASE_URL }.trimEnd('/')
    }

    /** 拉取并解析指定分类的条目；失败返回空列表（调用方据此回落内置清单）。 */
    suspend fun load(context: android.content.Context, category: String): List<MarketModel> =
        withContext(Dispatchers.IO) {
            val url = "${baseUrl(context, category)}/$INDEX_PATH"
            val text = runCatching { fetch(url) }.getOrElse {
                Timber.w(it, "$TAG: fetch failed")
                null
            }
            text?.let {
                runCatching { parse(it, category) }.getOrElse { e ->
                    Timber.w(e, "$TAG: parse failed")
                    emptyList()
                }
            }.orEmpty()
        }

    private fun fetch(url: String): String? {
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        response.use {
            if (!it.isSuccessful) {
                Timber.w("$TAG: HTTP ${it.code} for $url")
                return null
            }
            return it.body?.string()?.takeIf { s -> s.isNotBlank() }
        }
    }

    /** 解析 `models:` 列表，只保留 `category` 匹配的条目。 */
    internal fun parse(text: String, category: String): List<MarketModel> {
        val root = yaml.parseToYamlNode(text) as? YamlMap ?: return emptyList()
        val models = root["models"] as? YamlList ?: return emptyList()
        return models.items.mapNotNull { node ->
            val map = node as? YamlMap ?: return@mapNotNull null
            val id = (map["id"] as? YamlScalar)?.content ?: return@mapNotNull null
            val entryCategory = (map["category"] as? YamlScalar)?.content.orEmpty().lowercase()
            if (entryCategory != category.lowercase()) return@mapNotNull null
            // 非法 id（空、`.`/`..`、含路径分隔符）直接剔除，不进市场页
            if (!MarketModelId.isValid(id)) {
                Timber.w("$TAG: skip model with illegal id: $id")
                return@mapNotNull null
            }
            val name = (map["name"] as? YamlScalar)?.content ?: id

            val versions = map["versions"] as? YamlList
            val version = versions?.items?.firstOrNull() as? YamlMap
            val versionLabel = (version?.get("version") as? YamlScalar)?.content
                ?: (version?.get("name") as? YamlScalar)?.content.orEmpty()
            val versionSize = (version?.get("size") as? YamlScalar)?.content.orEmpty()
            val archiveUrl = (version?.get("archive") as? YamlMap)
                ?.let { (it["url"] as? YamlScalar)?.content }
            val files = (version?.get("files") as? YamlList)?.items.orEmpty().mapNotNull { f ->
                val fm = f as? YamlMap ?: return@mapNotNull null
                val fileName = (fm["name"] as? YamlScalar)?.content ?: return@mapNotNull null
                val fileUrl = (fm["url"] as? YamlScalar)?.content.orEmpty()
                MarketModelFile(
                    name = fileName,
                    url = fileUrl,
                    sha256 = (fm["sha256"] as? YamlScalar)?.content.orEmpty().lowercase(),
                )
            }
            MarketModel(
                id = id,
                name = name,
                description = (map["description"] as? YamlScalar)?.content.orEmpty(),
                size = (map["size"] as? YamlScalar)?.content.orEmpty().ifBlank { versionSize },
                version = versionLabel,
                archiveUrl = archiveUrl,
                files = files,
            )
        }
    }
}
