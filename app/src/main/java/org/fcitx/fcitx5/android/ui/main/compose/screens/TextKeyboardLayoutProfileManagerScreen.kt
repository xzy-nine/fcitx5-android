/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.screens

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.settings.behavior.data.LayoutDataManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.config.ConfigProviders
import org.fcitx.fcitx5.android.input.config.UserConfigFiles
import org.fcitx.fcitx5.android.ui.main.settings.behavior.TextKeyboardLayoutEditorActivity
import org.fcitx.fcitx5.android.ui.main.settings.behavior.utils.LayoutJsonUtils
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 一份布局文件的概览：名称、包含的布局键、是否可读与是否内置。 */
private data class LayoutProfile(
    val name: String,
    val keys: List<String>,
    val failed: Boolean,
    val builtin: Boolean,
)

/**
 * 文本键盘布局 profile 管理页。
 *
 * 每份 profile 展开后列出它包含的布局；可切换当前使用、编辑、新建、
 * 导入导出与重命名删除。
 */
@Composable
fun TextKeyboardLayoutProfileManagerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val keyboard = remember { AppPrefs.getInstance().keyboard }
    val activeProfile = remember {
        UserConfigFiles.normalizeTextKeyboardLayoutProfile(
            keyboard.textKeyboardLayoutProfile.getValue(),
        ) ?: UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE
    }

    var profiles by remember { mutableStateOf<List<LayoutProfile>>(emptyList()) }
    var selected by remember { mutableStateOf(activeProfile) }
    val expanded = remember { mutableStateSetOf(activeProfile) }

    LaunchedEffect(Unit) {
        profiles = withContext(Dispatchers.IO) {
            (UserConfigFiles.listTextKeyboardLayoutProfiles() + activeProfile).distinct()
                .sortedWith(
                    compareBy({ it != UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE }, { it }),
                )
                .map { name ->
                    val file = UserConfigFiles.textKeyboardLayoutJson(name)
                    val builtin = file != null && !file.exists()
                    val keys = if (builtin) listOf("default") else runCatching {
                        LayoutDataManager(context)
                            .parseJsonText(file!!.readText(), file.name, fallbackToDefault = false).keys.toList()
                    }.getOrDefault(emptyList())
                    LayoutProfile(name, keys, keys.isEmpty(), builtin)
                }
        }
        if (profiles.none { it.name == selected }) selected = activeProfile
    }

    fun display(name: String): String =
        if (name == UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE)
            context.getString(R.string.default_) else name

    fun openEditor(action: String? = null, layoutKey: String? = null) {
        context.startActivity(
            Intent(context, TextKeyboardLayoutEditorActivity::class.java).apply {
                putExtra(TextKeyboardLayoutEditorActivity.EXTRA_INITIAL_PROFILE, selected)
                layoutKey?.let { putExtra(TextKeyboardLayoutEditorActivity.EXTRA_INITIAL_LAYOUT, it) }
                action?.let { putExtra(TextKeyboardLayoutEditorActivity.EXTRA_MANAGER_ACTION, it) }
            },
        )
    }

    PageScaffold(
        title = stringResource(R.string.text_keyboard_layout_manage_title),
        onBack = onBack,
        floatingActionButton = {
            ArrowPreference(
                title = stringResource(R.string.text_keyboard_layout_file_create),
                modifier = Modifier.padding(16.dp),
                onClick = { openEditor(TextKeyboardLayoutEditorActivity.ACTION_CREATE_PROFILE) },
            )
        },
    ) {
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                Text(
                    text = stringResource(
                        R.string.text_keyboard_layout_manage_active,
                        display(activeProfile),
                    ),
                    modifier = Modifier.padding(16.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                ArrowPreference(
                    title = stringResource(R.string.text_keyboard_layout_file_rename),
                    onClick = { openEditor(TextKeyboardLayoutEditorActivity.ACTION_RENAME_PROFILE) },
                )
                ArrowPreference(
                    title = stringResource(R.string.text_keyboard_layout_file_delete),
                    onClick = { openEditor(TextKeyboardLayoutEditorActivity.ACTION_DELETE_PROFILE) },
                )
                ArrowPreference(
                    title = stringResource(R.string.text_keyboard_layout_qr_import_scan),
                    onClick = { openEditor(TextKeyboardLayoutEditorActivity.ACTION_IMPORT_PROFILE) },
                )
                ArrowPreference(
                    title = stringResource(R.string.text_keyboard_layout_qr_export),
                    onClick = { openEditor(TextKeyboardLayoutEditorActivity.ACTION_SHARE_PROFILE) },
                )
            }
        }

        item { SmallTitle(text = stringResource(R.string.text_keyboard_layout_manage_title)) }
        profiles.forEach { profile ->
            item {
                val isExpanded = profile.name in expanded
                Card(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    colors = CardDefaults.defaultColors(
                        color = if (profile.name == selected && isExpanded)
                            MiuixTheme.colorScheme.secondaryContainer
                        else MiuixTheme.colorScheme.surfaceContainerHighest,
                    ),
                ) {
                    ArrowPreference(
                        title = display(profile.name),
                        summary = if (profile.name == activeProfile) {
                            stringResource(R.string.text_keyboard_layout_manage_activate)
                        } else null,
                        onClick = {
                            if (isExpanded) expanded.remove(profile.name)
                            else expanded.add(profile.name)
                            selected = profile.name
                        },
                    )
                    if (profile.name != activeProfile) {
                        ArrowPreference(
                            title = stringResource(R.string.text_keyboard_layout_manage_activate),
                            onClick = {
                                keyboard.textKeyboardLayoutProfile.setValue(profile.name)
                                // 触发配置重载，使新 profile 立即生效
                                ConfigProviders.provider = ConfigProviders.provider
                                selected = profile.name
                                expanded.add(profile.name)
                            },
                        )
                    }
                    ArrowPreference(
                        title = stringResource(R.string.text_keyboard_layout_manage_edit),
                        onClick = {
                            selected = profile.name
                            openEditor()
                        },
                    )
                    AnimatedVisibility(visible = isExpanded) {
                        Column {
                            if (profile.failed) {
                                Text(
                                    text = stringResource(R.string.text_keyboard_layout_manage_read_failed),
                                    modifier = Modifier.padding(16.dp),
                                    color = MiuixTheme.colorScheme.error,
                                )
                            }
                            if (profile.builtin) {
                                Text(
                                    text = stringResource(R.string.text_keyboard_layout_manage_builtin),
                                    modifier = Modifier.padding(16.dp),
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            // 同一基础布局下的子布局按「默认 → 具名 → 分体」排列
                            val entries = profile.keys.mapNotNull { key ->
                                LayoutJsonUtils.parseEntryKey(key)?.let { key to it }
                            }
                            entries.groupBy { it.second.layoutName }.forEach { (base, layouts) ->
                                layouts.sortedWith(
                                    compareBy(
                                        { it.second.subLayout != null },
                                        { it.second.subLayout },
                                        { it.second.isSplit },
                                    ),
                                ).forEach { (key, identity) ->
                                    val sub = identity.subLayout
                                    val title = if (sub == null && !identity.isSplit) base else
                                        "• " + (sub ?: context.getString(R.string.default_)) +
                                            if (identity.isSplit) {
                                                " · " + context.getString(
                                                    R.string.text_keyboard_layout_split_layout,
                                                )
                                            } else ""
                                    ArrowPreference(
                                        title = title,
                                        onClick = {
                                            selected = profile.name
                                            openEditor(layoutKey = key)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}