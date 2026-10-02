/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 本地离线语音识别的 :asr 进程客户端（app 侧）。
 *
 * 与 [VoiceAsrService] 之间的进程边界只传「模型文件绝对路径 + 16kHz float 采样」，
 * 状态机留在 app 侧（[VoiceSession]）。
 */
package org.fcitx.fcitx5.android.data.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VoiceAsrClient(private val context: Context) {

    companion object {
        private const val TAG = "VoiceAsrClient"
        private const val BIND_TIMEOUT_SECONDS = 5L
    }

    interface Callback {
        fun onPartial(text: String)
        fun onError(message: String)
    }

    private var service: IVoiceAsrService? = null
    private var bound = false
    private var connectLatch = CountDownLatch(1)

    private val callbackStub = object : IVoiceAsrCallback.Stub() {
        @Volatile
        private var target: Callback? = null

        fun attach(cb: Callback) {
            target = cb
        }

        fun detach() {
            target = null
        }

        override fun onPartial(text: String) {
            target?.onPartial(text)
        }

        override fun onError(message: String) {
            target?.onError(message)
        }

        /** 预加载回执由 [prepare] 内部的临时回调处理，会话回调不关心。 */
        override fun onEngineReady(ok: Boolean, message: String) = Unit
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IVoiceAsrService.Stub.asInterface(binder)
            bound = true
            connectLatch.countDown()
            Timber.d("$TAG: connected")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
            connectLatch = CountDownLatch(1)
            Timber.w("$TAG: disconnected")
        }
    }

    /** 绑定 :asr 服务（幂等）。 */
    suspend fun ensureBound(): Boolean = withContext(Dispatchers.IO) {
        if (bound && service != null) return@withContext true
        if (!bound) {
            connectLatch = CountDownLatch(1)
            val intent = Intent(context, VoiceAsrService::class.java)
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                Timber.e("$TAG: bindService refused")
                return@withContext false
            }
        }
        val connected = connectLatch.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!connected) Timber.e("$TAG: bind timeout")
        connected && service != null
    }

    /** 开始一次会话：模型不存在时由 :asr 侧加载（失败返回 false）。 */
    suspend fun start(files: VoiceModelFiles, callback: Callback): Boolean =
        withContext(Dispatchers.IO) {
            if (!ensureBound()) return@withContext false
            callbackStub.attach(callback)
            val svc = service ?: return@withContext false
            try {
                svc.startAsr(
                    files.encoder, files.decoder, files.joiner, files.tokens, callbackStub
                )
            } catch (e: Exception) {
                Timber.e(e, "$TAG: start failed")
                false
            }
        }

    /** 送入一段 16kHz 单声道采样（[-1,1]），返回当前部分结果。 */
    /**
     * 预加载模型（面板打开时预热）：服务端在后台线程加载，这里等它的回执。
     *
     * 阻塞在 IO 线程上可接受（UI 由 PREPARING 状态提示），换来后续 start/finish
     * 不会排在模型加载后面。
     */
    suspend fun prepare(files: VoiceModelFiles, timeoutSeconds: Long = 60L): Boolean =
        withContext(Dispatchers.IO) {
            if (!ensureBound()) return@withContext false
            val svc = service ?: return@withContext false
            val latch = CountDownLatch(1)
            var ok = false
            val stub = object : IVoiceAsrCallback.Stub() {
                override fun onPartial(text: String) = Unit

                override fun onError(message: String) {
                    Timber.w("$TAG: prepare error: $message")
                    latch.countDown()
                }

                override fun onEngineReady(ready: Boolean, message: String) {
                    ok = ready
                    if (!ready) Timber.w("$TAG: prepare failed: $message")
                    latch.countDown()
                }
            }
            try {
                svc.prepareAsr(files.encoder, files.decoder, files.joiner, files.tokens, stub)
                val finished = latch.await(timeoutSeconds, TimeUnit.SECONDS)
                if (!finished) Timber.w("$TAG: prepare timed out after ${timeoutSeconds}s")
            } catch (e: Exception) {
                Timber.e(e, "$TAG: prepare failed")
                return@withContext false
            }
            ok
        }

    fun push(samples: FloatArray): String = try {
        service?.pushAudio(samples) ?: ""
    } catch (e: Exception) {
        Timber.e(e, "$TAG: push failed")
        ""
    }

    /** 结束会话并取最终文本（阻塞在 IPC 上，调用方须在 IO 线程）。 */
    suspend fun finish(): String = withContext(Dispatchers.IO) {
        try {
            service?.finishAsr() ?: ""
        } catch (e: Exception) {
            Timber.e(e, "$TAG: finish failed")
            ""
        } finally {
            callbackStub.detach()
        }
    }

    fun cancel() {
        callbackStub.detach()
        try {
            service?.cancelAsr()
        } catch (e: Exception) {
            Timber.e(e, "$TAG: cancel failed")
        }
    }

    /** 释放模型句柄（关闭「本地识别」开关时）。 */
    suspend fun releaseModel() = withContext(Dispatchers.IO) {
        try {
            service?.releaseAsr()
        } catch (e: Exception) {
            Timber.e(e, "$TAG: release failed")
        }
        Unit
    }

    fun unbind() {
        callbackStub.detach()
        if (!bound) return
        try {
            context.unbindService(connection)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: unbind failed")
        }
        bound = false
        service = null
    }

    /** 同步结束（供 stop 路径使用，已经是阻塞语义）。 */
    fun finishBlocking(): String = runBlocking { finish() }
}
