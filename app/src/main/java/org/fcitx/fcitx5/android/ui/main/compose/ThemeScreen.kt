/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose

import android.view.ViewOutlineProvider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeFilesManager
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.ui.main.settings.theme.CustomThemeActivity
import org.fcitx.fcitx5.android.ui.main.settings.theme.KeyboardPreviewUi
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemeThumbnailUi
import org.fcitx.fcitx5.android.utils.importErrorDialog
import org.fcitx.fcitx5.android.utils.queryFileName
import org.fcitx.fcitx5.android.utils.toast
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.util.UUID

/**
 * Compose renderer for the theme page (replaces ThemeFragment + ThemeListFragment + ThemeSettingsFragment).
 * The keyboard preview is kept as a View (AndroidView wrapping KeyboardPreviewUi); the theme list and
 * the config section are rendered with Compose.
 */
@Composable
fun ThemeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var previewHeightPx by remember { mutableIntStateOf(0) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val pagerState = rememberPagerState { 2 }
    var themes by remember { mutableStateOf(ThemeManager.getAllThemes()) }
    var activeTheme by remember { mutableStateOf(ThemeManager.activeTheme) }
    var followSystem by remember {
        mutableStateOf(ThemeManager.prefs.followSystemDayNightTheme.getValue())
    }
    var showNewDialog by remember { mutableStateOf(false) }
    var duplicateDialog by remember { mutableStateOf(false) }

    fun reload() {
        ThemeManager.refreshThemes()
        themes = ThemeManager.getAllThemes()
        activeTheme = ThemeManager.activeTheme
        followSystem = ThemeManager.prefs.followSystemDayNightTheme.getValue()
    }

    val themeListener = remember {
        ThemeManager.OnThemeChangeListener { active ->
            themes = ThemeManager.getAllThemes()
            activeTheme = active
        }
    }

    DisposableEffect(Unit) {
        ThemeManager.addOnChangedListener(themeListener)
        onDispose { ThemeManager.removeOnChangedListener(themeListener) }
    }

    // keep the pager and the tab row in sync when the user swipes
    LaunchedEffect(pagerState.currentPage) {
        selectedTab = pagerState.currentPage
    }

    val editLauncher = rememberLauncherForActivityResult(
        CustomThemeActivity.Contract()
    ) { result ->
        if (result == null) return@rememberLauncherForActivityResult
        when (result) {
            is CustomThemeActivity.BackgroundResult.Created -> {
                ThemeManager.saveTheme(result.theme)
                reload()
            }
            is CustomThemeActivity.BackgroundResult.Deleted -> {
                ThemeManager.deleteTheme(result.name)
                reload()
            }
            is CustomThemeActivity.BackgroundResult.Updated -> {
                ThemeManager.saveTheme(result.theme)
                reload()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val name = context.contentResolver.queryFileName(uri) ?: return@launch
            val ext = name.substringAfterLast('.')
            if (ext != "zip") {
                context.importErrorDialog(R.string.exception_theme_filename, ext)
                return@launch
            }
            try {
                val (newCreated, _, migrated) = withContext(Dispatchers.IO) {
                    ThemeFilesManager.importTheme(context.contentResolver.openInputStream(uri)!!)
                        .getOrThrow()
                }
                reload()
                if (migrated) context.toast(R.string.theme_migrated)
            } catch (e: Exception) {
                context.importErrorDialog(e)
            }
        }
    }

    var exportTarget by remember { mutableStateOf<Theme.Custom?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val exported = exportTarget ?: return@rememberLauncherForActivityResult
        exportTarget = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ThemeFilesManager.exportTheme(
                        exported, context.contentResolver.openOutputStream(uri)!!
                    ).getOrThrow()
                }
            } catch (e: Exception) {
                context.toast(e)
            }
        }
    }

    var pendingSelect by remember { mutableStateOf<Theme?>(null) }

    fun selectTheme(theme: Theme) {
        if (followSystem) {
            pendingSelect = theme
        } else {
            ThemeManager.setNormalModeTheme(theme)
        }
    }

    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            // keyboard preview kept as a View (same styling as the legacy fragment)
            val preview = remember {
                KeyboardPreviewUi(context, activeTheme).also { ui ->
                    ui.onSizeMeasured = { _, h -> previewHeightPx = h }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .height(with(density) { (previewHeightPx * 0.5f).toDp() })
                    .clip(RoundedCornerShape(12.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.TopCenter,
            ) {
                AndroidView(
                    factory = {
                        preview.root.apply {
                            scaleX = 0.5f
                            scaleY = 0.5f
                            outlineProvider = ViewOutlineProvider.BOUNDS
                            elevation = with(density) { 4.dp.toPx() }
                        }
                    },
                    update = { preview.setTheme(activeTheme) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            TabRow(
                tabs = listOf(
                    context.getString(R.string.theme),
                    context.getString(R.string.configure),
                ),
                selectedTabIndex = selectedTab,
                onTabSelected = { scope.launch { pagerState.animateScrollToPage(it) } },
                colors = TabRowDefaults.tabRowColors(
                    backgroundColor = MiuixTheme.colorScheme.surfaceContainer,
                    contentColor = MiuixTheme.colorScheme.onSurface,
                    selectedBackgroundColor = MiuixTheme.colorScheme.primary,
                    selectedContentColor = MiuixTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                when (page) {
                    0 -> {
                        val lightName =
                            if (followSystem) ThemeManager.prefs.lightModeTheme.getValue()?.name else null
                        val darkName =
                            if (followSystem) ThemeManager.prefs.darkModeTheme.getValue()?.name else null
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(144.dp),
                            contentPadding = PaddingValues(12.dp, 0.dp, 12.dp, 88.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            item {
                                NewThemeEntryView(onClick = { showNewDialog = true })
                            }
                            items(themes, key = { it.name }) { theme ->
                                val state = when {
                                    theme.name == activeTheme.name -> ThemeThumbnailUi.State.Selected
                                    lightName == theme.name -> ThemeThumbnailUi.State.LightMode
                                    darkName == theme.name -> ThemeThumbnailUi.State.DarkMode
                                    else -> ThemeThumbnailUi.State.Normal
                                }
                                ThemeThumbnailView(
                                    theme = theme,
                                    state = state,
                                    onClick = { selectTheme(theme) },
                                    onLongClick = {
                                        val exportable = when (theme) {
                                            is Theme.Custom -> theme
                                            is Theme.Monet -> theme.toCustom()
                                            else -> null
                                        }
                                        if (exportable != null) {
                                            exportTarget = exportable
                                            exportLauncher.launch(exportable.name + ".zip")
                                        }
                                    },
                                    onEdit = { if (theme is Theme.Custom) editLauncher.launch(theme) },
                                )
                            }
                        }
                    }
                    1 -> ManagedPrefsScreen(
                        category = ThemeManager.prefs,
                        onBack = onBack,
                        showTopBar = false,
                    )
                }
            }
        }

        if (selectedTab == 0) {
            FloatingActionButton(
                onClick = { showNewDialog = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) {
                Icon(MiuixIcons.Add, null)
            }
        }

        SmallTopAppBar(
            title = context.getString(R.string.theme),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(MiuixIcons.Back, null, Modifier.size(24.dp))
                }
            },
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
        )
    }

    if (showNewDialog) {
        ThemeNewDialog(
            onChooseImage = {
                showNewDialog = false
                editLauncher.launch(null)
            },
            onImport = {
                showNewDialog = false
                importLauncher.launch("application/zip")
            },
            onDuplicate = {
                showNewDialog = false
                duplicateDialog = true
            },
            onDismiss = { showNewDialog = false },
        )
    }

    if (duplicateDialog) {
        ThemeDuplicateDialog(
            onPick = { builtin ->
                duplicateDialog = false
                val newTheme = builtin.deriveCustomNoBackground(UUID.randomUUID().toString())
                ThemeManager.saveTheme(newTheme)
                reload()
            },
            onDismiss = { duplicateDialog = false },
        )
    }

    pendingSelect?.let { theme ->
        FollowSystemThemeConfirmDialog(
            message = context.getString(R.string.theme_message_follow_system_day_night_mode_enabled),
            onDisable = {
                ThemeManager.prefs.followSystemDayNightTheme.setValue(false)
                scope.launch { ThemeManager.setNormalModeTheme(theme) }
                pendingSelect = null
            },
            onDismiss = { pendingSelect = null },
        )
    }
}

@Composable
private fun ThemeNewDialog(
    onChooseImage: () -> Unit,
    onImport: () -> Unit,
    onDuplicate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var show by remember { mutableStateOf(true) }
    val options = listOf(
        context.getString(R.string.choose_image) to onChooseImage,
        context.getString(R.string.import_from_file) to onImport,
        context.getString(R.string.duplicate_builtin_theme) to onDuplicate,
    )
    WindowDialog(
        show = show,
        title = context.getString(R.string.new_theme),
        onDismissRequest = {
            show = false
            onDismiss()
        },
    ) {
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            options.forEach { (text, action) ->
                TextButton(
                    text = text,
                    onClick = {
                        show = false
                        action()
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ThemeDuplicateDialog(
    onPick: (Theme.Builtin) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var show by remember { mutableStateOf(true) }
    WindowDialog(
        show = show,
        title = context.getString(R.string.duplicate_builtin_theme),
        onDismissRequest = {
            show = false
            onDismiss()
        },
    ) {
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            ThemeManager.BuiltinThemes.forEach { builtin ->
                TextButton(
                    text = builtin.name,
                    onClick = {
                        show = false
                        onPick(builtin)
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun FollowSystemThemeConfirmDialog(
    message: String,
    onDisable: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var show by remember { mutableStateOf(true) }
    WindowDialog(
        show = show,
        title = context.getString(R.string.configure),
        summary = message,
        onDismissRequest = {
            show = false
            onDismiss()
        },
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextButton(
                text = context.getString(android.R.string.ok),
                onClick = {
                    show = false
                    onDismiss()
                },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = context.getString(R.string.disable_it),
                onClick = {
                    show = false
                    onDisable()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

@Composable
private fun NewThemeEntryView(onClick: () -> Unit) {
    val context = LocalContext.current
    AndroidView(
        factory = { ctx ->
            org.fcitx.fcitx5.android.ui.main.settings.theme.NewThemeEntryUi(ctx).root.apply {
                setOnClickListener { onClick() }
            }
        },
        modifier = Modifier.size(128.dp, 92.dp),
    )
}

@Composable
private fun ThemeThumbnailView(
    theme: Theme,
    state: ThemeThumbnailUi.State,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onEdit: () -> Unit,
) {
    val context = LocalContext.current
    val ui = remember { ThemeThumbnailUi(context) }
    AndroidView(
        factory = {
            ui.root.apply {
                setOnClickListener { onClick() }
                setOnLongClickListener { onLongClick(); true }
            }
            ui.editButton.setOnClickListener { onEdit() }
            ui.root
        },
        update = {
            ui.setTheme(theme)
            ui.setChecked(state)
        },
        modifier = Modifier.size(128.dp, 92.dp),
    )
}
