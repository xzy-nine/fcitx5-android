/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 整段（非流式）识别入口：把一段已经确定的 16kHz 音频交给本地离线模型或在线平台，
 * 一次拿到最终文本。
 *
 * 与 [VoiceSession] 的分工：会话层负责「边采集边识别 + 增量上屏」，本文件不碰麦克风、
 * 不产生部分结果，只回答「这段音频被识别成了什么」——预处理后的整段音频就走这一条路。
 *
 * 两条目标各自的协议差异全部收在这里：
 *  - 本地：绑定 `:asr` 进程（模型句柄留在那边复用，空闲超时自动释放），按块 push 后 finish；
 *  - 在线：平台 provider 的会话生命周期（流式平台收尾包、批次平台在 finish 时整段上传），
 *    最终文本由回调给出。
 */
package org.fcitx.fcitx5.android.data.voice

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.fcitx.fcitx5.android.data.voice.online.OnlineAsrCallback
import org.fcitx.fcitx5.android.data.voice.online.OnlineAsrRegistry
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference

/** 识别目标：本地离线模型或某个在线平台。 */
sealed interface VoiceRecognitionTarget {

    /** 本地离线模型（模型 id 取自 `AppPrefs.voice.voiceAsrModelId`）。 */
    data class Local(val modelId: String) : VoiceRecognitionTarget

    /** 在线平台（id 见 [OnlineAsrRegistry]）。 */
    data class Online(val providerId: String) : VoiceRecognitionTarget
}

object VoiceUtteranceRecognizer {

    private const val TAG = "VoiceUtteranceRecognizer"

    /**
     * 在线平台在收尾之后既没成功也没失败时的等待上限。
     *
     * 计时从音频送完（流式平台的实时节奏等待已经结束）开始，因此这里不需要再叠加音频时长。
     */
    private const val ONLINE_RESULT_TIMEOUT_MS = 60_000L

    /** 复用的 `:asr` 连接：连续识别同一个模型时省掉一次绑定与模型加载。 */
    private var localClient: VoiceAsrClient? = null
    private val localClientLock = Any()

    /**
     * 识别 [samples]（16kHz 单声道，[-1,1]）。
     *
     * 失败以 [Result.failure] 返回，异常消息可直接展示给用户。
     */
    suspend fun recognize(
        context: Context,
        target: VoiceRecognitionTarget,
        samples: FloatArray,
    ): Result<String> = try {
        if (samples.isEmpty()) throw IllegalStateException("没有可识别的音频")
        val text = when (target) {
            is VoiceRecognitionTarget.Local -> recognizeLocal(context, target.modelId, samples)
            is VoiceRecognitionTarget.Online -> recognizeOnline(context, target.providerId, samples)
        }
        Result.success(text)
    } catch (e: CancellationException) {
        // 取消不是识别失败：必须继续向上抛，否则协程会在已取消的状态下继续跑
        throw e
    } catch (e: Throwable) {
        Timber.w(e, "$TAG: recognize failed")
        Result.failure(e)
    }

    /** 解绑 `:asr`（调用方销毁时执行；模型句柄由 `:asr` 侧空闲超时释放）。 */
    fun releaseLocal() {
        val client = synchronized(localClientLock) {
            localClient.also { localClient = null }
        }
        client?.unbind()
    }

    /** 取（或建立）共用的 `:asr` 连接；失败时不留半成品引用。 */
    private suspend fun localClient(context: Context): VoiceAsrClient {
        synchronized(localClientLock) { localClient }?.let { return it }
        val client = VoiceAsrClient(context.applicationContext)
        if (!client.ensureBound()) {
            throw IllegalStateException("无法连接离线识别服务（:asr）")
        }
        // 并发首次绑定：只保留先建立的那一个连接，本次多出来的当场解绑
        val winner = synchronized(localClientLock) {
            localClient ?: run { localClient = client; null }
        }
        if (winner != null) {
            client.unbind()
            return winner
        }
        return client
    }

    private suspend fun recognizeLocal(
        context: Context,
        modelId: String,
        samples: FloatArray,
    ): String {
        // 家族判定要扫 onnx 头部区域，只能在 IO 线程做
        val spec = withContext(Dispatchers.IO) { VoiceModelStore.resolve(context, modelId) }
            ?: throw IllegalStateException(localUnavailableMessage(context, modelId))
        val client = localClient(context)
        val failure = AtomicReference<String?>(null)
        val started = client.start(
            spec,
            object : VoiceAsrClient.Callback {
                // 整段识别只取最终文本，部分结果没有上屏对象
                override fun onPartial(text: String) = Unit

                override fun onError(message: String) {
                    failure.set(message)
                }
            },
        )
        if (!started) throw IllegalStateException("离线语音模型加载失败")
        // push 是同步 IPC：放在 IO 线程上，整段音频的送入不会卡住调用方（可能的主线程）
        withContext(Dispatchers.IO) { samples.forEachPushChunk { client.push(it) } }
        val text = client.finish()
        if (text.isBlank()) failure.get()?.let { throw IllegalStateException(it) }
        return text
    }

    private suspend fun recognizeOnline(
        context: Context,
        providerId: String,
        samples: FloatArray,
    ): String = withContext(Dispatchers.IO) {
        val provider = OnlineAsrRegistry.byId(providerId)
            ?: throw IllegalStateException("未知的在线识别平台")
        if (!provider.isConfigured(context)) {
            throw IllegalStateException("在线识别平台未配置：${context.getString(provider.nameRes)}")
        }
        val done = CompletableDeferred<String>()
        val session = provider.newSession(
            context,
            object : OnlineAsrCallback {
                override fun onPartial(text: String) = Unit

                override fun onFinal(text: String) {
                    done.complete(text)
                }

                override fun onError(message: String) {
                    done.completeExceptionally(IllegalStateException(message))
                }
            },
        )
        try {
            session.start()
            // 流式平台按实时节奏送音频：一次性灌入整段会让服务端的静音检测/断句与真实说话
            // 完全不同（可能整段被当成一个超长语音或直接被丢弃），结果不能代表实际识别效果。
            // 批次平台（streamsAudio = false）内部只累积，松手时整段上传，不需要节奏。
            if (provider.streamsAudio) {
                var offset = 0
                while (offset < samples.size) {
                    val end = (offset + PUSH_CHUNK_SAMPLES).coerceAtMost(samples.size)
                    session.pushAudio(samples.copyOfRange(offset, end))
                    offset = end
                    // 最后一块之后不必再等：收尾包紧随其后发出
                    if (offset < samples.size) delay(PUSH_CHUNK_MS)
                }
            } else {
                samples.forEachPushChunk { session.pushAudio(it) }
            }
            session.finish()
            // 计时从收尾之后开始：实时送音频的等待已经结束，这里只等结果本身
            withTimeoutOrNull(ONLINE_RESULT_TIMEOUT_MS) { done.await() }
                ?: throw IllegalStateException("在线识别超时")
        } finally {
            runCatching { session.release() }
        }
    }

    /** 本地模型不可用的原因（区分「没下载」与「下载了但家族判不出」）。 */
    private suspend fun localUnavailableMessage(context: Context, modelId: String): String =
        when (withContext(Dispatchers.IO) { VoiceModelStore.resolveDetailed(context, modelId) }) {
            // 文件在，但架构无法从模型自身还原（sherpa 侧需用户显式指定配置）
            is VoiceModelClassifier.Result.Unknown -> "该模型的架构无法自动识别，暂不支持"

            else -> "离线语音模型不可用，请先到模型市场下载或导入"
        }
}
