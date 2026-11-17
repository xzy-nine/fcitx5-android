/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.IconTheme
import org.fcitx.fcitx5.android.data.theme.IconThemeManager
import org.fcitx.fcitx5.android.ui.main.compose.dialog.SimpleConfirmDialog
import org.fcitx.fcitx5.android.ui.main.settings.icon.IconThemeActions
import org.fcitx.fcitx5.android.ui.main.settings.icon.IconThemeThumbnailUi
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 图标主题列表。
 *
 * 网格展示全部主题缩略图，点选即切换；下方集中导入与分享入口。
 * 缩略图沿用已有的 View 实现，通过互操作承载。
 */
@Composable
fun IconThemeListScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var themes by remember { mutableStateOf<List<IconTheme>>(emptyList()) }
    var activeName by remember { mutableStateOf(IconThemeManager.activeTheme.name) }
    var pendingShare by remember { mutableStateOf<IconTheme?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }

    fun reload() {
        themes = IconThemeManager.iconThemes
        activeName = IconThemeManager.activeTheme.name
    }

    // 主题可能在别处被改动，进入后周期性对齐一次即可
    LaunchedEffect(Unit) {
        IconThemeManager.initAsync()
        reload()
        val listListener = IconThemeManager.OnIconThemeListChangeListener { reload() }
        val changeListener = IconThemeManager.OnIconThemeChangeListener { reload() }
        IconThemeManager.addOnListChangeListener(listListener)
        IconThemeManager.addOnChangedListener(changeListener)
        try {
            while (true) {
                kotlinx.coroutines.delay(500)
                reload()
            }
        } finally {
            IconThemeManager.removeOnListChangeListener(listListener)
            IconThemeManager.removeOnChangedListener(changeListener)
        }
    }

    val jsonImport = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            IconThemeActions.importJson(context, uri)
                .onSuccess { context.toast(context.getString(R.string.icon_theme_imported, it.name)) }
                .onFailure {
                    Timber.w(it, "图标主题 JSON 导入失败")
                    context.toast(R.string.icon_theme_import_json_failed)
                }
            importing = false
            reload()
        }
    }

    val zipImport = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            IconThemeActions.importZip(context, uri)
                .onSuccess { context.toast(context.getString(R.string.icon_theme_imported, it.name)) }
                .onFailure {
                    Timber.w(it, "图标主题 ZIP 导入失败")
                    context.toast(context.getString(R.string.icon_theme_import_zip_failed, it.message.orEmpty()))
                }
            importing = false
            reload()
        }
    }

    val builtinName = remember { IconTheme.default().name }

    PageScaffold(title = stringResource(R.string.icon_theme), onBack = onBack) {
        item {
            SmallTitle(text = stringResource(R.string.icon_theme))
        }
        item {
            // 网格与入口同处一页：网格本身不滚动，高度按内容展开
            Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                themes.chunked(3).forEach { row ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(vertical = 2.dp),
                    ) {
                        row.forEach { theme ->
                            val isActive = theme.name == activeName
                            AndroidView(
                                factory = { ctx ->
                                    IconThemeThumbnailUi(ctx).also { it.root.tag = it }.root
                                },
                                update = { view ->
                                    val ui = view.tag as? IconThemeThumbnailUi ?: return@AndroidView
                                    ui.bind(theme, isActive, theme.name == builtinName)
                                    ui.setEditAction(visible = false) {}
                                    view.setOnClickListener {
                                        IconThemeManager.activeTheme = theme
                                        reload()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // 不足一行时留白，避免最后一格被拉伸
                        repeat(3 - row.size) {
                            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        item {
            Card(
                modifier = Modifier.padding(12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                ArrowPreference(
                    title = stringResource(R.string.icon_theme_new),
                    onClick = { showCreate = true },
                )
                ArrowPreference(
                    title = stringResource(R.string.icon_theme_import_json),
                    enabled = !importing,
                    onClick = { jsonImport.launch("application/json") },
                )
                ArrowPreference(
                    title = stringResource(R.string.icon_theme_import_zip),
                    enabled = !importing,
                    onClick = { zipImport.launch("application/zip") },
                )
                ArrowPreference(
                    title = stringResource(R.string.icon_theme_share_title),
                    enabled = themes.size > 1,
                    onClick = { pendingShare = themes.drop(1).firstOrNull() },
                )
                if (importing) {
                    Text(
                        text = stringResource(R.string.wanxiang_extra_loading),
                        modifier = Modifier.padding(16.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }

    if (showCreate) {
        SimpleConfirmDialog(
            title = stringResource(R.string.icon_theme_new),
            message = stringResource(R.string.new_icon_theme),
            onConfirm = {
                showCreate = false
            },
            onDismiss = { showCreate = false },
        )
    }

    pendingShare?.let { theme ->
        SimpleConfirmDialog(
            title = stringResource(R.string.icon_theme_share_title),
            message = theme.name,
            onConfirm = {
                pendingShare = null
                scope.launch {
                    if (IconThemeManager.collectFileReferences(theme).isNotEmpty()) {
                        IconThemeActions.shareAsZip(context, theme)
                    } else {
                        IconThemeActions.shareAsQrImage(context, theme)
                    }
                }
            },
            onDismiss = { pendingShare = null },
        )
    }
}
