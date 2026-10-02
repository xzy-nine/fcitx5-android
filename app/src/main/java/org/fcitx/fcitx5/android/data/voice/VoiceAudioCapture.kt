/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 语音输入的音频采集（16kHz / 单声道 / PCM16）。
 *
 * 职责与边界：
 *  - 只负责「采到 16kHz float 采样并交付」；识别引擎、状态机、文本上屏都不在这里；
 *  - 首字保护：检测到说话之前先缓存 [PRE_ROLL_CHUNKS] 块（0.4s），一旦判定开始说话
 *    就把缓存按序补交，避免「你/觉」这类弱开头被门限吞掉；缓存满 0.4s 仍无语音也直接开闸，
 *    保证整段低音量内容仍能识别（与上一版实现的取舍一致）；
 *  - 轻量增益：仅当整块峰值过低时按常量放大（上限 6×），不衰减强信号；
 *  - 频谱/振幅回调只用于可视化，不参与识别。
 *
 * 非线程安全的前提：start/stop/release 由外部串行调用，内部读线程只跑一个。
 */
package org.fcitx.fcitx5.android.data.voice

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import timber.log.Timber

class VoiceAudioCapture(
    private val onSpeechChunk: (FloatArray) -> Unit,
    private val onAmplitude: (Float) -> Unit,
    private val onSpectrum: (FloatArray) -> Unit,
    private val onError: (String) -> Unit,
) {

    companion object {
        private const val TAG = "VoiceAudioCapture"

        const val SAMPLE_RATE = 16000
        private const val CHUNK_SAMPLES = SAMPLE_RATE / 10 // 100ms
        private const val PRE_ROLL_CHUNKS = 4              // 0.4s 语音前缓冲
        private const val SPEECH_PEAK = 200                // int16 峰值门限（约 -44dBFS）
        private const val LOW_LEVEL_PEAK = 2600            // 低于此值视为弱信号（约 -22dBFS）
        private const val MAX_GAIN = 6.0f
    }

    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    private val spectrum = VoiceSpectrum()

    /** 开始采集；录音权限缺失或设备初始化失败返回 false。 */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(): Boolean {
        if (running) return true
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            onError("无法初始化录音设备")
            return false
        }
        val bufferBytes = maxOf(minBuffer, CHUNK_SAMPLES * 2 * 4)
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
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
        record = recorder
        return try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Timber.e("$TAG: startRecording did not take effect")
                recorder.release()
                record = null
                onError("录音启动失败")
                false
            } else {
                running = true
                Timber.i("$TAG: capture started")
                thread = Thread({ loop(recorder) }, "voice-capture").apply { start() }
                true
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: startRecording failed")
            recorder.release()
            record = null
            onError("录音启动失败")
            false
        }
    }

    /** 停止采集（读线程自行退出；不释放 AudioRecord，便于复用）。 */
    fun stop() {
        running = false
        val recorder = record ?: return
        try {
            // 未启动就 stop() 会抛 IllegalStateException
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
        } catch (e: Exception) {
            Timber.w(e, "$TAG: stop failed")
        }
    }

    /** 释放设备（会话结束/组件销毁）。 */
    fun release() {
        stop()
        val t = thread
        thread = null
        if (t != null) {
            // 读线程通常在下一次 read 返回后退出（分块 100ms）；join 超时说明它还卡在
            // read/push 上——此时不能从外部 release AudioRecord（正被线程使用，且会残留
            // 原生录音会话），交给 loop() 的 finally 无条件释放它持有的实例
            t.join(200)
            if (t.isAlive) {
                Timber.w("$TAG: capture thread still alive after stop, deferring release to thread")
                return
            }
            // 线程已退出：recorder 已由 loop() 的 finally 释放，这里只清引用
            record = null
        } else {
            // 线程从未启动（启动失败路径残留）时由外部兜底释放
            record?.release()
            record = null
        }
    }

    private fun loop(recorder: AudioRecord) {
        val shortBuffer = ShortArray(CHUNK_SAMPLES)
        val preRoll = ArrayDeque<FloatArray>()
        var speechDetected = false
        var readErrors = 0
        var chunks = 0
        try {
            while (running) {
                val read = recorder.read(shortBuffer, 0, shortBuffer.size)
                if (read == 0) continue
                if (read < 0) {
                    // 停止过程中的负值属正常；运行中出现的负值先容忍，
                    // 仅当录音状态已不健康或连续多次失败才终止会话
                    if (!running) break
                    readErrors++
                    Timber.w("$TAG: AudioRecord read error $read (x$readErrors)")
                    if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        onError("录音中断（read=$read）")
                        break
                    }
                    if (readErrors >= 5) {
                        onError("录音读取出错（read=$read）")
                        break
                    }
                    continue
                }
                readErrors = 0
                chunks++
                if (chunks == 1) Timber.i("$TAG: first audio chunk read ($read samples)")
                var peak = 0
                for (i in 0 until read) {
                    val abs = kotlin.math.abs(shortBuffer[i].toInt())
                    if (abs > peak) peak = abs
                }
                onAmplitude((peak / 32768f).coerceIn(0f, 1f))
                onSpectrum(spectrum.analyze(shortBuffer, read))

                val chunk = toFloatSamples(shortBuffer, read, peak)
                if (!speechDetected) {
                    preRoll.addLast(chunk)
                    if (isSpeech(peak)) {
                        speechDetected = true
                        flush(preRoll)
                    } else if (preRoll.size >= PRE_ROLL_CHUNKS) {
                        // 缓冲满仍无语音：放弃门限，直接开闸（低音量整段输入）
                        speechDetected = true
                        flush(preRoll)
                    }
                } else {
                    onSpeechChunk(chunk)
                }
            }
        } catch (e: Exception) {
            if (running) {
                Timber.e(e, "$TAG: read loop failed")
                onError("录音中断")
            }
        } finally {
            // 读线程退出前把残余的语音前缓冲交出去，避免丢掉最后一段开头
            if (!speechDetected) flush(preRoll)
            // 录音设备由**读线程自己**无条件收尾：stop()/release() 从别的线程调用时，
            // 可能正好卡在 read() 里（外部 release 与 read 并发不安全，且会残留
            // 原生录音会话）；本线程退出时 recorder 必然已无人使用，这里 release 即可。
            // release() 侧只在线程已退出时才可能走到它的 record?.release()，
            // 故同一实例不会被双重释放
            try {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            } catch (e: Exception) {
                Timber.w(e, "$TAG: stop in loop failed")
            }
            try {
                recorder.release()
            } catch (e: Exception) {
                Timber.w(e, "$TAG: release in loop failed")
            }
            Timber.i("$TAG: capture loop exited (chunks=$chunks)")
        }
    }

    private fun flush(preRoll: ArrayDeque<FloatArray>) {
        while (preRoll.isNotEmpty()) onSpeechChunk(preRoll.removeFirst())
    }

    private fun isSpeech(peak: Int): Boolean = peak > SPEECH_PEAK

    /** short → float(±1)，弱信号按块常量增益。 */
    private fun toFloatSamples(samples: ShortArray, length: Int, peak: Int): FloatArray {
        val gain = if (peak in 1 until LOW_LEVEL_PEAK) {
            (LOW_LEVEL_PEAK.toFloat() / peak).coerceAtMost(MAX_GAIN)
        } else {
            1f
        }
        return FloatArray(length) { i ->
            (samples[i] / 32768f * gain).coerceIn(-1f, 1f)
        }
    }
}
