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
            // Navigating to the home index always collapses the stack back to the root.
            if (appRoute is AppRoute.Index) {
                backStack.clear()
                backStack.add(AppRoute.Index)
                return
            }
            // Entering a non-legacy destination while a stale legacy controller lingers (its
            // onDispose detach is async) would let popLegacyBackStack consume a back press on
            // the new page. Detach it synchronously so no ghost controller survives.
            if (appRoute !is AppRoute.Legacy &&
                appRoute !is AppRoute.LegacyInputMethodConfig &&
                appRoute !is AppRoute.LegacyPinyinDict &&
                appRoute !is AppRoute.LegacyPunctuation &&
                runtime.navController != null
            ) {
                runtime.detach()
            }
            // Push on top of the current stack so deeper levels (e.g. IM list -> IM config)
            // keep a proper back chain instead of collapsing to the home screen.
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
                        onSearch = { navigateTo(AppRoute.SettingsSearch) },
                    )
                }
                entry<AppRoute.SettingsSearch> {
                    SettingsSearchScreen(
                        onNavigate = ::navigateTo,
                        onBack = { backStack.removeLastOrNull() },
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
                entry<AppRoute.InputMethodList> {
                    InputMethodListScreen(
                        onOpenConfig = { name, uniqueName ->
                            navigateTo(AppRoute.InputMethodConfig(name, uniqueName))
                        },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<AppRoute.InputMethodConfig> { route ->
                    RawConfigHostScreen(
                        route = AppRoute.RawConfigHost(
                            kind = RawConfigHostType.InputMethodConfig,
                            name = route.name,
                            uniqueName = route.uniqueName,
                        ),
                        onNavigate = ::navigateTo,
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<AppRoute.AddonList> {
                    AddonListScreen(
                        onOpenConfig = { name, uniqueName ->
                            navigateTo(AppRoute.AddonConfig(name, uniqueName))
                        },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<AppRoute.AddonConfig> { route ->
                    RawConfigHostScreen(
                        route = AppRoute.RawConfigHost(
                            kind = RawConfigHostType.AddonConfig,
                            name = route.name,
                            uniqueName = route.uniqueName,
                        ),
                        onNavigate = ::navigateTo,
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<AppRoute.PluginList> {
                    PluginListScreen(onBack = { backStack.removeLastOrNull() })
                }
                entry<AppRoute.QuickPhraseList> {
                    QuickPhraseListScreen(
                        onEdit = { fileName ->
                            navigateTo(AppRoute.QuickPhraseEdit(fileName))
                        },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<AppRoute.QuickPhraseEdit> { route ->
                    QuickPhraseEditScreen(
                        fileName = route.fileName,
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<AppRoute.TableInputMethods> {
                    TableInputMethodsScreen(onBack = { backStack.removeLastOrNull() })
                }
                entry<AppRoute.PinyinCustomPhrase> {
                    PinyinCustomPhraseScreen(onBack = { backStack.removeLastOrNull() })
                }
                entry<AppRoute.PinyinDictionary> { route ->
                    PinyinDictionaryScreen(
                        initialUri = route.uri,
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<AppRoute.Punctuation> { route ->
                    PunctuationScreen(
                        title = route.title,
                        lang = route.lang,
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<AppRoute.Theme> {
                    ThemeScreen(onBack = { backStack.removeLastOrNull() })
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
                    if (route.category == PrefCategory.Broadcast) {
                        BroadcastScreen(onBack = { backStack.removeLastOrNull() })
                    } else {
                        ManagedPrefsScreen(
                            category = route.category.provider(),
                            onBack = { backStack.removeLastOrNull() },
                            highlightKey = route.highlightKey,
                        )
                    }
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

fun appRouteOf(route: SettingsRoute): AppRoute = when (route) {
    SettingsRoute.Index -> AppRoute.Legacy(LegacyTarget.Index)
    SettingsRoute.GlobalConfig -> AppRoute.RawConfigHost(RawConfigHostType.GlobalConfig)
    SettingsRoute.InputMethodList -> AppRoute.InputMethodList
    is SettingsRoute.InputMethodConfig -> AppRoute.InputMethodConfig(route.name, route.uniqueName)
    SettingsRoute.AddonList -> AppRoute.AddonList
    SettingsRoute.Theme -> AppRoute.Theme
    SettingsRoute.VirtualKeyboard -> AppRoute.Prefs(PrefCategory.Keyboard)
    SettingsRoute.CandidatesWindow -> AppRoute.Prefs(PrefCategory.Candidates)
    SettingsRoute.Clipboard -> AppRoute.Prefs(PrefCategory.Clipboard)
    SettingsRoute.Broadcast -> AppRoute.Prefs(PrefCategory.Broadcast)
    SettingsRoute.Symbol -> AppRoute.Prefs(PrefCategory.Symbols)
    SettingsRoute.Plugin -> AppRoute.PluginList
    SettingsRoute.Advanced -> AppRoute.Prefs(PrefCategory.Advanced)
    SettingsRoute.Developer -> AppRoute.Developer
    SettingsRoute.License -> AppRoute.Licenses
    SettingsRoute.About -> AppRoute.About
    SettingsRoute.TableInputMethods -> AppRoute.TableInputMethods
    SettingsRoute.QuickPhraseList -> AppRoute.QuickPhraseList
    SettingsRoute.PinyinCustomPhrase -> AppRoute.PinyinCustomPhrase
    // parameterized legacy routes that are not reachable through a fixed entry:
    // they are only produced by intent extras, so map to a fresh legacy entry hardcoding the
    // resolver-side handling on next intent-driven visit.
    is SettingsRoute.AddonConfig -> AppRoute.AddonConfig(route.name, route.uniqueName)
    is SettingsRoute.ListConfig -> AppRoute.Legacy(LegacyTarget.Index)
    is SettingsRoute.PinyinDict -> AppRoute.PinyinDictionary(route.uri)
    is SettingsRoute.Punctuation -> AppRoute.Punctuation(route.title, route.lang)
    is SettingsRoute.QuickPhraseEdit -> AppRoute.QuickPhraseEdit(route.param.quickPhrase.file.name)
}
