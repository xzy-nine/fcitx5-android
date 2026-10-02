/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 火山引擎（火山方舟）大模型流式语音识别。
 *
 * 协议依据平台官方文档（sauc websocket 二进制协议，官方示例 sauc_websocket_demo.py）：
 *   帧 = 4 字节头 + 4 字节大端有符号 seq + 4 字节大端 payload 长度 + payload
 *     头[0] = (协议版本 0x1 << 4) | 头长度单位 0x1      → 0x11
 *     头[1] = (消息类型 << 4) | flags
 *     头[2] = (序列化 << 4) | 压缩                     → JSON=0x1, gzip=0x1
 *     头[3] = 0
 *   消息类型：0x1 完整客户端请求 / 0x2 纯音频 / 0x9 服务端响应 / 0xF 错误
 *   flags   ：0x0 无 seq / 0x1 带正 seq / 0x3 末包（seq 取负）
 *   服务端响应 flags：0x1 带 seq / 0x2 末包 / 0x4 带 event（event 在 payload 前，4 字节）
 *   压缩    ：0x0 无 / 0x1 gzip（请求与响应都可能 gzip）
 *
 * 音频：16kHz / 单声道 / PCM16，边说边发（[streamsAudio] = true）。
 */
package org.fcitx.fcitx5.android.data.voice.online

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object VolcengineAsrProvider : OnlineAsrProvider {

    const val PROVIDER_ID = "volcengine"

    private const val ENDPOINT = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
    private const val DEFAULT_RESOURCE = "volc.seedasr.sauc.duration"

    // 消息类型 / flags（见文件头协议说明）
    private const val MSG_FULL_CLIENT_REQUEST = 0x1
    private const val MSG_AUDIO_ONLY = 0x2
    private const val MSG_SERVER_RESPONSE = 0x9
    private const val MSG_SERVER_ERROR = 0xF

    private const val FLAG_POS_SEQUENCE = 0x1
    private const val FLAG_NEG_WITH_SEQUENCE = 0x3
    private const val FLAG_RESP_IS_LAST = 0x2
    private const val FLAG_RESP_HAS_EVENT = 0x4

    private const val COMPRESSION_GZIP = 0x1
    private const val SERIALIZATION_JSON = 0x1

    override val id: String = PROVIDER_ID
    override val nameRes: Int = R.string.voice_provider_volcengine
    override val streamsAudio: Boolean = true
    override val streamsText: Boolean = true

    private fun prefs() = AppPrefs.getInstance().voice

    fun apiKey(): String = prefs().voiceVolcApiKey.getValue()
    fun appKey(): String = prefs().voiceVolcAppKey.getValue()
    fun accessKey(): String = prefs().voiceVolcAccessKey.getValue()
    fun resourceId(): String = prefs().voiceVolcResourceId.getValue().ifBlank { DEFAULT_RESOURCE }

    override fun isConfigured(context: Context): Boolean =
        apiKey().isNotBlank() || (appKey().isNotBlank() && accessKey().isNotBlank())

    override fun settings(): List<OnlineSetting> = listOf(
        OnlineSetting(
            key = "apiKey",
            label = R.string.voice_volc_api_key,
            secret = true,
            read = { apiKey() },
            write = { prefs().voiceVolcApiKey.setValue(it) },
        ),
        OnlineSetting(
            key = "appKey",
            label = R.string.voice_volc_app_key,
            secret = true,
            read = { appKey() },
            write = { prefs().voiceVolcAppKey.setValue(it) },
        ),
        OnlineSetting(
            key = "accessKey",
            label = R.string.voice_volc_access_key,
            secret = true,
            read = { accessKey() },
            write = { prefs().voiceVolcAccessKey.setValue(it) },
        ),
        OnlineSetting(
            key = "resourceId",
            label = R.string.voice_volc_resource_id,
            secret = false,
            read = { resourceId() },
            write = { prefs().voiceVolcResourceId.setValue(it) },
        ),
    )

    override fun newSession(context: Context, callback: OnlineAsrCallback): OnlineAsrSession =
        VolcengineSession(callback)

    // ---- 会话 ----

    private class VolcengineSession(private val callback: OnlineAsrCallback) : OnlineAsrSession {

        private val done = AtomicBoolean(false)
        private var socket: WebSocket? = null
        private var seq = 1
        private var text = ""

        override fun start() {
            seq = 1
            text = ""
            done.set(false)
            val request = Request.Builder()
                .url(ENDPOINT)
                .apply {
                    if (apiKey().isNotBlank()) {
                        addHeader("X-Api-Key", apiKey())
                    } else {
                        addHeader("X-Api-App-Key", appKey())
                        addHeader("X-Api-Access-Key", accessKey())
                    }
                    addHeader("X-Api-Resource-Id", resourceId())
                    addHeader("X-Api-Connect-Id", UUID.randomUUID().toString())
                    addHeader("X-Api-Request-Id", UUID.randomUUID().toString())
                }
                .build()
            // 请求帧在 start() 内立即入队（seq=1）：既保证 start 返回后 pushAudio 的音频帧
            // 能拿到后续序号（start 里同步分配 seq，不会与 onOpen 异步时序竞争），
            // OkHttp WebSocket 也会在握手完成后才真正写出该帧
            socket = httpClient().newWebSocket(request, object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    handleServerFrame(bytes)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    // 该协议只用二进制帧；文本帧忽略
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (done.get()) return
                    Timber.w(t, "VolcengineAsr: socket failure")
                    callback.onError("火山引擎识别失败：${t.message ?: "连接中断"}")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!done.getAndSet(true)) callback.onFinal(text)
                }
            })
            // newWebSocket 只是入队，此时 sock 尚未握手；把请求帧立刻 send 进队列，
            // 它会在握手完成后作为第一条消息写出（OkHttp 保证顺序）
            socket?.send(fullClientRequestFrame().toByteString())
        }

        override fun pushAudio(samples: FloatArray) {
            val ws = socket ?: return
            if (done.get()) return
            val pcm = Buffer()
            for (s in samples) {
                val v = (s.coerceIn(-1f, 1f) * 32767f).toInt()
                pcm.writeByte(v and 0xFF)
                pcm.writeByte((v shr 8) and 0xFF)
            }
            ws.send(audioFrame(pcm.readByteArray()).toByteString())
        }

        override fun finish() {
            val ws = socket ?: return
            if (done.get()) return
            ws.send(lastAudioFrame(ByteArray(0)).toByteString())
        }

        override fun cancel() {
            done.set(true)
            socket?.close(1000, "cancel")
            socket = null
        }

        override fun release() = cancel()

        // ---- 组帧 ----

        private fun fullClientRequestFrame(): ByteArray {
            val payload = buildJsonObject {
                put("user", buildJsonObject { put("uid", UUID.randomUUID().toString()) })
                put("audio", buildJsonObject {
                    put("format", "pcm")
                    put("rate", 16000)
                    put("bits", 16)
                    put("channel", 1)
                    put("codec", "raw")
                    put("language", "zh-CN")
                })
                put("request", buildJsonObject {
                    put("model_name", "bigmodel")
                    put("enable_punc", true)
                    put("result_type", "single")
                    put("show_utterances", true)
                })
            }.toString().toByteArray(Charsets.UTF_8)
            return frame(MSG_FULL_CLIENT_REQUEST, FLAG_POS_SEQUENCE, gzip(payload), seq++)
        }

        private fun audioFrame(pcm: ByteArray): ByteArray =
            frame(MSG_AUDIO_ONLY, FLAG_POS_SEQUENCE, gzip(pcm), seq++)

        private fun lastAudioFrame(pcm: ByteArray): ByteArray =
            frame(MSG_AUDIO_ONLY, FLAG_NEG_WITH_SEQUENCE, gzip(pcm), -seq)

        private fun frame(type: Int, flags: Int, payload: ByteArray, seq: Int): ByteArray {
            val out = ByteArrayOutputStream(payload.size + 12)
            out.write(0x11) // 协议版本 1 + 头长度 4 字节
            out.write((type shl 4) or flags)
            out.write((SERIALIZATION_JSON shl 4) or COMPRESSION_GZIP)
            out.write(0x00)
            writeInt32BE(out, seq)
            writeUint32BE(out, payload.size)
            out.write(payload)
            return out.toByteArray()
        }

        // ---- 解析服务端帧 ----

        private fun handleServerFrame(bytes: ByteString) {
            val data = bytes.toByteArray()
            if (data.size < 4) return
            val headerSize = data[0].toInt() and 0x0F
            val type = (data[1].toInt() shr 4) and 0x0F
            val flags = data[1].toInt() and 0x0F
            val compression = data[2].toInt() and 0x0F
            val serialization = (data[2].toInt() shr 4) and 0x0F
            // 头[0] 低 4 位是「4 字节单位」的头长度（0x1 = 4 字节），不是字节偏移
            val headerBytes = headerSize * 4
            if (headerBytes < 4 || headerBytes > data.size) return
            var offset = headerBytes

            fun need(n: Int) = offset + n <= data.size

            var event: Int? = null
            if (flags and FLAG_RESP_HAS_EVENT != 0) {
                if (!need(4)) return
                event = readInt32BE(data, offset)
                offset += 4
            }
            if (type == MSG_SERVER_ERROR) {
                val code = if (need(4)) readInt32BE(data, offset).also { offset += 4 } else 0
                val msg = if (need(4)) {
                    val len = readUint32BE(data, offset).also { offset += 4 }
                    if (len >= 0 && len <= data.size - offset) {
                        String(data, offset, len, Charsets.UTF_8)
                    } else ""
                } else ""
                if (!done.get()) callback.onError("火山引擎错误 $code：$msg")
                return
            }
            if (type != MSG_SERVER_RESPONSE) return
            // 末包状态：flags 位或 event==200 都表示识别结束；末包也可能携带
            // seq 与 payload（含最终识别 JSON），不能在解析 payload 前提前返回
            val isLast = flags and FLAG_RESP_IS_LAST != 0 || event == 200
            if (flags and FLAG_POS_SEQUENCE != 0 || flags and FLAG_NEG_WITH_SEQUENCE != 0) {
                // 服务端帧带 4 字节序列号，位于 payload 长度之前
                if (!need(4)) return
                offset += 4
            }
            var hasPayload = need(4)
            val payloadLen = if (hasPayload) readUint32BE(data, offset) else 0
            if (hasPayload) {
                offset += 4
                // 用减法比较避免 offset+payloadLen 溢出；负值长度也一律拒绝
                hasPayload = payloadLen >= 0 && payloadLen <= data.size - offset
            }
            if (hasPayload) {
                var payload = data.copyOfRange(offset, offset + payloadLen)
                if (compression == COMPRESSION_GZIP) {
                    payload = runCatching { gunzip(payload) }.getOrNull()
                        ?: return if (isLast) deliverFinal() else Unit
                }
                if (serialization == SERIALIZATION_JSON) {
                    val json = runCatching {
                        Json.parseToJsonElement(String(payload, Charsets.UTF_8)).jsonObject
                    }.getOrNull() ?: return if (isLast) deliverFinal() else Unit

                    // 老协议：result[0].text；新协议：result.text（含 utterances 分句）
                    val resultNode = json["result"]
                    val textNow = runCatching {
                        when {
                            resultNode == null -> null
                            resultNode is kotlinx.serialization.json.JsonArray ->
                                resultNode.firstOrNull()?.jsonObject?.get("text")
                                    ?.jsonPrimitive?.contentOrNull

                            else -> resultNode.jsonObject["text"]?.jsonPrimitive?.contentOrNull
                                ?: resultNode.jsonObject["utterances"]?.jsonArray
                                    ?.mapNotNull {
                                        it.jsonObject["text"]?.jsonPrimitive?.contentOrNull
                                    }?.joinToString("")
                        }
                    }.getOrNull()

                    if (!textNow.isNullOrEmpty() && textNow != text) {
                        text = textNow
                        callback.onPartial(text)
                    }
                }
            }
            if (isLast) deliverFinal()
        }

        private fun deliverFinal() {
            if (done.compareAndSet(false, true)) callback.onFinal(text)
        }
    }

    // ---- 工具 ----

    private fun writeInt32BE(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 0xFF); out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF); out.write(v and 0xFF)
    }

    private fun writeUint32BE(out: ByteArrayOutputStream, v: Int) = writeInt32BE(out, v)

    private fun readInt32BE(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)

    private fun readUint32BE(data: ByteArray, offset: Int): Int = readInt32BE(data, offset)

    private fun gzip(input: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(input) }
        return out.toByteArray()
    }

    private fun gunzip(input: ByteArray): ByteArray =
        GZIPInputStream(input.inputStream()).use { it.readBytes() }

    private var client: OkHttpClient? = null

    private fun httpClient(): OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket 长连接
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
        .also { client = it }
}
