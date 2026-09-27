/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.voice

import android.content.Context
import android.content.Intent
import org.fcitx.fcitx5.android.ui.main.MainActivity

/**
 * custom: 从 IME 内跳到应用内语音页面的深链。
 *
 * IME（Service）不能直接操作 MainActivity 的 Compose 返回栈，因此沿用仓库既有的
 * `Intent.ACTION_RUN` + extra 方案（原来用于 [org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute]），
 * 这里用独立的 extra 承载语音页面，避免改动上游 `SettingsRoute.kt`。
 */
object VoiceRoutes {

    const val EXTRA = "org.fcitx.fcitx5.android.EXTRA_VOICE_ROUTE"

    /** 语音输入设置页。 */
    const val SETTINGS = "settings"

    /** 模型市场（下载/删除）页。 */
    const val MODELS = "models"

    /** 在线插件配置表单页（需要配合 [EXTRA_PLUGIN_ID]）。 */
    const val PLUGIN_CONFIG = "plugin_config"

    const val EXTRA_PLUGIN_ID = "org.fcitx.fcitx5.android.EXTRA_VOICE_PLUGIN_ID"

    fun open(context: Context, route: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_RUN
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA, route)
        }
        context.startActivity(intent)
    }

    /** 打开某个在线插件的配置表单（IME 与设置页共用同一机制）。 */
    fun openPluginConfig(context: Context, pluginId: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_RUN
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA, PLUGIN_CONFIG)
            putExtra(EXTRA_PLUGIN_ID, pluginId)
        }
        context.startActivity(intent)
    }
}

/**
 * custom: 在线 ASR 插件的「配置表单」入口。
 *
 * 表单页在应用内（MainActivity Compose 导航），因此 IME 与设置页都通过
 * [VoiceRoutes.openPluginConfig] 跳转（singleTask 会走 onNewIntent → 导航）。
 */
object VoicePluginConfigEntryPoint {

    fun open(context: Context, pluginId: String) {
        VoiceRoutes.openPluginConfig(context, pluginId)
    }
}
