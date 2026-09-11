/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import android.view.View
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.transition.Transition
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.ComposeKeyRow
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.keyboard.preferenceState
import org.fcitx.fcitx5.android.input.keyboard.spaceAndBackspaceGestureListener
import org.fcitx.fcitx5.android.input.keyboard.spaceAndBackspaceSwipeSpec
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.wm.ComposeWindow
import org.fcitx.fcitx5.android.input.wm.EssentialWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.input.wm.createComposeWindowView
import org.mechdancer.dependency.manager.must

/**
 * Picker 窗口（符号 / Emoji / 颜文字），批次 D-3：由 View 改为 [ComposeWindow]。
 *
 * - 页内容 = [ComposePickerPage]（复用 [org.fcitx.fcitx5.android.input.keyboard.ComposeKey]）
 * - 分页数据 = [PickerPageModel]（与 View 版 adapter 共用同一份逻辑）
 * - `ViewPager2` → `HorizontalPager`；`isUserInputEnabled` → `userScrollEnabled`（弹层小键盘显示时关掉）
 * - 底部内嵌键盘复用 `PickerKeyboardRows` 的行数据
 * - 标签页仍是 View 版 [PickerTabsUi]，经 `onCreateBarExtension()` 交给（已支持 View 扩展的）Compose 工具栏
 * - 分页指示条仍是 View 版 [PickerPaginationUi]，以 `AndroidView` island 挂上
 */
