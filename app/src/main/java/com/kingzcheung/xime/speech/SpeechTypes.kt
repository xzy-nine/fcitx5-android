/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Kingz Cheung
 *
 * 移植自 Xime (https://github.com/ximeiorg/xime)，见仓库根 NOTICE.md。
 */
package com.kingzcheung.xime.speech

enum class RecognitionState {
    IDLE,
    LISTENING,
    PROCESSING,
    ERROR
}

data class AudioConfig(
    val sampleRate: Int = 16000,
    val channels: Int = 1,
    val encoding: String = "pcm16"
)

data class SpeechResult(
    val text: String,
    val isFinal: Boolean,
    val confidence: Float = 1.0f
)