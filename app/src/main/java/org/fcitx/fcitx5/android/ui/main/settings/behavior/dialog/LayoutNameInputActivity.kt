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
import org.fcitx.fcitx5.android.ui.main.compose.screens.LayoutNameInputScreen
import org.fcitx.fcitx5.android.ui.main.settings.behavior.data.LayoutEditorResultStore
import org.fcitx.fcitx5.android.utils.toast

/**
 * 布局名称输入的调用入口。
 *
 * 界面由 Compose 渲染；本类负责读取调用方参数并回送结果。
 */
class LayoutNameInputActivity : SettingsUiActivity() {

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_LABEL = "label"
        const val EXTRA_HINT = "hint"
        const val EXTRA_INITIAL_VALUE = "initial_value"
        const val EXTRA_COPY_SOURCE_OPTIONS = "copy_source_options"
        const val EXTRA_COPY_SOURCE_LABELS = "copy_source_labels"
        const val EXTRA_COPY_SOURCE_DEFAULT = "copy_source_default"
        const val EXTRA_RESULT_LAYOUT_NAME = "result_layout_name"
        const val EXTRA_RESULT_COPY_SOURCE = "result_copy_source"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val copySources = intent.getStringArrayListExtra(EXTRA_COPY_SOURCE_OPTIONS)?.toList().orEmpty()
        val copyLabels = intent.getStringArrayListExtra(EXTRA_COPY_SOURCE_LABELS)?.toList()
            ?.takeIf { it.size == copySources.size }
            ?: copySources
        setContent {
            LayoutNameInputScreen(
                title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                label = intent.getStringExtra(EXTRA_LABEL).orEmpty(),
                hint = intent.getStringExtra(EXTRA_HINT).orEmpty(),
                initialValue = intent.getStringExtra(EXTRA_INITIAL_VALUE).orEmpty(),
                copySources = copySources,
                copySourceLabels = copyLabels,
                copySourceDefault = intent.getStringExtra(EXTRA_COPY_SOURCE_DEFAULT).orEmpty(),
                onConfirm = { name, copySource ->
                    setResult(
                        RESULT_OK,
                        LayoutEditorResultStore.attachRequestToken(intent, Intent().apply {
                            putExtra(EXTRA_RESULT_LAYOUT_NAME, name)
                            copySource?.let { putExtra(EXTRA_RESULT_COPY_SOURCE, it) }
                        }),
                    )
                    finish()
                },
                onBack = { finish() },
                onEmptyName = { toast(R.string.text_keyboard_layout_name_empty) },
            )
        }
    }
}