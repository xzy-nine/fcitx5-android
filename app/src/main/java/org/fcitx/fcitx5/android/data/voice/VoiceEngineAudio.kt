/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 送入语音引擎的音频分块工具。
 *
 * 分块上限同时服务两条约束：跨进程 AIDL 单次传参有大小上限，在线流式平台也按小块收音频。
 * 采集链路的分块粒度（100ms @16kHz）与这里一致，两条路径共用同一个块长。
 */
package org.fcitx.fcitx5.android.data.voice

/** 单次送入引擎的采样数（100ms @16kHz）。 */
internal const val PUSH_CHUNK_SAMPLES = 1600

/** 分块对应的音频时长（毫秒）：16kHz 下 [PUSH_CHUNK_SAMPLES] 恰好是 100ms。 */
internal const val PUSH_CHUNK_MS = 100L

/** 按 [PUSH_CHUNK_SAMPLES] 分块回调；每块都是独立数组，跨进程与异步消费都不共享缓冲。 */
internal inline fun FloatArray.forEachPushChunk(action: (FloatArray) -> Unit) {
    var offset = 0
    while (offset < size) {
        val end = (offset + PUSH_CHUNK_SAMPLES).coerceAtMost(size)
        action(copyOfRange(offset, end))
        offset = end
    }
}
