/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 把 JS 语音插件包装成在线识别平台。
 *
 * 语音插件按 AIDL 的 IVoiceInputProvider 契约实现（start/feed/end），
 * 而语音会话层只认 OnlineAsrProvider（start/pushAudio/finish）。
 * 本适配器是两者之间唯一的一层，不关心具体平台。
 */
package org.fcitx.fcitx5.android.data.voice.online

import android.content.Context
import android.os.Bundle
import org.fcitx.fcitx5.android.common.ipc.IVoiceInputCallback
import org.fcitx.fcitx5.android.common.ipc.IVoiceInputProvider
import org.fcitx.fcitx5.android.common.ipc.VoiceInputIpc
import org.fcitx.fcitx5.android.input.voice.js.JsVoiceSettingsStore
import timber.log.Timber

/**
 * 把一个已注册的 JS 语音插件暴露为在线识别平台。
 *
 * @param id 插件 id（如 js:mimo-asr）
 * @param label 展示名
 * @param provider 插件实例
 * @param pluginSettingsId 设置存储的命名空间
 * @param fields 设置页字段；密钥走加密存储，普通项走明文存储
 */
internal class JsPluginOnlineProvider(
    override val id: String,
    private val label: String,
    private val provider: IVoiceInputProvider,
    private val pluginSettingsId: String,
    private val fields: List<JsFieldSpec>,
) : OnlineAsrProvider {

    override val nameRes: Int = org.fcitx.fcitx5.android.R.string.voice_online_provider

    override val labelOverride: String = label

    override val streamsAudio: Boolean = true

    override val streamsText: Boolean = true

    override fun isConfigured(context: Context): Boolean {
        // 字段未解析时不能假定已配置：那会让设置页把「没填密钥」显示成可用，
        // 用户直到开始识别才看到失败。此时按「是否有任一密钥」保守判断。
        if (fields.isEmpty()) {
            return hasAnySecret()
        }
        val required = fields.filter { it.required }
        if (required.isEmpty()) return hasAnySecret()
        return required.all { field ->
            if (field.secret) {
                JsVoiceSettingsStore.hasSecret(pluginSettingsId, field.key)
            } else {
                !JsVoiceSettingsStore.value(pluginSettingsId, field.key).isNullOrBlank()
            }
        }
    }

    /** 该插件是否至少填过一个密钥项（字段未解析时的保守判据）。 */
    private fun hasAnySecret(): Boolean =
        JsVoiceSettingsStore.secretKeys(pluginSettingsId).isNotEmpty()

    override fun settings(): List<OnlineSetting> = fields.map { field ->
        OnlineSetting(
            key = field.key,
            label = field.labelRes,
            labelText = field.labelText,
            kind = field.kind,
            description = field.description,
            secret = field.secret,
            options = field.options,
            read = {
                if (field.secret) "" else JsVoiceSettingsStore.value(pluginSettingsId, field.key).orEmpty()
            },
            write = { value ->
                if (field.secret) {
                    JsVoiceSettingsStore.setSecret(pluginSettingsId, field.key, value)
                    Unit
                } else {
                    JsVoiceSettingsStore.setValue(pluginSettingsId, field.key, value)
                }
            },
        )
    }

    override fun newSession(context: Context, callback: OnlineAsrCallback): OnlineAsrSession =
        BridgeSession(provider, pluginSettingsId, callback)

    /** 插件字段的元信息。 */
    data class JsFieldSpec(
        val key: String,
        val labelRes: Int = 0,
        val labelText: String? = null,
        val kind: String = "text",
        val description: String? = null,
        val secret: Boolean = true,
        val required: Boolean = false,
        val options: List<String> = emptyList(),
    )

    private class BridgeSession(
        private val provider: IVoiceInputProvider,
        private val pluginId: String,
        private val callback: OnlineAsrCallback,
    ) : OnlineAsrSession {

        override fun start() {
            val params = Bundle().apply {
                putInt(VoiceInputIpc.ConfigKeys.SAMPLE_RATE, 16000)
                putInt(VoiceInputIpc.ConfigKeys.BITS_PER_SAMPLE, 16)
                putInt(VoiceInputIpc.ConfigKeys.CHANNELS, 1)
                JsVoiceSettingsStore.values(pluginId).forEach { (k, v) -> putString(k, v) }
            }
            provider.configure(params)
            provider.startSession(object : IVoiceInputCallback.Stub() {
                override fun onReady() = Unit

                override fun onVolumeLevel(rms: Int) = Unit

                override fun onPartialResult(text: String?) {
                    if (!text.isNullOrEmpty()) callback.onPartial(text)
                }

                override fun onSegmentFinal(text: String?) {
                    if (!text.isNullOrEmpty()) callback.onFinal(text)
                }

                override fun onSessionEnded() = Unit

                override fun onError(code: Int, message: String?) {
                    callback.onError(message ?: "语音插件错误（" + code + "）")
                }
            })
        }

        override fun pushAudio(samples: FloatArray) {
            val buf = ByteArray(samples.size * 2)
            var i = 0
            for (s in samples) {
                val v = (s.coerceIn(-1f, 1f) * 32767f).toInt()
                buf[i++] = (v and 0xFF).toByte()
                buf[i++] = ((v shr 8) and 0xFF).toByte()
            }
            runCatching { provider.feedAudio(buf, 0, buf.size, 0L) }
                .onFailure { Timber.w(it, "JS voice plugin feedAudio failed") }
        }

        override fun finish() {
            runCatching { provider.endStream() }
                .onFailure { Timber.w(it, "JS voice plugin endStream failed") }
        }

        override fun cancel() {
            runCatching { provider.cancelSession() }
                .onFailure { Timber.w(it, "JS voice plugin cancelSession failed") }
        }

        override fun release() {
            runCatching { provider.stopSession() }
                .onFailure { Timber.w(it, "JS voice plugin stopSession failed") }
        }
    }
}
