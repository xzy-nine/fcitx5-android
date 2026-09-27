/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 小米 MiMo 语音识别（`mimo-v2.5-asr`）。
 *
 * 协议依据平台官方文档（Speech Recognition / OpenAI API Compatibility）：
 *   POST https://api.xiaomimimo.com/v1/chat/completions
 *   header: `api-key: <KEY>`（或 `Authorization: Bearer <KEY>`）
 *   body  : OpenAI Chat Completions；音频以 data URL（`data:audio/wav;base64,...`）整段传入，
 *           仅支持 wav/mp3，base64 后 ≤10MB；`asr_options.language` ∈ auto|zh|en；
 *           `stream: true` 时结果文本以 SSE（chat.completion.chunk 的 delta.content）增量返回。
 *
 * 因此本平台属于「批次音频 + 流式文本」：说话过程中不发送音频，松手后把整段 PCM 包成 WAV
 * 上传，随后用 SSE 把文本逐段吐给面板（比一次性返回体验好，但仍无说话中的实时出字）。
 */
package org.fcitx.fcitx5.android.data.voice.online

import android.content.Context
import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object MiMoAsrProvider : OnlineAsrProvider {

    const val PROVIDER_ID = "mimo"

    private const val ENDPOINT = "https://api.xiaomimimo.com/v1/chat/completions"
    private const val MODEL = "mimo-v2.5-asr"
    private const val SAMPLE_RATE = 16000

    /** base64 上限 10MB ≈ 7.5MB 原始音频；留一点余量给 WAV 头与 JSON 包装。 */
    private const val MAX_PCM_BYTES = 7_000_000

    override val id: String = PROVIDER_ID
    override val nameRes: Int = R.string.voice_provider_mimo
    override val streamsAudio: Boolean = false
    override val streamsText: Boolean = true

    fun apiKey(): String = AppPrefs.getInstance().voice.voiceMiMoApiKey.getValue()

    fun language(): String =
        AppPrefs.getInstance().voice.voiceMiMoLanguage.getValue().ifBlank { "auto" }

    override fun isConfigured(context: Context): Boolean = apiKey().isNotBlank()

    override fun settings(): List<OnlineSetting> {
        val prefs = AppPrefs.getInstance().voice
        return listOf(
            OnlineSetting(
                key = "apiKey",
                label = R.string.voice_mimo_api_key,
                secret = true,
                read = { prefs.voiceMiMoApiKey.getValue() },
                write = { prefs.voiceMiMoApiKey.setValue(it) },
            ),
            OnlineSetting(
                key = "language",
                label = R.string.voice_mimo_language,
                secret = false,
                options = listOf("auto", "zh", "en"),
                read = { language() },
                write = { prefs.voiceMiMoLanguage.setValue(it) },
            ),
        )
    }

    override fun newSession(context: Context, callback: OnlineAsrCallback): OnlineAsrSession =
        MiMoSession(callback)

    // ---- 会话 ----

    private class MiMoSession(private val callback: OnlineAsrCallback) : OnlineAsrSession {

        private val pcm = ByteArrayOutputStream()
        private val done = AtomicBoolean(false)
        private var source: EventSource? = null
        private var call: Call? = null
        private val text = StringBuilder()

        override fun start() {
            text.clear()
            pcm.reset()
            done.set(false)
        }

        override fun pushAudio(samples: FloatArray) {
            // 批次平台：只累积，松手时才上传（超过上限则丢弃最旧的部分无法做到——直接截断尾部）
            if (pcm.size() >= MAX_PCM_BYTES) return
            for (s in samples) {
                val v = (s.coerceIn(-1f, 1f) * 32767f).toInt()
                pcm.write(v and 0xFF)
                pcm.write((v shr 8) and 0xFF)
            }
        }

        override fun finish() {
            if (!done.compareAndSet(false, true)) return
            val key = apiKey()
            if (key.isBlank()) {
                callback.onError("MiMo API Key 未配置")
                return
            }
            val wav = pcm.toByteArray().let { wrapWav(it) }
            if (wav.size <= 44) {
                callback.onFinal("")
                return
            }
            val dataUrl = "data:audio/wav;base64," +
                    Base64.encodeToString(wav, Base64.NO_WRAP)
            val body = buildJsonObject {
                put("model", MODEL)
                put("stream", true)
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "input_audio")
                                put("input_audio", buildJsonObject { put("data", dataUrl) })
                            })
                        })
                    })
                })
                put("asr_options", buildJsonObject { put("language", language()) })
            }.toString()

            val request = Request.Builder()
                .url(ENDPOINT)
                .addHeader("api-key", key)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            source = EventSources.createFactory(httpClient()).newEventSource(
                request,
                object : EventSourceListener() {
                    override fun onEvent(
                        eventSource: EventSource,
                        id: String?,
                        type: String?,
                        data: String,
                    ) {
                        if (data.isBlank() || data == "[DONE]") return
                        val delta = runCatching {
                            Json.parseToJsonElement(data).jsonObject["choices"]
                                ?.jsonArray?.firstOrNull()
                                ?.jsonObject?.get("delta")
                                ?.jsonObject?.get("content")
                                ?.jsonPrimitive?.contentOrNull
                        }.getOrNull()
                        if (!delta.isNullOrEmpty()) {
                            text.append(delta)
                            callback.onPartial(text.toString())
                        }
                    }

                    override fun onClosed(eventSource: EventSource) {
                        callback.onFinal(text.toString())
                    }

                    override fun onFailure(
                        eventSource: EventSource,
                        t: Throwable?,
                        response: Response?,
                    ) {
                        val detail = t?.message
                            ?: response?.let { "HTTP ${it.code}" }
                            ?: "未知错误"
                        Timber.w("MiMoAsr: $detail")
                        callback.onError("MiMo 识别失败：$detail")
                    }
                },
            )
        }

        override fun cancel() {
            done.set(true)
            source?.cancel()
            source = null
            call?.cancel()
            call = null
        }

        override fun release() = cancel()

        private fun wrapWav(pcmBytes: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(pcmBytes.size + 44)
            val dataLen = pcmBytes.size
            val byteRate = SAMPLE_RATE * 2
            fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
            fun le32(v: Int) {
                out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
                out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
            }
            fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }

            ascii("RIFF"); le32(36 + dataLen); ascii("WAVE")
            ascii("fmt "); le32(16); le16(1); le16(1)
            le32(SAMPLE_RATE); le32(byteRate); le16(2); le16(16)
            ascii("data"); le32(dataLen)
            out.write(pcmBytes)
            return out.toByteArray()
        }
    }

    private var client: OkHttpClient? = null

    private fun httpClient(): OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // 整段音频上传 + 识别，读超时给足
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()
        .also { client = it }
}
