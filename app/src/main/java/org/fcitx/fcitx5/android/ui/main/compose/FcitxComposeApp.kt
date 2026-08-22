/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.MainActivity
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.setup.SetupActivity
import org.fcitx.fcitx5.android.utils.parcelable
import org.fcitx.fcitx5.android.utils.startActivity
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

@Composable
fun FcitxComposeApp(activity: MainActivity, shell: ComposeMainShell) {
    val themeController = remember { ThemeController(ColorSchemeMode.System) }
    MiuixTheme(controller = themeController) {
        val backStack = rememberNavBackStack<AppRoute>(AppRoute.Index)
        val runtime = remember { LegacyNavRuntime() }
        shell.legacyNavController = { runtime.navController }
        var importDictUri by remember { mutableStateOf<String?>(null) }

        fun interceptTargets(route: AppRoute): Boolean = route is AppRoute.Legacy ||
            route is AppRoute.LegacyInputMethodConfig || route is AppRoute.LegacyPinyinDict

        fun navigateTo(appRoute: AppRoute) {
            if (backStack.lastOrNull() == appRoute) return
            backStack.removeAll { it !is AppRoute.Index }
            backStack.add(appRoute)
        }

        LaunchedEffect(Unit) {
            shell.intents.collect { intent ->
                when (intent.action) {
                    Intent.ACTION_MAIN -> {
                        if (SetupActivity.shouldShowUp()) activity.startActivity<SetupActivity>()
                    }
                    Intent.ACTION_VIEW -> {
                        importDictUri = intent.data?.toString()
                    }
                    Intent.ACTION_RUN -> {
                        val route = intent.parcelable<SettingsRoute>(MainActivity.EXTRA_SETTINGS_ROUTE)
                            ?: return@collect
                        navigateTo(appRouteOf(route))
                    }
                }
            }
        }

        CompositionLocalProvider(LocalLegacyNavRuntime provides runtime) {
            NotificationPermissionFlow()
            DictImportDialog(
                uri = importDictUri,
                onConfirm = { uri ->
                    importDictUri = null
                    navigateTo(AppRoute.LegacyPinyinDict(uri))
                },
                onDismiss = { importDictUri = null },
            )
            NavDisplay(
                backStack = backStack,
                transition = NavTransitions.MiuixDefault,
                onBack = {
                    when {
                        runtime.popLegacyBackStack() -> Unit
                        backStack.size > 1 -> backStack.removeLastOrNull()
                        else -> activity.moveTaskToBack(false)
                    }
                },
            ) {
                entry<AppRoute.Index> {
                    HomeScreen(
                        onNavigate = ::navigateTo,
                        onOpenUrl = { url ->
                            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        },
                        onSearch = {
                            org.fcitx.fcitx5.android.ui.main.settings.SettingsSearchManager
                                .showSearchDialog(activity)
                        },
                    )
                }
                entry<AppRoute.About> {
                    AboutScreen(
                        onBack = { backStack.removeLastOrNull() },
                        onNavigate = ::navigateTo,
                        onOpenUrl = { url ->
                            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        },
                    )
                }
                entry<AppRoute.Licenses> {
                    LicensesScreen(
                        onBack = { backStack.removeLastOrNull() },
                        onOpenUrl = { url ->
                            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        },
                    )
                }
                entry<AppRoute.Developer> {
                    DeveloperScreen(onBack = { backStack.removeLastOrNull() })
                }
                entry<AppRoute.Legacy> { route ->
                    LegacyScreen(LegacyTargets.routeOf(route.target))
                }
                entry<AppRoute.LegacyInputMethodConfig> { route ->
                    LegacyScreen(SettingsRoute.InputMethodConfig(route.name, route.uniqueName))
                }
                entry<AppRoute.LegacyPinyinDict> { route ->
                    LegacyScreen(SettingsRoute.PinyinDict(route.uri))
                }
                entry<AppRoute.Prefs> { route ->
                    ManagedPrefsScreen(
                        category = route.category.provider(),
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<AppRoute.RawConfigHost> { route ->
                    RawConfigHostScreen(
                        route = route,
                        onNavigate = ::navigateTo,
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<AppRoute.LegacyPunctuation> { route ->
                    LegacyScreen(SettingsRoute.Punctuation(route.title, route.lang))
                }
            }
        }
    }
}

private fun appRouteOf(route: SettingsRoute): AppRoute = when (route) {
    SettingsRoute.Index -> AppRoute.Legacy(LegacyTarget.Index)
    SettingsRoute.GlobalConfig -> AppRoute.Legacy(LegacyTarget.GlobalConfig)
    SettingsRoute.InputMethodList -> AppRoute.Legacy(LegacyTarget.InputMethodList)
    is SettingsRoute.InputMethodConfig -> AppRoute.LegacyInputMethodConfig(route.name, route.uniqueName)
    SettingsRoute.AddonList -> AppRoute.Legacy(LegacyTarget.AddonList)
    SettingsRoute.Theme -> AppRoute.Legacy(LegacyTarget.Theme)
    SettingsRoute.VirtualKeyboard -> AppRoute.Legacy(LegacyTarget.VirtualKeyboard)
    SettingsRoute.CandidatesWindow -> AppRoute.Legacy(LegacyTarget.CandidatesWindow)
    SettingsRoute.Clipboard -> AppRoute.Legacy(LegacyTarget.Clipboard)
    SettingsRoute.Broadcast -> AppRoute.Legacy(LegacyTarget.Broadcast)
    SettingsRoute.Symbol -> AppRoute.Legacy(LegacyTarget.Symbol)
    SettingsRoute.Plugin -> AppRoute.Legacy(LegacyTarget.Plugin)
    SettingsRoute.Advanced -> AppRoute.Legacy(LegacyTarget.Advanced)
    SettingsRoute.Developer -> AppRoute.Developer
    SettingsRoute.License -> AppRoute.Licenses
    SettingsRoute.About -> AppRoute.About
    SettingsRoute.TableInputMethods -> AppRoute.Legacy(LegacyTarget.TableInputMethods)
    SettingsRoute.QuickPhraseList -> AppRoute.Legacy(LegacyTarget.QuickPhraseList)
    SettingsRoute.PinyinCustomPhrase -> AppRoute.Legacy(LegacyTarget.PinyinCustomPhrase)
    // parameterized legacy routes that are not reachable through a fixed entry:
    // they are only produced by intent extras, so map to a fresh legacy entry hardcoding the
    // resolver-side handling on next intent-driven visit.
    is SettingsRoute.AddonConfig -> AppRoute.Legacy(LegacyTarget.Index)
    is SettingsRoute.ListConfig -> AppRoute.Legacy(LegacyTarget.Index)
    is SettingsRoute.PinyinDict -> AppRoute.LegacyPinyinDict(route.uri.orEmpty())
    is SettingsRoute.Punctuation -> AppRoute.Legacy(LegacyTarget.Index)
    is SettingsRoute.QuickPhraseEdit -> AppRoute.Legacy(LegacyTarget.Index)
}
