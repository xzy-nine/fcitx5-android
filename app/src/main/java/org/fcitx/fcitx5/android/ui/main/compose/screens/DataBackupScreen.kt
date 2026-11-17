/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.screens

import android.os.Build
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.compose.AppRoute
import org.fcitx.fcitx5.android.utils.buildDocumentsProviderIntent
import org.fcitx.fcitx5.android.utils.buildPrimaryStorageIntent
import org.fcitx.fcitx5.android.utils.toast
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 数据与备份页。
 *
 * 汇集用户数据目录浏览与方案、词典的获取入口。
 * 导出导入在「高级」页，二者面向的操作场景不同：
 * 这里偏「数据在哪、从哪补充」，高级页偏「整体迁移」。
 */
@Composable
fun DataBackupScreen(
    onBack: () -> Unit,
    onNavigate: (AppRoute) -> Unit = {},
) {
    val context = LocalContext.current

    PageScaffold(title = stringResource(R.string.settings_group_data), onBack = onBack) {
        item {
            SmallTitle(text = stringResource(R.string.settings_group_data))
        }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                ArrowPreference(
                    title = stringResource(R.string.browse_user_data_dir),
                    onClick = {
                        runCatching { context.startActivity(buildDocumentsProviderIntent()) }
                            .onFailure { context.toast(it) }
                    },
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ArrowPreference(
                        title = stringResource(R.string.browse_primary_storage),
                        onClick = {
                            runCatching { context.startActivity(buildPrimaryStorageIntent()) }
                                .onFailure { context.toast(it) }
                        },
                    )
                }

            }
        }

        item {
            SmallTitle(text = stringResource(R.string.wanxiang_group))
        }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                ArrowPreference(
                    title = stringResource(R.string.direct_rime_import),
                    summary = stringResource(R.string.direct_rime_import_description),
                    onClick = { onNavigate(AppRoute.RimeImport) },
                )
                ArrowPreference(
                    title = stringResource(R.string.wanxiang_update),
                    summary = stringResource(R.string.wanxiang_update_summary),
                    onClick = { onNavigate(AppRoute.WanxiangUpdate) },
                )
                ArrowPreference(
                    title = stringResource(R.string.scheme_update_title),
                    summary = stringResource(R.string.scheme_update_summary),
                    onClick = { onNavigate(AppRoute.RimeSchemeUpdate) },
                )
            }
        }
    }
}
