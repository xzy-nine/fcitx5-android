/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 * custom: 本地离线语音识别服务，运行在 :asr 独立进程。
 */
package org.fcitx.fcitx5.android.data.voice

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

class VoiceAsrService : Service() {

    companion object {
        private const val TAG = "VoiceAsrService"

        /** 会话结束后多久无新会话即释放模型句柄。 */
        private const val IDLE_RELEASE_DELAY_MS = 60_000L

        /** 采样率固定 16kHz（模型训练口径）。 */
        const val SAMPLE_RATE = 16000
    }

    private val lock = Any()
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var callback: IVoiceAsrCallback? = null

    /** 当前 recognizer 对应的模型文件路径（持锁读写），用于检测模型切换。 */
    private var enginePaths: List<String> = emptyList()

    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleReleaseRunnable = Runnable { releaseEngine() }

    private fun scheduleIdleRelease() {
        idleHandler.removeCallbacks(idleReleaseRunnable)
        idleHandler.postDelayed(idleReleaseRunnable, IDLE_RELEASE_DELAY_MS)
    }

    private fun cancelIdleRelease() = idleHandler.removeCallbacks(idleReleaseRunnable)

    private val binder = object : IVoiceAsrService.Stub() {

        override fun startAsr(
            encoder: String,
            decoder: String,
            joiner: String,
            tokens: String,
            cb: IVoiceAsrCallback?,
        ): Boolean {
            cancelIdleRelease()
            val paths = listOf(encoder, decoder, joiner, tokens)
            val ok = synchronized(lock) {
                if (recognizer != null && enginePaths != paths) {
                    // 模型已切换：释放旧 stream 与 recognizer，再用新路径重建
                    Log.i(TAG, "model changed, rebuilding engine: $encoder")
                    releaseEngineLocked()
                }
                if (recognizer == null && !createEngine(encoder, decoder, joiner, tokens)) {
                    return false
                }
                callback = cb
                stream?.release()
                stream = recognizer?.createStream()
                stream != null
            }
            if (!ok) Log.e(TAG, "startAsr failed: $encoder")
            return ok
        }

        override fun prepareAsr(
            encoder: String,
            decoder: String,
            joiner: String,
            tokens: String,
            cb: IVoiceAsrCallback?,
        ): Boolean {
            cancelIdleRelease()
            val paths = listOf(encoder, decoder, joiner, tokens)
            val ready = synchronized(lock) {
                if (recognizer != null && enginePaths != paths) {
                    // 模型已切换：旧引擎对新路径无效，先释放（后台线程会按新路径重建）
                    Log.i(TAG, "model changed, releasing stale engine: $encoder")
                    releaseEngineLocked()
                }
                recognizer != null
            }
            if (ready) {
                runCatching { cb?.onEngineReady(true, "") }
                return true
            }
            // 后台线程加载：不占 binder 线程，app 侧的 push/finish 不会被加载阻塞
            Thread({
                val ok = try {
                    synchronized(lock) {
                        // 拿到锁后重新校验路径：期间可能又发生了模型切换，
                        // 旧的 recognizer 对本次请求的路径无效，须先释放再按新路径创建
                        if (recognizer != null && enginePaths != paths) {
                            Log.i(TAG, "model changed during prepare, rebuilding: $encoder")
                            releaseEngineLocked()
                        }
                        recognizer != null || createEngine(encoder, decoder, joiner, tokens)
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "prepareAsr failed", e)
                    false
                }
                runCatching { cb?.onEngineReady(ok, if (ok) "" else "模型加载失败") }
                if (!ok) scheduleIdleRelease()
            }, "voice-asr-prepare").start()
            return true
        }

        override fun pushAudio(samples: FloatArray?): String {
            if (samples == null || samples.isEmpty()) return ""
            val text = synchronized(lock) {
                val r = recognizer ?: return ""
                val s = stream ?: return ""
                s.acceptWaveform(samples, SAMPLE_RATE)
                while (r.isReady(s)) r.decode(s)
                r.getResult(s).text
            }
            return text
        }

        override fun finishAsr(): String {
            val text = synchronized(lock) {
                val r = recognizer
                val s = stream
                if (r == null || s == null) {
                    ""
                } else {
                    s.inputFinished()
                    while (r.isReady(s)) r.decode(s)
                    r.getResult(s).text
                }
            }
            // 本次会话结束：丢弃流，保留模型句柄供热启动，空闲超时再释放
            synchronized(lock) {
                stream?.release()
                stream = null
                callback = null
            }
            scheduleIdleRelease()
            return text
        }

        override fun cancelAsr() {
            synchronized(lock) {
                stream?.release()
                stream = null
                callback = null
            }
            scheduleIdleRelease()
        }

        override fun releaseAsr() {
            cancelIdleRelease()
            releaseEngine()
        }
    }

    /** 创建识别器；模型文件缺失或加载失败返回 false。 */
    private fun createEngine(
        encoder: String,
        decoder: String,
        joiner: String,
        tokens: String,
    ): Boolean {
        val files = listOf(encoder, decoder, joiner, tokens).map(::File)
        if (files.any { !it.isFile || it.length() <= 0L }) {
            Log.e(TAG, "model files incomplete: $encoder")
            return false
        }
        return try {
            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80, dither = 0.0f),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = encoder,
                        decoder = decoder,
                        joiner = joiner,
                    ),
                    tokens = tokens,
                    // 纯 CPU：int8 + 动态 shape 的流式模型在 NNAPI 上命中率极低，
                    // 2 线程与共享线程池口径一致，避免模型常驻期间空转发热
                    numThreads = 2,
                    provider = "cpu",
                    debug = false,
                ),
                // 按住说话：由 UI 决定起止，不用引擎的静音端点判定
                enableEndpoint = false,
                // 与自研实现口径一致（greedy），后续可切换 modified_beam_search 提升准确率
                decodingMethod = "greedy_search",
            )
            recognizer = OnlineRecognizer(config = config)
            enginePaths = files.map { it.path }
            true
        } catch (e: Throwable) {
            Log.e(TAG, "createEngine failed", e)
            recognizer = null
            enginePaths = emptyList()
            false
        }
    }

    /** 释放引擎并清除已记录的路径（外部入口：空闲超时 / releaseAsr / 销毁）。 */
    private fun releaseEngine() {
        synchronized(lock) { releaseEngineLocked() }
    }

    /** 须持 [lock] 调用；路径信息一并清掉，避免复用过期状态。 */
    private fun releaseEngineLocked() {
        stream?.release()
        stream = null
        recognizer?.release()
        recognizer = null
        enginePaths = emptyList()
        callback = null
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        cancelIdleRelease()
        releaseEngine()
        super.onDestroy()
    }
}
