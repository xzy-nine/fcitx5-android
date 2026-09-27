/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * 语音入口的处置方式。
 */
enum class VoiceEntryPlan {
    /** 打开 IME 内的语音面板（内置引擎，面板内再处理权限/模型缺失的引导）。 */
    OpenPanel,

    /** 回落到系统里另一个带 voice subtype 的输入法（保持本次改动前的既有行为）。 */
    SwitchExternalIme,

    /** 什么也不做（未启用语音输入且没有可回落的外部语音输入法）。 */
    Noop,
}

/**
 * custom: 语音入口的可用性判定（纯逻辑，便于单测）。
 *
 * 决策顺序（对应用户要求「内置优先，未启用/未就绪时回落到外部语音输入法」）：
 * 1. 未启用语音输入 → 有外部语音输入法就回落，否则什么也不做；
 * 2. 没有录音权限 → 仍打开面板（面板里可以一键授权，比静默失败可用）；
 * 3. 当前引擎未就绪（本地模型未下载 / 在线插件未配置）→ 有外部输入法就回落，
 *    否则打开面板引导用户去下载/配置；
 * 4. 其余情况 → 打开内置面板。
 */
object VoiceEntryRouting {

    fun plan(
        enabled: Boolean,
        permissionGranted: Boolean,
        useLocal: Boolean,
        localModelReady: Boolean,
        onlinePluginReady: Boolean,
        hasExternalVoiceIme: Boolean,
    ): VoiceEntryPlan {
        if (!enabled) {
            return if (hasExternalVoiceIme) VoiceEntryPlan.SwitchExternalIme else VoiceEntryPlan.Noop
        }
        if (!permissionGranted) return VoiceEntryPlan.OpenPanel
        val engineReady = if (useLocal) localModelReady else onlinePluginReady
        if (!engineReady) {
            return if (hasExternalVoiceIme) VoiceEntryPlan.SwitchExternalIme else VoiceEntryPlan.OpenPanel
        }
        return VoiceEntryPlan.OpenPanel
    }
}
