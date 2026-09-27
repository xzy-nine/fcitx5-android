/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Kingz Cheung
 *
 * 移植自 Xime (https://github.com/ximeiorg/xime) 的 service/AsrInferenceService.kt，见仓库根 NOTICE.md。
 *
 * 与上游的差异：上游 startAsr(modelDir, cb) 由本进程（`:asr`）用 AsrModelManager 读偏好定位模型文件；
 * 本移植的 `:asr` 进程不初始化 AppPrefs/用户数据目录（见 FcitxApplication 的进程守卫），
 * 因此改为由 app 侧把 4 个模型文件的绝对路径经 AIDL 传入。
 */
package com.kingzcheung.xime.service

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.kingzcheung.xime.speech.AsrNative
import com.kingzcheung.xime.util.FileLogger
import java.io.File

/**
 * 离线语音识别服务，运行在 :asr 独立进程。
 *
 * 模型加载与推理全部在此进程完成，输入法主进程仅负责音频采集与结果回调，
 * 不占用输入法进程内存。
 *
 * 模型空闲释放：语音会话结束后一段时间无新会话，自动释放模型句柄
 * （[AsrNative.nativeRelease]），只保留 :asr 进程与 ONNX 运行库，避免
 * 154MB 模型长期常驻推高功耗。下次 [startAsr] 时再按需加载（加载已提速）。
 */
class AsrInferenceService : Service() {

    companion object {
        private const val TAG = "AsrInferenceService"

        /** 语音会话结束后，多久无新会话即释放模型。 */
        private const val IDLE_RELEASE_DELAY_MS = 60_000L
    }

    private var asrHandle: Long = 0L
    private var asrCallback: IInferenceAsrCallback? = null
    private val asrLock = Any()

    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleReleaseRunnable = Runnable {
        synchronized(asrLock) {
            if (asrHandle != 0L) {
                AsrNative.nativeRelease(asrHandle)
                asrHandle = 0L
                asrCallback = null
                FileLogger.i(TAG, "ASR model released after idle timeout")
            }
        }
    }

    private fun scheduleIdleRelease() {
        idleHandler.removeCallbacks(idleReleaseRunnable)
        idleHandler.postDelayed(idleReleaseRunnable, IDLE_RELEASE_DELAY_MS)
    }

    private fun cancelIdleRelease() {
        idleHandler.removeCallbacks(idleReleaseRunnable)
    }

    private val binder = object : IInferenceAsrService.Stub() {

        override fun startAsr(
            encoderPath: String,
            decoderPath: String,
            joinerPath: String,
            tokensPath: String,
            callback: IInferenceAsrCallback
        ): Boolean {
            cancelIdleRelease()
            // 模型加载耗时较长，放在 asrLock 外执行，避免阻塞其它 ASR 控制操作
            if (synchronized(asrLock) { asrHandle } == 0L) {
                val handle = createAsrHandle(encoderPath, decoderPath, joinerPath, tokensPath)
                if (handle == 0L) {
                    Log.e(TAG, "Failed to create ASR recognizer from $encoderPath")
                    return false
                }
                synchronized(asrLock) {
                    if (asrHandle == 0L) {
                        asrHandle = handle
                        FileLogger.i(TAG, "ASR recognizer created, handle=$handle")
                    } else {
                        AsrNative.nativeRelease(handle)
                    }
                }
            }
            return try {
                synchronized(asrLock) {
                    asrCallback = callback
                    AsrNative.nativeReset(asrHandle)
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "startAsr failed", e)
                false
            }
        }

        override fun pushAsrAudio(audioData: ByteArray) {
            val partial: String
            val cb: IInferenceAsrCallback?
            synchronized(asrLock) {
                if (asrHandle == 0L) return
                AsrNative.nativeAcceptPcm(asrHandle, audioData)
                partial = AsrNative.nativeGetPartial(asrHandle)
                cb = asrCallback
            }
            if (cb != null && partial.isNotEmpty()) {
                try {
                    cb.onPartialResult(partial)
                } catch (_: Exception) {
                }
            }
        }

        override fun stopAsr(): String {
            val text = try {
                synchronized(asrLock) {
                    if (asrHandle == 0L) ""
                    else AsrNative.nativeFinalize(asrHandle)
                }
            } catch (e: Exception) {
                Log.e(TAG, "stopAsr failed", e)
                ""
            }
            scheduleIdleRelease()
            synchronized(asrLock) { asrCallback = null }
            return text
        }

        override fun cancelAsr() {
            synchronized(asrLock) {
                if (asrHandle != 0L) AsrNative.nativeReset(asrHandle)
                asrCallback = null
            }
            scheduleIdleRelease()
        }

        override fun releaseAsr() {
            cancelIdleRelease()
            synchronized(asrLock) {
                if (asrHandle != 0L) {
                    AsrNative.nativeRelease(asrHandle)
                    asrHandle = 0L
                }
                asrCallback = null
            }
        }
    }

    /** 按 app 侧传来的 4 个绝对路径创建识别器（模型加载发生在本 `:asr` 进程）。 */
    private fun createAsrHandle(
        encoderPath: String,
        decoderPath: String,
        joinerPath: String,
        tokensPath: String
    ): Long {
        return try {
            val encoder = File(encoderPath)
            val decoder = File(decoderPath)
            val joiner = File(joinerPath)
            val tokens = File(tokensPath)
            if (!encoder.exists() || !decoder.exists() || !joiner.exists() || !tokens.exists()) {
                Log.e(TAG, "ASR model files incomplete: $encoderPath")
                return 0L
            }
            AsrNative.nativeCreate(
                encoder.absolutePath,
                decoder.absolutePath,
                joiner.absolutePath,
                tokens.absolutePath
            )
        } catch (e: Exception) {
            Log.e(TAG, "createAsrHandle failed", e)
            0L
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        cancelIdleRelease()
        synchronized(asrLock) {
            if (asrHandle != 0L) {
                AsrNative.nativeRelease(asrHandle)
                asrHandle = 0L
            }
            asrCallback = null
        }
        super.onDestroy()
    }
}
