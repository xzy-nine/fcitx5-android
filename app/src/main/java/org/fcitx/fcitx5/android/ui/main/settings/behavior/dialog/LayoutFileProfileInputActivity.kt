/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.dialog

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.common.SettingsUiActivity
import org.fcitx.fcitx5.android.ui.main.compose.screens.LayoutFileProfileInputScreen
import org.fcitx.fcitx5.android.ui.main.settings.behavior.data.LayoutEditorResultStore
import org.fcitx.fcitx5.android.utils.toast

/**
 * 布局文件名输入的调用入口。
 *
 * 界面由 Compose 渲染；本类负责读取调用方参数、回送结果，并保留最近一次
 * 结果给需要跨重建读取的调用方。
 */
class LayoutFileProfileInputActivity : SettingsUiActivity() {

    data class ResultPayload(
        val action: String,
        val profile: String,
        val copyCurrent: Boolean,
        val resultIntent: Intent? = null,
    )

    companion object {
        const val EXTRA_ACTION = "action"
        const val EXTRA_INITIAL_PROFILE = "initial_profile"
        const val EXTRA_SHOW_COPY_SWITCH = "show_copy_switch"
        const val EXTRA_COPY_CURRENT_DEFAULT = "copy_current_default"
        const val EXTRA_RESULT_PROFILE = "result_profile"
        const val EXTRA_RESULT_COPY_CURRENT = "result_copy_current"

        const val ACTION_CREATE = "create"
        const val ACTION_RENAME = "rename"

        private var pendingResultPayload: ResultPayload? = null

        fun consumePendingResultPayload(): ResultPayload? {
            val payload = pendingResultPayload
            pendingResultPayload = null
            return payload
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent.getStringExtra(EXTRA_ACTION) ?: ACTION_CREATE
        setContent {
            LayoutFileProfileInputScreen(
                isRename = action == ACTION_RENAME,
                initialProfile = intent.getStringExtra(EXTRA_INITIAL_PROFILE).orEmpty(),
                showCopySwitch = intent.getBooleanExtra(EXTRA_SHOW_COPY_SWITCH, false),
                copyCurrentDefault = intent.getBooleanExtra(EXTRA_COPY_CURRENT_DEFAULT, true),
                onConfirm = { profile, copyCurrent ->
                    val data = Intent().apply {
                        putExtra(EXTRA_ACTION, action)
                        putExtra(EXTRA_RESULT_PROFILE, profile)
                        putExtra(EXTRA_RESULT_COPY_CURRENT, copyCurrent)
                    }
                    LayoutEditorResultStore.attachRequestToken(intent, data)
                    pendingResultPayload = ResultPayload(action, profile, copyCurrent, data)
                    setResult(RESULT_OK, data)
                    finish()
                },
                onBack = { finish() },
                onInvalidName = { toast(R.string.text_keyboard_layout_file_name_invalid) },
            )
        }
    }
}
