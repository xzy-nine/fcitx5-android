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
            val ok = synchronized(lock) {
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
            true
        } catch (e: Throwable) {
            Log.e(TAG, "createEngine failed", e)
            recognizer = null
            false
        }
    }

    private fun releaseEngine() {
        synchronized(lock) {
            stream?.release()
            stream = null
            recognizer?.release()
            recognizer = null
            callback = null
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        cancelIdleRelease()
        releaseEngine()
        super.onDestroy()
    }
}
