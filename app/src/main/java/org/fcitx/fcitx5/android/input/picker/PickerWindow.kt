/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.transition.Transition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.candidates.expanded.ExpandedCandidateKeyboard
import org.fcitx.fcitx5.android.input.candidates.expanded.computePageTargetIndex
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.keyboard.ReturnKey
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
 * Picker 窗口（符号 / Emoji / 颜文字），批次 D-3：由 View 改为 [ComposeWindow]；
 * 后续改造为「展开候选页」同款形态：
 *
 * - 排版 = [ComposePickerSplitUi]（左栏分类标签 + 中栏上下滚动网格 + 右栏竖排键盘），
 *   三模块（符号 / 表情 / 颜文字）共用同一份实现，风格与展开候选页统一；
 * - 数据 = [PickerPageModel.flatCategories]（`[⟳最近使用] + 各分类`，全部平铺进单个网格），
 *   分页 API 原样保留，供休眠的 View 版 [PickerPagesAdapter] 继续编译；
 * - 滚动 = 窗口持有 [gridState]（`EssentialWindow` 常驻，跨 attach 保留位置），
 *   右栏上/下翻键经 [keyActionListener] 的 `"U"/"D"` 路由走 [scrollPage]（复用
 *   [computePageTargetIndex]），左栏点标签由 UI 侧按锚点动画滚动；
 * - 弹层 = 网格滚动时关掉长按弹层/预览气泡；弹层小键盘显示期间禁用网格滚动
 *   （[gridScrollEnabled]，对应 View 的 `isUserInputEnabled`）。
 *
 * 已移除：`HorizontalPager` 分页、顶部 View 版标签栏 `PickerTabsUi`（含 `onCreateBarExtension()`
 * 覆盖）、分页指示条 `PickerPaginationUi`、底部内嵌键盘行与构造器 `switchKey` 参数。
 * 相关 View/Compose 文件（`PickerTabsUi` / `PickerPaginationUi` / `PickerLayout` /
 * `PickerKeyboardRows` / `ComposePickerPage`）按项目回退惯例**断线保留**，不删除。
 */
