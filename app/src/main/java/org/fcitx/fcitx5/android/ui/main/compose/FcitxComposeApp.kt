/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.ui.main.MainActivity
import org.fcitx.fcitx5.android.ui.main.compose.dialog.DictImportDialog
import org.fcitx.fcitx5.android.ui.main.compose.dialog.NotificationPermissionFlow
import org.fcitx.fcitx5.android.ui.main.compose.screens.AboutScreen
import org.fcitx.fcitx5.android.ui.main.compose.screens.AddonListScreen
import org.fcitx.fcitx5.android.ui.main.compose.screens.BroadcastScreen
import org.fcitx.fcitx5.android.ui.main.compose.screens.DeveloperScreen
import org.fcitx.fcitx5.android.ui.main.compose.screens.HomeScreen
import org.fcitx.fcitx5.android.ui.main.compose.screens.InputMethodListScreen
import org.fcitx.fcitx5.android.ui.main.compose.screens.LicensesScreen
import org.fcitx.fcitx5.android.ui.main.compose.screens.RawConfigHostScreen
import org.fcitx.fcitx5.android.ui.main.compose.screens.SettingsSearchDialog
import org.fcitx.fcitx5.android.ui.main.compose.screens.ThemeScreen
import org.fcitx.fcitx5.android.ui.main.compose.screens.WebDavSyncScreen
import org.fcitx.fcitx5.android.ui.main.compose.screens.VoiceInputSettingsScreen
import org.fcitx.fcitx5.android.ui.main.compose.screens.ModelMarketHomeScreen
import org.fcitx.fcitx5.android.ui.main.compose.screens.AddonDirProfileScreen
import org.fcitx.fcitx5.android.ui.main.compose.screens.DataBackupScreen
import org.fcitx.fcitx5.android.ui.main.compose.screens.ModelMarketScreen
import org.fcitx.fcitx5.android.ui.main.compose.screens.MultiSelectScreen
import org.fcitx.fcitx5.android.ui.main.compose.screens.RimeImportScreen
import org.fcitx.fcitx5.android.ui.main.compose.screens.RimeSchemeUpdateScreen
import org.fcitx.fcitx5.android.ui.main.compose.screens.KeyboardGroupScreen
import org.fcitx.fcitx5.android.ui.main.compose.screens.KeyboardModesScreen
import org.fcitx.fcitx5.android.ui.main.compose.screens.SettingsGroupScreen
import org.fcitx.fcitx5.android.ui.main.compose.screens.SettingsUiAppearanceScreen
import org.fcitx.fcitx5.android.ui.main.compose.screens.SettingsUiMotionScreen
import org.fcitx.fcitx5.android.ui.main.compose.screens.TextEditingPrefsScreen
import org.fcitx.fcitx5.android.ui.main.compose.screens.UpdateHostsSettingsScreen
import org.fcitx.fcitx5.android.ui.main.compose.screens.UpdateMirrorEditScreen
import org.fcitx.fcitx5.android.ui.main.compose.screens.TextKeyboardLayoutProfileManagerScreen
import org.fcitx.fcitx5.android.ui.main.compose.screens.IconThemeListScreen
import org.fcitx.fcitx5.android.ui.main.compose.screens.InputStatsScreen
import org.fcitx.fcitx5.android.ui.main.compose.screens.RimeEngineManagerScreen
import org.fcitx.fcitx5.android.ui.main.compose.screens.FontsetEditorScreen
import org.fcitx.fcitx5.android.ui.main.compose.screens.SplitKeyboardCalibrationScreen
import org.fcitx.fcitx5.android.ui.main.compose.screens.UpdateMirrorListScreen
import org.fcitx.fcitx5.android.ui.main.compose.screens.WanxiangUpdateScreen
import org.fcitx.fcitx5.android.ui.main.compose.screens.HandwritingGestureDemoScreen
import org.fcitx.fcitx5.android.ui.main.compose.screens.HandwritingSettingsScreen
import org.fcitx.fcitx5.android.data.market.MarketCategories
import org.fcitx.fcitx5.android.ui.main.compose.screens.VoiceProviderConfigScreen
import org.fcitx.fcitx5.android.ui.main.compose.screens.VoicePreprocessDebugScreen
import org.fcitx.fcitx5.android.ui.main.compose.settings.ManagedPrefsScreen
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
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
        var importDictUri by remember { mutableStateOf<String?>(null) }

        fun interceptTargets(route: AppRoute): Boolean = route is AppRoute.Legacy

        fun navigateTo(appRoute: AppRoute) {
            if (backStack.lastOrNull() == appRoute) return
            // Navigating to the home index always collapses the stack back to the root.
            if (appRoute is AppRoute.Index) {
                backStack.clear()
                backStack.add(AppRoute.Index)
                return
            }

            // Push on top of the current stack so deeper levels (e.g. IM list -> IM config)
            // keep a proper back chain instead of collapsing to the home screen.
            backStack.add(appRoute)
        }

        LaunchedEffect(Unit) {
            shell.intents.collect { intent ->
                when (intent.action) {
                    // onboarding is triggered from ComposeMainShell.onResume instead of here,
                    // so a fresh cold start and returning to the foreground both re-check it
                    Intent.ACTION_MAIN -> Unit
                    Intent.ACTION_VIEW -> {
                        importDictUri = intent.data?.toString()
                    }
                    Intent.ACTION_RUN -> {
                        // custom: IME 内语音面板跳应用内语音页面（独立 extra，避免改动 SettingsRoute）
                        when (intent.getStringExtra(
                            org.fcitx.fcitx5.android.data.voice.VoiceRoutes.EXTRA
                        )) {
                            org.fcitx.fcitx5.android.data.voice.VoiceRoutes.SETTINGS -> {
                                navigateTo(AppRoute.VoiceInputSettings)
                                return@collect
                            }

                            org.fcitx.fcitx5.android.data.voice.VoiceRoutes.MODELS -> {
                                navigateTo(AppRoute.ModelMarket(MarketCategories.ASR))
                                return@collect
                            }

                            org.fcitx.fcitx5.android.data.voice.VoiceRoutes.PROVIDER_CONFIG -> {
                                intent.getStringExtra(
                                    org.fcitx.fcitx5.android.data.voice.VoiceRoutes.EXTRA_PROVIDER_ID
                                )?.let { navigateTo(AppRoute.VoiceProviderConfig(it)) }
                                return@collect
                            }
                        }
                        // 只存在于新导航里的页面按路由名传过来
                        intent.getStringExtra(MainActivity.EXTRA_COMPOSE_ROUTE)?.let { name ->
                            val arg = intent.getStringExtra(MainActivity.EXTRA_COMPOSE_ROUTE_ARG)
                            composeRouteByName(name, arg)?.let {
                                navigateTo(it)
                                return@collect
                            }
                        }
                        val route = intent.parcelable<SettingsRoute>(MainActivity.EXTRA_SETTINGS_ROUTE)
                            ?: return@collect
                        navigateTo(appRouteOf(route))
                    }
                }
            }
        }

            NotificationPermissionFlow()
            DictImportDialog(
                uri = importDictUri,
                onConfirm = { uri ->
                    importDictUri = null
                    // 词库导入已由对话框完成，随后进入 Rime 输入法配置继续设置
                    navigateTo(AppRoute.InputMethodConfig("Rime", "rime"))
                },
                onDismiss = { importDictUri = null },
            )
            NavDisplay(
                backStack = backStack,
                onBack = {
                    when {
                        backStack.size > 1 -> backStack.removeLastOrNull()
                        else -> activity.moveTaskToBack(false)
                    }
                },
            ) {
                entry<AppRoute.Index> {
                    var showSearch by remember { mutableStateOf(false) }
                    HomeScreen(
                        onNavigate = ::navigateTo,
                        onOpenUrl = { url ->
                            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        },
                        onSearch = { showSearch = true },
                    )
                    if (showSearch) {
                        SettingsSearchDialog(
                            onNavigate = ::navigateTo,
                            onDismiss = { showSearch = false },
                        )
                    }
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
                entry<AppRoute.Theme> {
                    ThemeScreen(onBack = { backStack.removeLastOrNull() })
                }
                entry<AppRoute.Legacy> {
                    // 旧路由的唯一目标就是设置首页，首页本身由 Compose 渲染
                    HomeScreen(
                        onNavigate = ::navigateTo,
                        onOpenUrl = { url ->
                            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        },
                        onSearch = {},
                    )
                }
                entry<AppRoute.Prefs> { route ->
                    if (route.category == PrefCategory.Broadcast) {
                        BroadcastScreen(onBack = { backStack.removeLastOrNull() })
                    } else if (route.category == PrefCategory.TextEditing) {
                        TextEditingPrefsScreen(onBack = { backStack.removeLastOrNull() })
                    } else {
                        ManagedPrefsScreen(
                            category = route.category.provider(),
                            onBack = { backStack.removeLastOrNull() },
                            highlightKey = route.highlightKey,
                            onWebDavSync = if (route.category == PrefCategory.Advanced) {
                                { navigateTo(AppRoute.WebDavSync) }
                            } else null,
                        )
                    }
                }
                entry<AppRoute.DataBackup> {
                    DataBackupScreen(
                        onBack = { backStack.removeLastOrNull() },
                        onNavigate = ::navigateTo,
                    )
                }
                entry<AppRoute.RimeImport> {
                    RimeImportScreen(onBack = { backStack.removeLastOrNull() })
                }
                entry<AppRoute.RimeSchemeUpdate> {
                    RimeSchemeUpdateScreen(onBack = { backStack.removeLastOrNull() })
                }
                entry<AppRoute.WanxiangUpdate> {
                    WanxiangUpdateScreen(onBack = { backStack.removeLastOrNull() })
                }
                entry<AppRoute.UpdateMirrorList> {
                    UpdateMirrorListScreen(
                        onBack = { backStack.removeLastOrNull() },
                        onEdit = { id -> navigateTo(AppRoute.UpdateMirrorEdit(id)) },
                    )
                }
                entry<AppRoute.UpdateHostsSettings> {
                    UpdateHostsSettingsScreen(onBack = { backStack.removeLastOrNull() })
                }
                entry<AppRoute.UpdateMirrorEdit> { route ->
                    UpdateMirrorEditScreen(
                        mirrorId = route.mirrorId,
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<AppRoute.TextKeyboardLayoutProfiles> {
                    TextKeyboardLayoutProfileManagerScreen(
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<AppRoute.IconThemeList> {
                    IconThemeListScreen(onBack = { backStack.removeLastOrNull() })
                }
                entry<AppRoute.InputStats> {
                    InputStatsScreen(onBack = { backStack.removeLastOrNull() })
                }
                entry<AppRoute.RimeEngineManager> {
                    RimeEngineManagerScreen(onBack = { backStack.removeLastOrNull() })
                }
                entry<AppRoute.FontsetEditor> {
                    FontsetEditorScreen(onBack = { backStack.removeLastOrNull() })
                }
                entry<AppRoute.SplitKeyboardCalibration> {
                    SplitKeyboardCalibrationScreen(onBack = { backStack.removeLastOrNull() })
                }
                entry<AppRoute.SettingsGroup> { route ->
                    SettingsGroupScreen(
                        groupId = route.id,
                        onBack = { backStack.removeLastOrNull() },
                        onNavigate = ::navigateTo,
                    )
                }
                entry<AppRoute.SettingsUiAppearance> {
                    SettingsUiAppearanceScreen(
                        onBack = { backStack.removeLastOrNull() },
                        onNavigate = ::navigateTo,
                    )
                }
                entry<AppRoute.SettingsUiMotion> {
                    SettingsUiMotionScreen(onBack = { backStack.removeLastOrNull() })
                }
                entry<AppRoute.KeyboardModes> {
                    KeyboardModesScreen(
                        onBack = { backStack.removeLastOrNull() },
                        onCalibrate = { navigateTo(AppRoute.SplitKeyboardCalibration) },
                    )
                }
                entry<AppRoute.KeyboardGroup> { route ->
                    KeyboardGroupScreen(
                        groupIndex = route.group,
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<AppRoute.WebDavSync> {
                    WebDavSyncScreen(onBack = { backStack.removeLastOrNull() })
                }
                // custom: 语音输入设置页
                entry<AppRoute.VoiceInputSettings> {
                    VoiceInputSettingsScreen(
                        onBack = { backStack.removeLastOrNull() },
                        onOpenModels = {
                            navigateTo(AppRoute.ModelMarket(MarketCategories.ASR))
                        },
                        onOpenPreprocessDebug = {
                            navigateTo(AppRoute.VoicePreprocessDebug)
                        },
                    )
                }
                // custom: 整段语音预处理测试页（仅 debug 构建注册；入口同理只在 debug 显示）
                if (BuildConfig.DEBUG) {
                    entry<AppRoute.VoicePreprocessDebug> {
                        VoicePreprocessDebugScreen(onBack = { backStack.removeLastOrNull() })
                    }
                }
                // custom: 模型市场父页（各分类入口）
                entry<AppRoute.ModelMarketHome> {
                    ModelMarketHomeScreen(
                        onBack = { backStack.removeLastOrNull() },
                        onOpenCategory = {
                            navigateTo(AppRoute.ModelMarket(MarketCategories.routeOf(it)))
                        },
                    )
                }
                // custom: 模型市场分类子页（语音 asr / 数字墨水 digitalink）
                entry<AppRoute.ModelMarket> { route ->
                    ModelMarketScreen(
                        category = MarketCategories.of(route.category),
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<AppRoute.VoiceProviderConfig> { route ->
                    VoiceProviderConfigScreen(
                        providerId = route.providerId,
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                // custom: 手写输入设置页（引擎下拉 + 识别语言入口（模型市场）+ 手势演示）
                entry<AppRoute.HandwritingSettings> {
                    HandwritingSettingsScreen(
                        onBack = { backStack.removeLastOrNull() },
                        onOpenModels = {
                            navigateTo(
                                AppRoute.ModelMarket(MarketCategories.DIGITAL_INK)
                            )
                        },
                        onOpenGestureDemo = { navigateTo(AppRoute.HandwritingGestureDemo) },
                    )
                }
                entry<AppRoute.HandwritingGestureDemo> {
                    HandwritingGestureDemoScreen(onBack = { backStack.removeLastOrNull() })
                }
                entry<AppRoute.RawConfigHost> { route ->
                    RawConfigHostScreen(
                        route = route,
                        onNavigate = ::navigateTo,
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<AppRoute.MultiSelect> { route ->
                    MultiSelectScreen(
                        title = route.title,
                        addon = route.addon,
                        path = route.path,
                        option = route.option,
                        min = route.min,
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<AppRoute.AddonDirProfile> { route ->
                    AddonDirProfileScreen(
                        title = route.title,
                        addon = route.addon,
                        path = route.path,
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
            }
        }
    }

/// 按路由名解析 Compose 页面；名字来自其它页面启动本界面时的 extra。
fun composeRouteByName(name: String, arg: String? = null): AppRoute? = when (name) {
    "UpdateMirrorList" -> AppRoute.UpdateMirrorList
    "UpdateMirrorEdit" -> AppRoute.UpdateMirrorEdit(arg)
    "UpdateHostsSettings" -> AppRoute.UpdateHostsSettings
    "IconThemeList" -> AppRoute.IconThemeList
    "FontsetEditor" -> AppRoute.FontsetEditor
    "SplitKeyboardCalibration" -> AppRoute.SplitKeyboardCalibration
    else -> null
}

fun appRouteOf(route: SettingsRoute): AppRoute = when (route) {
    SettingsRoute.Index -> AppRoute.Index
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
    SettingsRoute.TextEditing -> AppRoute.Prefs(PrefCategory.TextEditing)
    is SettingsRoute.SettingsGroup -> AppRoute.SettingsGroup(route.id)
    SettingsRoute.DataBackup -> AppRoute.DataBackup
    SettingsRoute.SettingsUiAppearance -> AppRoute.SettingsUiAppearance
    SettingsRoute.SettingsUiMotion -> AppRoute.SettingsUiMotion
    SettingsRoute.KeyboardModes -> AppRoute.KeyboardModes
    is SettingsRoute.KeyboardGroup -> AppRoute.KeyboardGroup(route.group)
    SettingsRoute.TextKeyboardLayoutProfiles -> AppRoute.TextKeyboardLayoutProfiles
    SettingsRoute.IconThemeList -> AppRoute.IconThemeList
    SettingsRoute.InputStats -> AppRoute.InputStats
    SettingsRoute.RimeEngineManager -> AppRoute.RimeEngineManager
    SettingsRoute.FontsetEditor -> AppRoute.FontsetEditor
    SettingsRoute.SplitKeyboardCalibration -> AppRoute.SplitKeyboardCalibration
    SettingsRoute.Advanced -> AppRoute.Prefs(PrefCategory.Advanced)
    SettingsRoute.Developer -> AppRoute.Developer
    SettingsRoute.License -> AppRoute.Licenses
    SettingsRoute.About -> AppRoute.About
    // parameterized legacy routes that are not reachable through a fixed entry:
    // they are only produced by intent extras, so map to a fresh legacy entry hardcoding the
    // resolver-side handling on next intent-driven visit.
    // 列表配置已由 RawConfig 行内的编辑对话框承载，旧路由回到首页
    is SettingsRoute.ListConfig -> AppRoute.Index
    else -> AppRoute.Index
}
