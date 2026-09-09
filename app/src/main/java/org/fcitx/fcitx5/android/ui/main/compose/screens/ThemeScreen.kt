/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.screens

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
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
import org.fcitx.fcitx5.android.ui.main.compose.settings.ManagedPrefsScreen
import org.fcitx.fcitx5.android.ui.main.settings.theme.CustomThemeActivity
import org.fcitx.fcitx5.android.ui.main.settings.theme.KeyboardPreviewUi
import org.fcitx.fcitx5.android.ui.main.settings.theme.NewThemeEntryUi
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
import androidx.compose.ui.res.stringResource

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
            // keep local follow-system flag in sync with the pref (changed in the config tab
            // or via the disable button) so the confirm dialog is not shown again on the
            // same visit
            followSystem = ThemeManager.prefs.followSystemDayNightTheme.getValue()
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

    PageScaffold(
        title = stringResource(R.string.theme),
        onBack = onBack,
        floatingActionButton = {
            if (selectedTab == 0) {
                FloatingActionButton(
                    onClick = { showNewDialog = true },
                    modifier = Modifier.padding(16.dp),
                ) {
                    Icon(MiuixIcons.Add, stringResource(R.string.add))
                }
            }
        },
    ) {
        item {
            Column(Modifier.fillParentMaxSize()) {
            // keyboard preview kept as a View. Rebuild on orientation change so the measured
            // height follows the active keyboard-height percent, and use the full measured size
            // (no extra 0.5 scaling, which shrank the preview to ~20% of the screen height).
            val orientation = LocalConfiguration.current.orientation
            val preview = remember(orientation) {
                KeyboardPreviewUi(context, activeTheme).also { ui ->
                    ui.onSizeMeasured = { _, h -> previewHeightPx = h }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    // keep the preview full-width so the keyboard's own side padding isn't
                    // clipped on the right by a horizontal inset
                    .height(with(density) { previewHeightPx.toDp() })
                    .clip(RoundedCornerShape(12.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.TopCenter,
            ) {
                // key on the orientation so AndroidView re-runs its factory when the device
                // rotates: the factory is only executed once per composition slot, otherwise the
                // old preview (with the previous keyboard-height percent) stays mounted
                key(orientation) {
                    AndroidView(
                        factory = {
                            preview.root.apply {
                                outlineProvider = ViewOutlineProvider.BOUNDS
                                elevation = with(density) { 4.dp.toPx() }
                            }
                        },
                        update = { preview.setTheme(activeTheme) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            TabRow(
                tabs = listOf(
                    stringResource(R.string.theme),
                    stringResource(R.string.configure),
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
                modifier = Modifier.fillMaxSize().weight(1f),
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
        }
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
            message = stringResource(R.string.theme_message_follow_system_day_night_mode_enabled),
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
        stringResource(R.string.choose_image) to onChooseImage,
        stringResource(R.string.import_from_file) to onImport,
        stringResource(R.string.duplicate_builtin_theme) to onDuplicate,
    )
    WindowDialog(
        show = show,
        title = stringResource(R.string.new_theme),
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
        title = stringResource(R.string.duplicate_builtin_theme),
        onDismissRequest = {
            show = false
            onDismiss()
        },
    ) {
        // scrollable list of builtin themes
        LazyColumn(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .heightIn(max = 320.dp),
        ) {
            ThemeManager.BuiltinThemes.forEach { builtin ->
                item(key = builtin.name) {
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
        title = stringResource(R.string.configure),
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
                text = stringResource(android.R.string.ok),
                onClick = {
                    show = false
                    onDismiss()
                },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = stringResource(R.string.disable_it),
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
    val ui = remember { NewThemeEntryUi(context) }
    AndroidView(
        factory = { ui.root },
        update = { root -> root.setOnClickListener { onClick() } },
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
    val ui = remember(context) { ThemeThumbnailUi(context) }
    AndroidView(
        factory = { ui.root },
        update = {
            ui.setTheme(theme)
            ui.setChecked(state)
            ui.root.setOnClickListener { onClick() }
            ui.root.setOnLongClickListener { onLongClick(); true }
            ui.editButton.setOnClickListener { onEdit() }
        },
        modifier = Modifier.size(128.dp, 92.dp),
    )
}