class PickerWindow(
    override val key: Key,
    private val data: List<Pair<PickerData.Category, Array<String>>>,
    private val density: PickerPageUi.Density,
    private val switchKey: KeyDef,
    private val popupPreview: Boolean = true,
    private val followKeyBorder: Boolean = true,
    private val policy: PickerPolicy = DefaultPickerPolicy()
) : InputWindow.ExtendedInputWindow<PickerWindow>(), EssentialWindow, ComposeWindow {

    enum class Key : EssentialWindow.Key {
        Symbol,
        Emoji,
        Emoticon
    }

    private val theme by manager.theme()
    private val windowManager: InputWindowManager by manager.must()
    private val commonKeyActionListener: CommonKeyActionListener by manager.must()
    private val popup: PopupComponent by manager.must()
    private val returnKeyDrawable: ReturnKeyDrawableComponent by manager.must()

    private val pageModel by lazy {
        PickerPageModel(data, density, key.name, policy)
    }

    private val keyBorder by ThemeManager.prefs.keyBorder

    private val tabsUi by lazy { PickerTabsUi(context, theme) }

    /** 内嵌键盘的回车图标（`onReturnDrawableUpdate` 更新）。 */
    private var returnDrawable by mutableStateOf(returnKeyDrawable.resourceId)

    /** 弹层小键盘显示时禁用 pager 滑动（对应 View 的 `isUserInputEnabled`）。 */
    private var pagerScrollEnabled by mutableStateOf(true)

    /** `refreshIfNeeded()` 重建分类后自增，驱动 Content 重组（页数可能变化）。 */
    private var modelVersion by mutableIntStateOf(0)

    override fun enterAnimation(lastWindow: InputWindow): Transition? = null

    override fun exitAnimation(nextWindow: InputWindow): Transition? = null

    private val keyActionListener = KeyActionListener { action, source ->
        when (action) {
            is KeyAction.LayoutSwitchAction -> {
                // Switch to NumberKeyboard before attaching KeyboardWindow
                (windowManager.getEssentialWindow(KeyboardWindow) as KeyboardWindow)
                    .switchLayout(action.act)
                // The real switchLayout in KeyboardWindow is postponed,
                // so we have to postpone attachWindow as well
                ContextCompat.getMainExecutor(context).execute {
                    windowManager.attachWindow(KeyboardWindow)
                }
            }

            is KeyAction.FcitxKeyAction -> {
                // we want the behavior of CommitAction (commit the character as-is),
                // but don't want to include it in recently used list
                commonKeyActionListener.listener.onKeyAction(
                    KeyAction.CommitAction(action.act), source
                )
            }

            else -> {
                if (action is KeyAction.CommitAction) {
                    pageModel.insertRecent(action.text)
                }
                commonKeyActionListener.listener.onKeyAction(action, source)
            }
        }
    }

    private val popupActionListener: PopupActionListener by lazy {
        PopupActionListener { action ->
            when (action) {
                is PopupAction.PreviewAction -> {
                    if (!popupPreview) return@PopupActionListener
                }

                is PopupAction.ShowKeyboardAction ->
                    // prevent pager from consuming swipe gesture when popup keyboard shown
                    pagerScrollEnabled = false

                is PopupAction.DismissAction ->
                    pagerScrollEnabled = true

                else -> {}
            }
            popup.listener.onPopupAction(action)
        }
    }

    /** 兜底路径：wm 对 [ComposeWindow] 会走统一 ComposeView 宿主，正常不会调用到这里。 */
    override fun onCreateView(): View = createComposeWindowView(context) { Content() }

    @Composable
    override fun Content() {
        val tabs = remember { tabsUi }
        val scope = rememberCoroutineScope()
        // modelVersion 参与读取，重建分类后 pageCount 变化能触发重组
        val pageCount = remember(modelVersion) { pageModel.pageCount }
        val pagerState = rememberPagerState(
            initialPage = remember(modelVersion) {
                // 与 View 一致：默认显示第一个符号分类页，而不是「最近使用」
                pageModel.rangeOfCategoryIndex(1).first.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            }
        ) { pageCount }

        // 标签页交互（View 侧是 setOnTabClickListener + setCurrentItem(_, false)）
        LaunchedEffect(tabs, pageCount) {
            tabs.setTabs(pageModel.categoryList())
            tabs.setOnTabClickListener { index ->
                scope.launch { pagerState.scrollToPage(pageModel.rangeOfCategoryIndex(index).first) }
            }
            // update initial tab and page manually（View 的注释）
            tabs.activateTab(1)
        }

        // 翻页：同步标签页 + 分页指示条 + 关弹层（对应 onPageSelected / onPageScrolled）
        LaunchedEffect(pagerState, pageCount) {
            snapshotFlow { pagerState.currentPage to pagerState.currentPageOffsetFraction }
                .collect { (page, offset) ->
                    val range = pageModel.categoryRangeOfPage(page)
                    if (offset == 0f) {
                        tabs.activateTab(pageModel.categoryIndexOfPage(page))
                        popup.dismissAll()
                    }
                    paginationUpdate(range.last - range.first + 1, page - range.first, offset)
                }
        }

        val bordered = followKeyBorder && keyBorder
        val prefs = remember { AppPrefs.getInstance().keyboard }
        val hapticOnRepeat = prefs.hapticOnRepeat.preferenceState()
        val spaceSwipeMoveCursor = prefs.spaceSwipeMoveCursor.preferenceState()
        val view = LocalView.current
        val embeddedRow = remember(switchKey, returnDrawable) {
            pickerKeyboardRow(switchKey).map(::withReturnDrawable)
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(3f),
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = pagerScrollEnabled,
                ) { page ->
                    val items = remember(page, modelVersion, pageCount) {
                        pageModel.pageItems(page)
                    }
                    ComposePickerPage(
                        items = items,
                        density = density,
                        bordered = bordered,
                        keyActionListener = keyActionListener,
                        // 最近使用页不挂弹层（View: popupActionListener = null）
                        popupActionListener = if (pageModel.hasPopups(page)) {
                            popupActionListener
                        } else {
                            null
                        },
                        policy = if (page == 0) null else policy,
                        keyIdBase = page * KeyIdPageStride,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                PickerPaginationBar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        // View: below(pager, dp(-1)) → 压在 pager 底边上 1dp
                        .offset(y = (-1).dp),
                    onCreated = { paginationUpdate = it },
                )
            }
            // 底部内嵌键盘（View: 25% 高）
            ComposeKeyRow(
                row = embeddedRow,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                keyIdBase = KeyIdEmbeddedBase,
                key = { keyId, def, insets, keyModifier ->
                    org.fcitx.fcitx5.android.input.keyboard.ComposeKey(
                        def = def,
                        keyId = keyId,
                        modifier = keyModifier,
                        insets = insets,
                        keyActionListener = keyActionListener,
                        popupActionListener = popupActionListener,
                        swipeSpec = def.spaceAndBackspaceSwipeSpec(spaceSwipeMoveCursor),
                        onSwipeGesture = remember(def, hapticOnRepeat) {
                            def.spaceAndBackspaceGestureListener(
                                view = view,
                                onAction = { action ->
                                    keyActionListener.onKeyAction(
                                        action, KeyActionListener.Source.Keyboard
                                    )
                                },
                                hapticOnRepeat = hapticOnRepeat,
                            )
                        },
                    )
                },
            )
        }
    }

    override fun onCreateBarExtension(): View = tabsUi.root

    override fun onAttached() {
        if (pageModel.refreshIfNeeded()) modelVersion++
        returnDrawable = returnKeyDrawable.resourceId
    }

    override fun onDetached() {
        popup.dismissAll()
    }

    override val showTitle = false

    // -----------------------------------------------------------------------
    // 分页指示条（View 版 PickerPaginationUi 的桥）
    // -----------------------------------------------------------------------

    /** `(pageCount, index, progress)` —— 由 island 创建后注入。 */
    private var paginationUpdate: (Int, Int, Float) -> Unit = { _, _, _ -> }

    @Composable
    private fun PickerPaginationBar(
        modifier: Modifier = Modifier,
        onCreated: ((Int, Int, Float) -> Unit) -> Unit,
    ) {
        AndroidView(
            modifier = modifier.height(PaginationBarHeight),
            factory = { ctx ->
                PickerPaginationUi(ctx, theme).also { ui ->
                    onCreated { count, index, progress ->
                        ui.updatePageCount(count)
                        ui.updateScrollProgress(index, progress)
                    }
                }.root
            },
        )
    }

    private fun withReturnDrawable(def: KeyDef): KeyDef {
        val appearance = def.appearance
        if (appearance.viewId != R.id.button_return ||
            appearance !is KeyDef.Appearance.Image ||
            appearance.src == returnDrawable
        ) {
            return def
        }
        return KeyDef(
            KeyDef.Appearance.Image(
                src = returnDrawable,
                percentWidth = appearance.percentWidth,
                variant = appearance.variant,
                border = appearance.border,
                margin = appearance.margin,
                viewId = appearance.viewId,
                soundEffect = appearance.soundEffect,
            ),
            def.behaviors,
            def.popup,
        )
    }

    private companion object {
        /** 每页键 id 段（`PopupComponent` 按 Int id 索引，多页需错开）。 */
        const val KeyIdPageStride = 200
        const val KeyIdEmbeddedBase = 100000
    }
}

/** 分页指示条高度（View: `lParams(matchConstraints, dp(2))`）。 */
private val PaginationBarHeight = 2.dp
