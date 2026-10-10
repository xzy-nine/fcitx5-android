/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 非流式语音输入的整段音频缓冲与预处理入口。
 *
 * 非流式目标（离线整段模型、松手后整段提交的在线平台）在采集期间只积累采样，
 * 松手后一次性跑完整段预处理，再把结果交给引擎——切除、压缩与响度均衡都需要看到完整音频。
 *
 * 流式目标不经过这里：它依赖连续时间轴，必须边采边送。
 *
 * 采集线程写入、会话线程取用，故读写都持锁；[drain] 之后本对象即作废（每次会话新建一个）。
 */
package org.fcitx.fcitx5.android.data.voice.preprocess

/** 累积整段音频并在结束时预处理；空的累积返回 null。 */
class VoiceUtteranceBuffer(
    private val config: VoicePreprocessConfig = VoicePreprocessConfig.VOICE_INPUT,
) {

    private val lock = Any()
    private val chunks = ArrayList<FloatArray>()
    private var samples = 0
    private var drained = false

    val isEmpty: Boolean get() = synchronized(lock) { drained || samples == 0 }

    /** 追加一个采集分块；已取走（[drain] 之后）的缓冲忽略后续写入。 */
    fun append(chunk: FloatArray) {
        if (chunk.isEmpty()) return
        synchronized(lock) {
            if (drained) return
            chunks.add(chunk)
            samples += chunk.size
        }
    }

    /**
     * 取走全部音频并做整段预处理；没有音频返回 null。
     *
     * 之后本对象不再接受写入（调用方每次会话新建一个），避免迟到分块混进下一次结果。
     */
    fun drain(): VoicePreprocessResult? {
        val joined = synchronized(lock) {
            if (drained || samples == 0) {
                drained = true
                chunks.clear()
                return null
            }
            drained = true
            val out = FloatArray(samples)
            var offset = 0
            for (chunk in chunks) {
                chunk.copyInto(out, offset)
                offset += chunk.size
            }
            chunks.clear()
            samples = 0
            out
        }
        return VoiceSilenceTrimmer.trim(joined, config)
    }
}
