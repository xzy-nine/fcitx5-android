/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 本地语音模型的远程索引（YAML）。
 *
 * 索引地址：`AppPrefs.voice.voiceIndexUrl` 优先，否则用 [DEFAULT_BASE_URL]；
 * 拉取 `<base>/models/index.yaml`，只取 `category: asr` 的条目。
 * 索引不可用时回落到 [VoiceModelCatalog.builtin]（官方 release 资产），
 * 保证市场页永远有内容可选、且不依赖第三方索引服务。
 */
package org.fcitx.fcitx5.android.data.voice

import android.content.Context
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

object VoiceModelIndex {

    private const val TAG = "VoiceModelIndex"
    private const val DEFAULT_BASE_URL = "https://index.ximei.me/"
    private const val INDEX_PATH = "models/index.yaml"

    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun baseUrl(): String {
        val override = runCatching {
            AppPrefs.getInstance().voice.voiceIndexUrl.getValue()
        }.getOrDefault("")
        return override.ifBlank { DEFAULT_BASE_URL }.trimEnd('/')
    }

    /** 拉取并解析索引；失败或空则返回内置清单。 */
    suspend fun load(context: Context): List<VoiceModelInfo> = withContext(Dispatchers.IO) {
        val url = "${baseUrl()}/$INDEX_PATH"
        val text = runCatching { fetch(url) }.getOrElse {
            Timber.w(it, "$TAG: fetch failed")
            null
        }
        val parsed = text?.let { runCatching { parse(it) }.getOrElse { e ->
            Timber.w(e, "$TAG: parse failed")
            emptyList()
        } }.orEmpty()
        if (parsed.isNotEmpty()) parsed else VoiceModelCatalog.builtin
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

    /** 解析 `models:` 列表，只保留 `category: asr`。 */
    internal fun parse(text: String): List<VoiceModelInfo> {
        val root = yaml.parseToYamlNode(text) as? YamlMap ?: return emptyList()
        val models = root["models"] as? YamlList ?: return emptyList()
        return models.items.mapNotNull { node ->
            val map = node as? YamlMap ?: return@mapNotNull null
            val id = (map["id"] as? YamlScalar)?.content ?: return@mapNotNull null
            val name = (map["name"] as? YamlScalar)?.content ?: id
            val category = (map["category"] as? YamlScalar)?.content.orEmpty().lowercase()
            if (category != "asr") return@mapNotNull null

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
                VoiceModelFile(fileName, fileUrl)
            }
            VoiceModelInfo(
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
