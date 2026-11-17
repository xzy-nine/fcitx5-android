/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.screens

import android.content.Intent
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.fcitx.fcitx5.android.ui.main.compose.AppRoute
import org.fcitx.fcitx5.android.ui.main.compose.appRouteOf
import org.fcitx.fcitx5.android.ui.main.settings.group.SettingsGroupAction
import org.fcitx.fcitx5.android.ui.main.settings.group.SettingsGroupSpecs
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置分组页。
 *
 * 一组带图标的跳转入口由规格表描述；找不到规格时只显示空页，
 * 不因过期路由闪退。
 */
@Composable
fun SettingsGroupScreen(
    groupId: String,
    onBack: () -> Unit,
    onNavigate: (AppRoute) -> Unit,
) {
    val context = LocalContext.current
    val spec = SettingsGroupSpecs.find(groupId)
    if (spec == null) {
        PageScaffold(title = "", onBack = onBack) { }
        return
    }

    val entries = spec.entries(context)
    PageScaffold(title = stringResource(spec.title), onBack = onBack) {
        var lastSection: Int? = null
        entries.forEach { entry ->
            if (entry.sectionTitle != null && entry.sectionTitle != lastSection) {
                lastSection = entry.sectionTitle
                item { SmallTitle(text = stringResource(entry.sectionTitle)) }
            }
            item {
                Card(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    colors = CardDefaults.defaultColors(
                        color = MiuixTheme.colorScheme.surfaceContainerHighest,
                    ),
                ) {
                    ArrowPreference(
                        title = stringResource(entry.title),
                        summary = entry.summary?.let { stringResource(it) },
                        onClick = {
                            when (val action = entry.action) {
                                is SettingsGroupAction.Route ->
                                    onNavigate(appRouteOf(action.route))
                                is SettingsGroupAction.Launch -> {
                                    context.startActivity(
                                        Intent(context, action.activityClass),
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}