class PickerWindow(
    override val key: Key,
    private val data: List<Pair<PickerData.Category, Array<String>>>,
    private val density: PickerDensity,
    private val popupPreview: Boolean = true,
    private val followKeyBorder: Boolean = true,
    private val policy: PickerPolicy = DefaultPickerPolicy()
) : InputWindow.ExtendedInputWindow<PickerWindow>(), EssentialWindow, ComposeWindow {

    enum class Key : EssentialWindow.Key {
        Symbol,
        Emoji,
        Emoticon
    }

    private val windowManager: InputWindowManager by manager.must()
    private val commonKeyActionListener: CommonKeyActionListener by manager.must()
    private val popup: PopupComponent by manager.must()
    private val returnKeyDrawable: ReturnKeyDrawableComponent by manager.must()

    private val pageModel by lazy {
        PickerPageModel(data, density, key.name, policy)
    }

    private val keyBorder by ThemeManager.prefs.keyBorder

    /**
     * 内嵌键盘的回车图标，由 `onAttached()` 从 [returnKeyDrawable] 取初值。
     *
     * **不能在属性初始化器里读依赖**：Picker 窗口是在 `InputView.<init>` 里构造的，
     * 那时依赖作用域尚未装配，`by manager.must()` 会抛 `ComponentNotExistException`
     * （View 原实现同样只在 `onAttached()` 里访问它）。
     */
    private var returnDrawable by mutableStateOf(0)

    /** 弹层小键盘显示时禁用网格滚动（对应 View 的 `isUserInputEnabled`）。 */
    private var gridScrollEnabled by mutableStateOf(true)

    /**
     * 中栏网格滚动状态：窗口持有（`EssentialWindow` 实例常驻，跨 attach 保留滚动位置），
     * 左栏锚点滚动与右栏翻页键共用同一份。
     */
    private val gridState = LazyGridState()

    private val service by manager.inputMethodService()
    private val disableAnimation = AppPrefs.getInstance().advanced.disableAnimation

    /**
     * 由 `Content()` 通过 [rememberCoroutineScope] 捕获的 Compose 协程作用域。
     * 翻页/锚点动画（`animateScrollToItem`）必须在带 `MonotonicFrameClock` 的 Compose 作用域里执行，
     * 而 `lifecycleScope` 没有这个时钟，直接调会抛 `IllegalStateException` 崩溃。
     */
    private var composeScope: CoroutineScope? = null

    /** [onAttached] 重建分类后自增，驱动 Content 重算平铺分类并复位滚动。 */
    private var modelVersion by mutableIntStateOf(0)

    /**
     * 提交符号后自增，驱动 Content 重算平铺分类。
     *
     * 旧布局把「最近使用」放在第 0 页，靠翻页重访刷新；新布局该节常驻网格头部，必须即时刷新。
     */
    private var recentVersion by mutableIntStateOf(0)

    override fun enterAnimation(lastWindow: InputWindow): Transition? = null

    override fun exitAnimation(nextWindow: InputWindow): Transition? = null

    private val keyActionListener = KeyActionListener { action, source ->
        // 右栏上/下翻：位移一整屏行数（复用展开候选页的纯函数与路由约定 "U"/"D"）
        val pageAct = (action as? KeyAction.LayoutSwitchAction)?.act
        if (pageAct == ExpandedCandidateKeyboard.PageUp ||
            pageAct == ExpandedCandidateKeyboard.PageDown
        ) {
            scrollPage(forward = pageAct == ExpandedCandidateKeyboard.PageDown)
            return@KeyActionListener
        }
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
                if (action is KeyAction.CommitAction &&
                    pageModel.insertRecent(action.text)
                ) {
                    recentVersion++
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
                    // prevent grid from consuming swipe gesture when popup keyboard shown
                    gridScrollEnabled = false

                is PopupAction.DismissAction ->
                    gridScrollEnabled = true

                else -> {}
            }
            popup.listener.onPopupAction(action)
        }
    }

    /** 兜底路径：wm 对 [ComposeWindow] 会走统一 ComposeView 宿主，正常不会调用到这里。 */
    override fun onCreateView(): View = createComposeWindowView(context) { Content() }

    @Composable
    override fun Content() {
        // 捕获带 MonotonicFrameClock 的 Compose 作用域，供翻页/锚点动画使用（见 scrollPage）
        composeScope = rememberCoroutineScope()
        // 平铺分类 + 网格布局（含分类之间的换页空行、各分类锚点）：
        // policy 重建（modelVersion）或提交符号（recentVersion）时重算
        val sections = remember(modelVersion, recentVersion) { pageModel.flatCategories() }
        val gridLayout = remember(sections) {
            buildPickerGridLayout(sections, PickerCategoryGapRows)
        }

        // 与 View 一致：初始位置是首个真实符号分类（跳过「⟳最近使用」）；
        // modelVersion 重建（emoji 字形过滤等偏好变化）后同样回到该位置
        LaunchedEffect(modelVersion) {
            gridState.scrollToItem(gridLayout.sectionStarts.getOrNull(1) ?: 0)
        }

        // 可见范围变化（等价原来的「翻页」）时关掉长按弹层 / 预览气泡。
        // 只在 **index 变化** 时清，而不是一进入滚动就清：弹层小键盘显示期间网格已禁滚
        // （`gridScrollEnabled=false`），index 不可能变化，因此不会误杀刚弹出的浮窗。
        LaunchedEffect(gridState) {
            snapshotFlow { gridState.firstVisibleItemIndex }
                .drop(1)
                .collect { popup.dismissAll() }
        }

        ComposePickerSplitUi(
            gridLayout = gridLayout,
            density = density,
            bordered = followKeyBorder && keyBorder,
            gridState = gridState,
            gridScrollEnabled = gridScrollEnabled,
            returnKeyDef = remember(returnDrawable) { withReturnDrawable(ReturnKey()) },
            keyActionListener = keyActionListener,
            popupActionListener = popupActionListener,
            policy = policy,
            onCategorySelected = { index ->
                // 锚点指向该分类首项（含换页空行后的位置），点标签即可让分类贴顶
                val anchor = gridLayout.sectionStarts.getOrNull(index) ?: 0
                val scope = composeScope
                if (scope != null) {
                    scope.launch { gridState.animateScrollToItem(anchor) }
                } else {
                    service.lifecycleScope.launch { gridState.scrollToItem(anchor) }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }

    override fun onAttached() {
        if (pageModel.refreshIfNeeded()) modelVersion++
        returnDrawable = returnKeyDrawable.resourceId
    }

    override fun onDetached() {
        popup.dismissAll()
    }

    override val showTitle = false

    // -----------------------------------------------------------------------
    // 翻页（右栏上/下翻键）：一整屏行数的位移，目标行首项贴到视口顶部
    // -----------------------------------------------------------------------

    /**
     * 翻页：把目标行的首项滚到视口顶部（SNAP_TO_START），位移为**一整屏行数**。
     *
     * 目标下标由纯函数 [computePageTargetIndex] 算出（含「上翻是一整页而不是一行」的语义），
     * 与展开候选页 `ComposeExpandedCandidateWindow.scrollPage` 同一口径；差别只在于本窗口的网格
     * 是「单网格连续滚动」的全部分类。
     *
     * 动画滚动必须在 Compose 协程作用域里跑：`animateScrollToItem` 依赖 `MonotonicFrameClock`，
     * 而 `lifecycleScope` 不带该时钟会直接抛 `IllegalStateException` 崩溃；
     * `disableAnimation` 偏好生效时退回 `scrollToItem`。
     */
    private fun scrollPage(forward: Boolean) {
        val info = gridState.layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isEmpty() || info.totalItemsCount <= 0) return
        val viewportStart = info.viewportStartOffset
        val viewportEnd = info.viewportEndOffset
        // 完全可见：顶部 >= 视口顶 且 底部 <= 视口底；没有完全可见项时退回首/末可见项。
        val completelyVisible = visible.filter {
            it.offset.y >= viewportStart && it.offset.y + it.size.height <= viewportEnd
        }
        val target = computePageTargetIndex(
            firstVisibleIndex = (completelyVisible.firstOrNull() ?: visible.first()).index,
            lastVisibleIndex = (completelyVisible.lastOrNull() ?: visible.last()).index,
            columns = info.maxSpan,
            totalItemsCount = info.totalItemsCount,
            forward = forward,
        )
        val scope = composeScope
        val block: suspend CoroutineScope.() -> Unit = {
            if (disableAnimation.getValue()) {
                gridState.scrollToItem(target)
            } else {
                gridState.animateScrollToItem(target)
            }
        }
        if (scope != null) {
            scope.launch(block = block)
        } else {
            service.lifecycleScope.launch { gridState.scrollToItem(target) }
        }
    }

    // -----------------------------------------------------------------------
    // 回车键图标
    // -----------------------------------------------------------------------

    /**
     * 把 [ReturnKey] 的图标换成当前编辑框类型对应的 drawable（与 `KeyboardWindow` /
     * `ComposeExpandedCandidateKeyboard` 同口径）；`returnDrawable` 尚未就绪（0）时原样返回，
     * 避免 `painterResource(0)` 崩溃。
     */
    private fun withReturnDrawable(def: KeyDef): KeyDef {
        val appearance = def.appearance
        if (returnDrawable == 0 ||
            appearance.viewId != R.id.button_return ||
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
}
