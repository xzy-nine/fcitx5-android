/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.compose.AppRoute
import org.fcitx.fcitx5.android.ui.main.compose.PrefCategory
import org.fcitx.fcitx5.android.ui.main.compose.RawConfigHostType
import org.fcitx.fcitx5.android.utils.Const
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Help
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Merge
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.MoreCircle
import top.yukonga.miuix.kmp.icon.extended.Promotions
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.icon.extended.Translate
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.icon.extended.UploadCloud
import top.yukonga.miuix.kmp.preference.ArrowPreference

private data class HomeDestination(
    @StringRes val title: Int,
    val icon: ImageVector,
    val route: AppRoute,
)

@Composable
fun HomeScreen(
    onNavigate: (AppRoute) -> Unit,
    onOpenUrl: (String) -> Unit,
    onSearch: () -> Unit,
) {
    val appName = stringResource(R.string.app_name)

    PageScaffold(
        title = appName,
        actions = {
            IconButton(onClick = onSearch) {
                Icon(
                    MiuixIcons.Search,
                    contentDescription = stringResource(R.string.search),
                    modifier = Modifier.size(24.dp),
                )
            }
            IconButton(onClick = { onOpenUrl(Const.faqUrl) }) {
                Icon(
                    MiuixIcons.Help,
                    contentDescription = stringResource(R.string.help),
                    modifier = Modifier.size(24.dp),
                )
            }
            IconButton(onClick = { onNavigate(AppRoute.Developer) }) {
                Icon(
                    MiuixIcons.Info,
                    contentDescription = stringResource(R.string.developer),
                    modifier = Modifier.size(24.dp),
                )
            }
            IconButton(onClick = { onNavigate(AppRoute.About) }) {
                Icon(
                    MiuixIcons.MoreCircle,
                    contentDescription = stringResource(R.string.about),
                    modifier = Modifier.size(24.dp),
                )
            }
        },
    ) {
        item {
            SmallTitle(text = "Fcitx")
        }
        item {
            HomeCard(homeFcitxItems, onNavigate)
        }
        item {
            SmallTitle(text = stringResource(R.string.physical_keyboard))
        }
        item {
            HomeCard(homePhysicalKeyboardItems, onNavigate)
        }
        item {
            SmallTitle(text = "Android")
        }
        item {
            HomeCard(homeAndroidItems, onNavigate)
        }
    }
}

@Composable
private fun HomeCard(
    items: List<HomeDestination>,
    onNavigate: (AppRoute) -> Unit,
) {
    Card(
        modifier = Modifier.padding(horizontal = 12.dp),
    ) {
        items.forEachIndexed { index, dest ->
            ArrowPreference(
                title = stringResource(dest.title),
                startAction = {
                    Icon(dest.icon, contentDescription = null, modifier = Modifier.size(24.dp))
                },
                onClick = { onNavigate(dest.route) },
            )
            if (index < items.lastIndex) {
                HorizontalDivider()
            }
        }
    }
}

private val homeFcitxItems = listOf(
    HomeDestination(R.string.global_options, MiuixIcons.Tune, AppRoute.RawConfigHost(
        RawConfigHostType.GlobalConfig)),
    HomeDestination(R.string.input_methods, MiuixIcons.Translate, AppRoute.InputMethodList),
    HomeDestination(R.string.addons, MiuixIcons.Merge, AppRoute.AddonList),
)

private val homePhysicalKeyboardItems = listOf(
    HomeDestination(R.string.hotkey, MiuixIcons.Tune, AppRoute.RawConfigHost(RawConfigHostType.PhysicalHotkey)),
    HomeDestination(R.string.candidates_window, MiuixIcons.ListView, AppRoute.Prefs(PrefCategory.Candidates)),
)

private val homeAndroidItems = listOf(
    HomeDestination(R.string.theme, MiuixIcons.Theme, AppRoute.Theme),
    HomeDestination(R.string.virtual_keyboard, MiuixIcons.GridView, AppRoute.Prefs(PrefCategory.Keyboard)),
    HomeDestination(R.string.clipboard, MiuixIcons.Copy, AppRoute.Prefs(PrefCategory.Clipboard)),
    HomeDestination(R.string.broadcast_settings, MiuixIcons.Promotions, AppRoute.Prefs(PrefCategory.Broadcast)),
    HomeDestination(R.string.emoji_and_symbols, MiuixIcons.Messages, AppRoute.Prefs(PrefCategory.Symbols)),
    HomeDestination(R.string.plugins, MiuixIcons.Layers, AppRoute.PluginList),
    HomeDestination(R.string.webdav_settings_title, MiuixIcons.UploadCloud, AppRoute.WebDavSync),
    HomeDestination(R.string.advanced, MiuixIcons.More, AppRoute.Prefs(PrefCategory.Advanced)),
)
