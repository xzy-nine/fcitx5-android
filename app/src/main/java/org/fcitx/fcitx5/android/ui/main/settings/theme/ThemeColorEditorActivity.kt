/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContract
import kotlinx.parcelize.Parcelize
import org.fcitx.fcitx5.android.ui.common.SettingsUiActivity
import org.fcitx.fcitx5.android.ui.main.compose.screens.ThemeColorEditorScreen
import org.fcitx.fcitx5.android.utils.parcelable

/**
 * 颜色编辑器的调用入口。
 *
 * 页面由 Compose 渲染；本类只负责输入参数的传递与结果的回送。
 */
class ThemeColorEditorActivity : SettingsUiActivity() {

    @Parcelize
    data class EditorInput(
        val fieldName: String,
        @androidx.annotation.StringRes val titleRes: Int,
        val initialColor: Int,
        val allowClear: Boolean = false,
    ) : Parcelable

    @Parcelize
    data class EditorResult(
        val fieldName: String,
        val color: Int?,
    ) : Parcelable

    class Contract : ActivityResultContract<EditorInput, EditorResult?>() {
        override fun createIntent(context: Context, input: EditorInput): Intent =
            Intent(context, ThemeColorEditorActivity::class.java).apply {
                putExtra(EXTRA_INPUT, input)
            }

        override fun parseResult(resultCode: Int, intent: Intent?): EditorResult? =
            intent?.parcelable<EditorResult>(EXTRA_RESULT)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val input: EditorInput = intent.parcelable<EditorInput>(EXTRA_INPUT) ?: run {
            finish()
            return
        }
        setContent {
            ThemeColorEditorScreen(
                title = getString(input.titleRes),
                initialColor = input.initialColor,
                allowClear = input.allowClear,
                onResult = { color ->
                    setResult(
                        RESULT_OK,
                        Intent().apply { putExtra(EXTRA_RESULT, EditorResult(input.fieldName, color)) },
                    )
                    finish()
                },
                onBack = { finish() },
            )
        }
    }

    companion object {
        const val EXTRA_INPUT = "theme_color_editor_input"
        const val EXTRA_RESULT = "theme_color_editor_result"
    }
}