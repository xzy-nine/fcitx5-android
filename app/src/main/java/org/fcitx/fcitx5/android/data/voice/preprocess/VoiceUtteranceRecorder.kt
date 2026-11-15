/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 整段语音录音器（16kHz / 单声道 / PCM16 → float）。
 *
 * 与 [org.fcitx.fcitx5.android.data.voice.VoiceAudioCapture] 的差别：这里**不做任何门限与增益**，
 * 从按下到松开之间的全部采样原样保留（含首尾静音）。理由：它服务于整段提交路径的预处理
 * （VAD 需要看到真实静音才能判决），而采集侧的预滚与弱信号增益会改变静音段的电平特征，
 * 让预处理无法复现线上的输入。
 *
 * 线程模型：读线程只有一个，由 start() 创建；缓冲区读写用 [lock] 保护，
 * stop() 可以从任意线程调用并取回整段音频。音频设备由读线程自己收尾（与采集侧同一约定）。
 */
package org.fcitx.fcitx5.android.data.voice.preprocess

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import timber.log.Timber
import kotlin.math.abs

class VoiceUtteranceRecorder(
    private val sampleRate: Int = VoicePreprocessConfig.SAMPLE_RATE,
    private val maxDurationMs: Int = DEFAULT_MAX_DURATION_MS,
    private val onLevel: (Float) -> Unit = {},
    private val onError: (String) -> Unit = {},
) {

    companion object {
        private const val TAG = "VoiceUtteranceRecorder"

        /** 单次录音时长上限，避免长按不松手时无界占用内存（60s ≈ 3.8MB float）。 */
        const val DEFAULT_MAX_DURATION_MS = 60_000
    }

    private val lock = Any()

    /** 预分配 4 秒，之后按需翻倍。 */
    private var buffer = FloatArray(sampleRate * 4)
    private var size = 0

    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    /** 达到时长上限后自动停止采集。 */
    @Volatile
    private var reachedLimit = false

    val isRecording: Boolean get() = running

    val isReachedLimit: Boolean get() = reachedLimit

    val recordedMs: Int get() = synchronized(lock) { size } * 1000 / sampleRate

    /** 开始采集；权限缺失或设备初始化失败返回 false。 */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(): Boolean {
        if (running) return true
        val chunkSamples = sampleRate / 10
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            onError("无法初始化录音设备")
            return false
        }
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, chunkSamples * 2 * 4),
            )
        } catch (e: Exception) {
            Timber.e(e, "$TAG: AudioRecord create failed")
            onError("无法初始化录音设备")
            return false
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            onError("录音设备不可用")
            return false
        }
        synchronized(lock) {
            size = 0
            if (buffer.size < sampleRate) buffer = FloatArray(sampleRate * 4)
        }
        reachedLimit = false
        return try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                recorder.release()
                onError("录音启动失败")
                false
            } else {
                record = recorder
                running = true
                Timber.i("$TAG: capture started")
                thread = Thread({ loop(recorder) }, "voice-utterance").apply { start() }
                true
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: startRecording failed")
            recorder.release()
            onError("录音启动失败")
            false
        }
    }

    /**
     * 停止采集并返回整段音频（可能为空数组）。
     *
     * 读线程最多等 [STOP_JOIN_MS]：正常情况它在下一次 read 返回后退出（分块 100ms）；
     * 超时也照样返回当前已采集的样本，避免阻塞调用方。
     */
    fun stop(): FloatArray {
        running = false
        val t = thread
        thread = null
        if (t != null) {
            runCatching { t.join(STOP_JOIN_MS) }
        }
        val recorder = record
        record = null
        if (recorder != null && (t == null || !t.isAlive)) {
            // 线程已退出时设备已由 loop() 释放；只有「线程从未启动/已退出但设备仍在」才在这里兜底
            runCatching { recorder.release() }
        }
        return synchronized(lock) { buffer.copyOf(size) }
    }

    /** 释放（页面销毁）。 */
    fun release() {
        stop()
    }

    private fun loop(recorder: AudioRecord) {
        val chunkSamples = sampleRate / 10
        val shortBuffer = ShortArray(chunkSamples)
        val limitSamples = sampleRate * maxDurationMs / 1000
        try {
            while (running) {
                val read = recorder.read(shortBuffer, 0, shortBuffer.size)
                if (read == 0) continue
                if (read < 0) {
                    if (!running) break
                    Timber.w("$TAG: AudioRecord read error $read")
                    if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        onError("录音中断（read=$read）")
                        break
                    }
                    continue
                }
                var peak = 0
                for (i in 0 until read) {
                    val v = abs(shortBuffer[i].toInt())
                    if (v > peak) peak = v
                }
                onLevel((peak / 32768f).coerceIn(0f, 1f))

                val appended = synchronized(lock) {
                    val room = limitSamples - size
                    if (room <= 0) {
                        0
                    } else {
                        val count = minOf(read, room)
                        ensureCapacity(size + count)
                        for (i in 0 until count) {
                            buffer[size + i] = shortBuffer[i] / 32768f
                        }
                        size += count
                        count
                    }
                }
                if (appended < read) {
                    // 触顶：不再采集，等 UI 轮询到状态变化后停止
                    reachedLimit = true
                    running = false
                    break
                }
            }
        } catch (e: Exception) {
            if (running) {
                Timber.e(e, "$TAG: read loop failed")
                onError("录音中断")
            }
        } finally {
            runCatching {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            }
            runCatching { recorder.release() }
            Timber.i("$TAG: capture loop exited (${recordedMs}ms)")
        }
    }

    /** 只在 [lock] 内调用。 */
    private fun ensureCapacity(required: Int) {
        if (required <= buffer.size) return
        var capacity = buffer.size
        while (capacity < required) capacity *= 2
        buffer = buffer.copyOf(capacity)
    }
}

/** stop() 等待读线程退出的上限；超时也返回当前样本，不阻塞调用方。 */
private const val STOP_JOIN_MS = 400L
