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

    /** 在线识别平台配置页（需要配合 [EXTRA_PROVIDER_ID]）。 */
    const val PROVIDER_CONFIG = "provider_config"

    const val EXTRA_PROVIDER_ID = "org.fcitx.fcitx5.android.EXTRA_VOICE_PROVIDER_ID"

    fun open(context: Context, route: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_RUN
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA, route)
        }
        context.startActivity(intent)
    }

    /** 打开某个在线识别平台的配置表单（IME 与设置页共用同一机制）。 */
    fun openProviderConfig(context: Context, providerId: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_RUN
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA, PROVIDER_CONFIG)
            putExtra(EXTRA_PROVIDER_ID, providerId)
        }
        context.startActivity(intent)
    }
}

/**
 * custom: 在线识别平台的「配置表单」入口。
 *
 * 表单页在应用内（MainActivity Compose 导航），因此 IME 与设置页都通过
 * [VoiceRoutes.openProviderConfig] 跳转（singleTask 会走 onNewIntent → 导航）。
 */
object VoiceProviderConfigEntryPoint {

    fun open(context: Context, providerId: String) {
        VoiceRoutes.openProviderConfig(context, providerId)
    }
}
