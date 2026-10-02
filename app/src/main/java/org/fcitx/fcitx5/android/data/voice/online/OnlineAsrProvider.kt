/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 在线识别平台的「集中接口」。
 *
 * 每个平台按自己的官方开发文档实现（WebSocket / REST），但对外只暴露同一套契约：
 * 平台元信息、配置字段、会话生命周期（start/pushAudio/finish/cancel）。
 * 会话层（[org.fcitx.fcitx5.android.data.voice.VoiceSession]）与设置页都只认这一层，
 * 因此新增平台 = 加一个实现 + 注册，不需要动 UI 与会话逻辑。
 *
 * 能力位说明：
 *  - [OnlineAsrProvider.streamsAudio]：说话过程中就要音频（火山 WebSocket）；
 *    false 表示松手后整段提交（MiMo 走 REST 上传 wav）。
 *  - [OnlineAsrProvider.streamsText]：最终结果之前能给出增量文本（火山 partial / MiMo SSE delta）。
 */
package org.fcitx.fcitx5.android.data.voice.online

import android.content.Context
import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.data.prefs.AppPrefs

interface OnlineAsrProvider {

    /** 稳定 id（存进 `AppPrefs.voice.voiceOnlineProviderId`）。 */
    val id: String

    @get:StringRes
    val nameRes: Int

    /** true=说话中持续送音频；false=松手后整段提交。 */
    val streamsAudio: Boolean

    /** 是否能在最终结果前返回增量文本。 */
    val streamsText: Boolean

    /** 凭据是否齐备（设置页据此标「未配置」）。 */
    fun isConfigured(context: Context): Boolean

    /** 设置页要渲染的字段（读写闭包由平台自己给出，UI 不关心存储细节）。 */
    fun settings(): List<OnlineSetting>

    fun newSession(context: Context, callback: OnlineAsrCallback): OnlineAsrSession
}

/** 一个平台配置项。 */
data class OnlineSetting(
    val key: String,
    @StringRes val label: Int,
    val secret: Boolean = true,
    /** 非空则渲染成单选（值即选项之一）。 */
    val options: List<String> = emptyList(),
    val read: () -> String,
    val write: (String) -> Unit,
)

interface OnlineAsrSession {
    fun start()

    /** 16kHz 单声道 float 采样（[-1,1]）；仅 [OnlineAsrProvider.streamsAudio] 的平台会用到。 */
    fun pushAudio(samples: FloatArray)

    /** 结束本次会话：流式平台发尾包等下最终结果；批次平台在此整段上传。 */
    fun finish()

    /** 取消：丢弃本次会话，迟到结果不得回调。 */
    fun cancel()

    fun release()
}

interface OnlineAsrCallback {
    /** 增量文本（已含此前内容，可直接覆盖 composing）。 */
    fun onPartial(text: String)

    fun onFinal(text: String)

    fun onError(message: String)
}

object OnlineAsrRegistry {

    /** 内置平台（新增平台只需在这里加一项）。 */
    val providers: List<OnlineAsrProvider> = listOf(
        VolcengineAsrProvider,
        MiMoAsrProvider,
    )

    fun byId(id: String): OnlineAsrProvider? = providers.firstOrNull { it.id == id }

    fun configured(context: Context): List<OnlineAsrProvider> =
        providers.filter { it.isConfigured(context) }

    /** 当前选中的平台：偏好优先 → 第一个已配置 → 第一个。 */
    fun selected(context: Context): OnlineAsrProvider? {
        val id = runCatching {
            AppPrefs.getInstance().voice.voiceOnlineProviderId.getValue()
        }.getOrDefault("")
        return byId(id) ?: configured(context).firstOrNull() ?: providers.firstOrNull()
    }
}
