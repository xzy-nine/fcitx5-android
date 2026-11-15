/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: WAV 文件播放器（调试页试听「原始 / 处理后」音频）。
 *
 * 用 [MediaPlayer] 播落盘的 WAV，而不是自己用 AudioTrack 推流：停止语义由系统播放器保证
 * （不需要在写线程与释放之间做竞态收尾），进度可轮询 currentPosition，试听与导出的
 * 编码路径也完全一致。
 *
 * 线程约定：[play] / [stop] 必须在主线程调用，状态回调也在主线程。
 */
package org.fcitx.fcitx5.android.data.voice.preprocess

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import timber.log.Timber
import java.io.File

class VoicePcmPlayer {

    companion object {
        private const val TAG = "VoicePcmPlayer"
        private const val PROGRESS_INTERVAL_MS = 50L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var player: MediaPlayer? = null
    private var progressCallback: ((Float) -> Unit)? = null
    private var finishedCallback: (() -> Unit)? = null

    /** 播放代次：换曲/停止后，旧播放器的迟到回调一律忽略。 */
    private var generation = 0

    val isPlaying: Boolean get() = player != null

    private val ticker = object : Runnable {
        override fun run() {
            val mp = player ?: return
            val duration = runCatching { mp.duration }.getOrDefault(-1)
            val position = runCatching { mp.currentPosition }.getOrDefault(0)
            if (duration > 0) {
                progressCallback?.invoke((position.toFloat() / duration).coerceIn(0f, 1f))
            }
            mainHandler.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }

    /** 播放 [file]（WAV）；已在播放时先停止当前播放。 */
    fun play(
        file: File,
        onProgress: (Float) -> Unit = {},
        onFinished: () -> Unit = {},
        onError: (String) -> Unit = {},
    ) {
        stop()
        val gen = generation
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener {
                if (gen != generation) return@setOnCompletionListener
                val callback = finishedCallback
                stop()
                callback?.invoke()
            }
            mp.setOnErrorListener { _, what, extra ->
                if (gen == generation) {
                    Timber.w("$TAG: playback error what=$what extra=$extra")
                    stop()
                    onError("播放失败（$what/$extra）")
                }
                true
            }
            mp.prepare()
            mp.start()
            player = mp
            this.progressCallback = onProgress
            this.finishedCallback = onFinished
            mainHandler.removeCallbacks(ticker)
            mainHandler.post(ticker)
        } catch (e: Exception) {
            Timber.w(e, "$TAG: play failed")
            stop()
            onError(e.message ?: "播放失败")
        }
    }

    /** 停止播放并释放播放器（可重复调用）。 */
    fun stop() {
        generation++
        mainHandler.removeCallbacks(ticker)
        val mp = player
        player = null
        progressCallback = null
        finishedCallback = null
        if (mp != null) {
            runCatching { if (mp.isPlaying) mp.stop() }
            runCatching { mp.release() }
        }
    }

    fun release() = stop()
}
