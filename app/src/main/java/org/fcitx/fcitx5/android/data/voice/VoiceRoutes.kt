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

    fun open(context: Context, route: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_RUN
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA, route)
        }
        context.startActivity(intent)
    }
}

/**
 * custom: 在线 ASR 插件的「配置表单」入口。
 *
 * 插件配置表单由插件框架桥接层（P5）在初始化时注入；未注入时设置页只显示「未配置」。
 */
object VoicePluginConfigEntryPoint {

    @Volatile
    var openConfig: ((Context, String) -> Unit)? = null

    fun open(context: Context, pluginId: String): Boolean {
        val entry = openConfig ?: return false
        entry(context, pluginId)
        return true
    }
}
