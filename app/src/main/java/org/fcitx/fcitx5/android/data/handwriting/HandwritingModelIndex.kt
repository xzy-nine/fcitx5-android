/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 手写模型的远程索引读取。
 *
 * 与语音共用同一个索引地址（`AppPrefs.voice.voiceIndexUrl` 优先，否则 xime 默认端点），
 * 拉取 `<base>/models/index.yaml` 后**只取 `category: handwriting` 的条目** ——
 * 这就是「模型市场按 category 分流语音 / 手写」的落点：
 * `VoiceModelIndex` 取 `asr`，本对象取 `handwriting`，两者共用同一份索引与下载器。
 *
 * 索引不可用时回落到 [HandwritingModelCatalog.builtin]。
 */
package org.fcitx.fcitx5.android.data.handwriting

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import timber.log.Timber
import java.util.concurrent.TimeUnit

object HandwritingModelIndex {

    private const val TAG = "HandwritingModelIndex"
    private const val DEFAULT_BASE_URL = "https://index.ximei.me/"
    private const val INDEX_PATH = "models/index.yaml"

    /** 索引里代表手写模型的 category 值。 */
    const val CATEGORY = "handwriting"

    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * 索引地址：手写自己的覆盖值优先，其次沿用语音的覆盖值，最后用默认端点。
     * （绝大多数用户只需要配一次端点，两个分类共用。）
     */
    private fun baseUrl(): String {
        val prefs = runCatching { AppPrefs.getInstance() }.getOrNull()
        val own = runCatching { prefs?.handwriting?.handwritingIndexUrl?.getValue() }
            .getOrNull().orEmpty()
        val shared = runCatching { prefs?.voice?.voiceIndexUrl?.getValue() }
            .getOrNull().orEmpty()
        return own.ifBlank { shared }.ifBlank { DEFAULT_BASE_URL }.trimEnd('/')
    }

    /** 拉取并解析索引；失败或空则返回内置清单。 */
    suspend fun load(context: android.content.Context): List<HandwritingModelInfo> =
        withContext(Dispatchers.IO) {
            val url = "${baseUrl()}/$INDEX_PATH"
            val text = runCatching { fetch(url) }.getOrElse {
                Timber.w(it, "$TAG: fetch failed")
                null
            }
            val parsed = text?.let {
                runCatching { parse(it) }.getOrElse { e ->
                    Timber.w(e, "$TAG: parse failed")
                    emptyList()
                }
            }.orEmpty()
            if (parsed.isNotEmpty()) parsed else HandwritingModelCatalog.builtin
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

    /** 解析 `models:` 列表，只保留 `category: handwriting`。 */
    internal fun parse(text: String): List<HandwritingModelInfo> {
        val root = yaml.parseToYamlNode(text) as? YamlMap ?: return emptyList()
        val models = root["models"] as? YamlList ?: return emptyList()
        return models.items.mapNotNull { node ->
            val map = node as? YamlMap ?: return@mapNotNull null
            val id = (map["id"] as? YamlScalar)?.content ?: return@mapNotNull null
            val name = (map["name"] as? YamlScalar)?.content ?: id
            val category = (map["category"] as? YamlScalar)?.content.orEmpty().lowercase()
            if (category != CATEGORY) return@mapNotNull null

            val versions = map["versions"] as? YamlList
            val version = versions?.items?.firstOrNull() as? YamlMap
            val versionLabel = (version?.get("version") as? YamlScalar)?.content
                ?: (version?.get("name") as? YamlScalar)?.content.orEmpty()
            val versionSize = (version?.get("size") as? YamlScalar)?.content.orEmpty()
            val files = (version?.get("files") as? YamlList)?.items.orEmpty().mapNotNull { f ->
                val fm = f as? YamlMap ?: return@mapNotNull null
                val fileName = (fm["name"] as? YamlScalar)?.content ?: return@mapNotNull null
                val fileUrl = (fm["url"] as? YamlScalar)?.content.orEmpty()
                if (fileUrl.isBlank()) return@mapNotNull null
                HandwritingModelFile(
                    name = fileName,
                    url = fileUrl,
                    sha256 = (fm["sha256"] as? YamlScalar)?.content.orEmpty().lowercase(),
                )
            }
            if (files.isEmpty()) return@mapNotNull null

            HandwritingModelInfo(
                id = id,
                name = name,
                description = (map["description"] as? YamlScalar)?.content.orEmpty(),
                size = (map["size"] as? YamlScalar)?.content.orEmpty().ifBlank { versionSize },
                version = versionLabel,
                files = files,
            )
        }
    }
}
