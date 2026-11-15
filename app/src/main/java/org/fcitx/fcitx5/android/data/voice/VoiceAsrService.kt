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

class VoiceAsrService : Service() {

    private val lock = Any()
    private var engine: SpeechEngine? = null
    private var callback: IVoiceAsrCallback? = null

    /** 当前引擎对应的模型描述（持锁读写），用于检测模型切换。 */
    private var engineSpec: VoiceModelSpec? = null

    /** 本次会话已送入的采样点数（仅用于排障日志）。 */
    private var pushedSamples = 0

    /** 上一次已记录的部分结果，避免逐块重复打同一句。 */
    private var lastPushedText = ""

    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleReleaseRunnable = Runnable { releaseEngine() }

    private fun scheduleIdleRelease() {
        idleHandler.removeCallbacks(idleReleaseRunnable)
        idleHandler.postDelayed(idleReleaseRunnable, IDLE_RELEASE_DELAY_MS)
    }

    private fun cancelIdleRelease() = idleHandler.removeCallbacks(idleReleaseRunnable)

    /** AIDL 的平行数组 → [VoiceModelSpec]；家族名不认识时返回 null。 */
    private fun specOf(
        family: String,
        roles: Array<out String>?,
        paths: Array<out String>?,
        language: String?,
        inverseTextNormalization: Boolean,
    ): VoiceModelSpec? {
        val parsed = VoiceModelFamily.entries.firstOrNull { it.name == family } ?: return null
        if (roles == null || paths == null || roles.size != paths.size) return null
        val files = roles.zip(paths).toMap()
        return VoiceModelSpec(
            family = parsed,
            files = files,
            language = language.orEmpty(),
            useInverseTextNormalization = inverseTextNormalization,
        )
    }

    /** 加载引擎；已加载且 spec 未变则复用。仅在持锁时调用/自持锁。 */
    private fun ensureEngine(spec: VoiceModelSpec): Boolean {
        if (engine != null && engineSpec == spec) return true
        if (engine != null) {
            Log.i("VoiceAsrService", "model changed, rebuilding engine: ${spec.family}")
            engine?.release()
            engine = null
            engineSpec = null
        }
        val created = createSpeechEngine(spec) ?: return false
        engine = created
        engineSpec = spec
        return true
    }

    private val binder = object : IVoiceAsrService.Stub() {

        override fun startAsr(
            family: String,
            roles: Array<out String>?,
            paths: Array<out String>?,
            language: String?,
            inverseTextNormalization: Boolean,
            cb: IVoiceAsrCallback?,
        ): Boolean {
            cancelIdleRelease()
            val spec = specOf(family, roles, paths, language, inverseTextNormalization)
            if (spec == null) {
                Log.e("VoiceAsrService", "startAsr rejected: bad spec (family=$family)")
                return false
            }
            Log.i(
                "VoiceAsrService",
                "startAsr: ${VoiceModelClassifier.describe(spec)}",
            )
            val ok = synchronized(lock) {
                if (!ensureEngine(spec)) return false
                callback = cb
                // 新会话：重置本次会话状态（流式建流 / 整段清空音频缓冲）
                engine?.startSession()
                true
            }
            if (!ok) Log.e("VoiceAsrService", "startAsr failed: ${spec.family}")
            return ok
        }

        override fun prepareAsr(
            family: String,
            roles: Array<out String>?,
            paths: Array<out String>?,
            language: String?,
            inverseTextNormalization: Boolean,
            cb: IVoiceAsrCallback?,
        ): Boolean {
            cancelIdleRelease()
            val spec = specOf(family, roles, paths, language, inverseTextNormalization)
            if (spec == null) {
                Log.e("VoiceAsrService", "prepareAsr rejected: bad spec (family=$family)")
                return false
            }
            val ready = synchronized(lock) {
                // spec 变了就地释放，避免旧的（可能错家族的）句柄留在进程里
                if (engine != null && engineSpec != spec) {
                    Log.i("VoiceAsrService", "model changed, releasing stale engine: ${spec.family}")
                    engine?.release()
                    engine = null
                    engineSpec = null
                }
                engine != null
            }
            if (ready) {
                runCatching { cb?.onEngineReady(true, "") }
                return true
            }
            // 后台线程加载：不占 binder 线程，app 侧的 push/finish 不会被加载阻塞
            Thread({
                val ok = try {
                    synchronized(lock) { ensureEngine(spec) }
                } catch (e: Throwable) {
                    Log.e("VoiceAsrService", "prepareAsr failed", e)
                    false
                }
                runCatching { cb?.onEngineReady(ok, if (ok) "" else "模型加载失败") }
                if (!ok) scheduleIdleRelease()
            }, "voice-asr-prepare").start()
            return true
        }

        override fun pushAudio(samples: FloatArray?): String {
            if (samples == null || samples.isEmpty()) return ""
            // 采样计数只用于排障：能看出音频是否真的送到了引擎
            pushedSamples += samples.size
            val text = synchronized(lock) { engine?.push(samples) ?: "" }
            if (text.isNotEmpty() && text != lastPushedText) {
                lastPushedText = text
                Log.i("VoiceAsrService", "partial: $text")
            }
            return text
        }

        override fun finishAsr(): String {
            val text = synchronized(lock) { engine?.finish() ?: "" }
            // 本次会话结束：保留模型句柄供热启动，空闲超时再释放
            synchronized(lock) { callback = null }
            // 整段（离线）模型只有在结束解码后才有文本，必须在这里记一条
            Log.i(
                "VoiceAsrService",
                "finish: samples=$pushedSamples chars=${text.length}",
            )
            pushedSamples = 0
            lastPushedText = ""
            scheduleIdleRelease()
            return text
        }

        override fun cancelAsr() {
            synchronized(lock) {
                engine?.cancel()
                callback = null
            }
            scheduleIdleRelease()
        }

        override fun releaseAsr() {
            cancelIdleRelease()
            releaseEngine()
        }
    }

    /** 释放引擎并清除已记录的描述（外部入口：空闲超时 / releaseAsr / 销毁）。 */
    private fun releaseEngine() {
        synchronized(lock) {
            engine?.release()
            engine = null
            engineSpec = null
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
