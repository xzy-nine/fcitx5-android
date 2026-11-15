/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 语音识别的会话状态（面板文案与按钮态的唯一来源）。
 */
package org.fcitx.fcitx5.android.data.voice

enum class VoiceRecognitionState {
    /** 未开始/已结束。 */
    IDLE,

    /** 引擎正在加载（首次使用或空闲释放后重新加载，130MB 级模型要 1–3 秒）。 */
    PREPARING,

    /** 正在采集（可能同时已有部分结果）。 */
    LISTENING,

    /** 已停止采集，等待最终结果（批次型在线引擎在此停留较久）。 */
    PROCESSING,

    /** 引擎报错 / 未就绪。 */
    ERROR,
}

/**
 * IME 语音面板状态。
 *
 * 只保留面板真正消费的字段：识别状态、引擎展示名、部分结果文本。
 */
data class VoiceUiState(
    /** 是否处于语音面板（语音模式）。 */
    val isVoiceMode: Boolean = false,
    /** 当前引擎展示名（本地 Zipformer / 在线平台名）。 */
    val engineName: String = "",
    val recognitionState: VoiceRecognitionState = VoiceRecognitionState.IDLE,
    /** 最近一次部分识别结果（面板显示用；输入框侧由 composing 承载）。 */
    val recognizedText: String = "",
    /** 引擎错误文案（面板提示用）。 */
    val error: String? = null,
)
