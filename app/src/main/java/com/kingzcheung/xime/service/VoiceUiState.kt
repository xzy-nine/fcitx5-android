/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Kingz Cheung
 *
 * 从 Xime (https://github.com/ximeiorg/xime) 的 service/InputUIState.kt 裁剪而来，
 * 只保留语音输入相关的字段（上游是 79 字段的全局 UI 状态），见仓库根 NOTICE.md。
 */
package com.kingzcheung.xime.service

import com.kingzcheung.xime.speech.RecognitionState

data class VoiceUiState(
    /** 是否处于语音面板（语音模式）。 */
    val isVoiceMode: Boolean = false,
    /** 自动模式（点按开始/点按结束）。 */
    val voiceSticky: Boolean = false,
    val voiceButtonState: VoiceButtonState = VoiceButtonState(),
    /** 当前引擎展示名（本地 Zipformer / 插件名）。 */
    val voicePluginName: String = "",
    val voiceRecognitionState: RecognitionState = RecognitionState.IDLE,
    /** 最近一次部分识别结果（面板显示用；输入框侧由 composing 承载）。 */
    val voiceRecognizedText: String = "",
    val voiceAmplitude: Float = 0f,
)
