/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.fcitx.fcitx5.android.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton

/**
 * 工具栏高度。
 *
 * 由宿主（[ComposeKawaiiBarComponent.ToolbarContent]）注入按偏好版本缓存的值，
 * 使工具栏子树内十余处读取共享同一份结果，而不是各自去读 SharedPreferences。
 * 默认值仅在脱离工具栏宿主单独使用 [ComposeToolbar] 时兜底。
 */
val LocalToolbarHeight = staticCompositionLocalOf { ComposeKawaiiBarComponent.HEIGHT.dp }

/**
 * Compose 工具栏
 *
 * 三态切换：Idle / Candidate / Title
 *
 * **顶栏的两段式结构**（custom）：左侧引导元素（键盘页=收起/展开菜单，更多页=返回）、
 * 中间**可停靠按钮行**（横向滚动，内容与顺序由 [ToolbarLayout] 布局决定）、
 * 右侧两个固定元素（更多按钮 + 尾随按钮：键盘页=收起键盘，更多页=编辑/完成）。
 * 右侧固定元素画在行内**之后**，因此「更多」永远贴着可停靠区的最右端。
 * 更多页打开时收起键盘不显示（顶栏改由返回+编辑承担离开/编辑语义）。
 */
@Composable
fun ComposeToolbar(
    barState: KawaiiBarStateMachine.State,
    idleSubState: IdleSubState,
    titleData: TitleData?,
    callbacks: ToolbarCallbacks,
    visuals: ToolbarVisuals,
    splitKeyboardEnabled: Boolean = false,
    menuRotation: Float = 0f,
    modifier: Modifier = Modifier,
    // 工具栏高度：宿主注入缓存值（见 [LocalToolbarHeight]）
    toolbarHeight: Dp = LocalToolbarHeight.current,
    // 可停靠行的按钮（顺序即显示顺序），由宿主从布局偏好解出
    dockedButtons: List<ToolbarButton> = emptyList(),
    // 候选栏可见性：由调用方按「候选内容就绪」驱动（默认按 barState，调用方覆盖以消除闪烁）
    candidateVisible: Boolean = barState == KawaiiBarStateMachine.State.Candidate,
    // 候选栏内容由外部传入
    candidateContent: @Composable () -> Unit = {},
    // NumberRow 需要 AndroidView 包装
    numberRowContent: @Composable () -> Unit = {},
    // InlineSuggestions 需要 AndroidView 包装
    inlineSuggestionContent: @Composable () -> Unit = {},
    // Clipboard suggestion 内容
    clipboardContent: @Composable () -> Unit = {},
    // 标题扩展内容
    titleExtensionContent: @Composable (() -> Unit)? = null,
) {
    CompositionLocalProvider(LocalToolbarHeight provides toolbarHeight) {
        // 「更多」只在可停靠按钮行真的可见时出现：收起态（Empty）与候选/剪贴板/数字行等
        // 子态里中间区不是按钮行，放一个更多按钮既无处安放也会与这些子态抢视觉重心。
        // 更多页本身（Title 态 + morePageVisible）恒显示可停靠行，故更多按钮恒在。
        val showMore = callbacks.morePageVisible ||
            (barState == KawaiiBarStateMachine.State.Idle && idleSubState == IdleSubState.Toolbar)
        // 右侧固定槽位数：更多页为「更多 + 编辑/完成」，键盘页为「更多? + 收起键盘」
        val trailingSlots = if (callbacks.morePageVisible) 2 else if (showMore) 2 else 1
        Box(
            modifier = modifier
                .fillMaxWidth()
                // 四角裁圆：
                //  · 上两角与父级 keyboardView 的 outline 圆角重合（ViewOutlineExt.applyTopRoundedCornerClip），
                //    这里一并写上是为了不依赖父级裁剪，将来父级不再裁也不塌；
                //  · **下两角是工具栏与键盘区的分界**，必须自己裁 —— IME 的下缘在屏幕边缘，
                //    由屏幕自身的圆角代劳，所以 IME 不裁下缘，但工具栏下缘不在屏幕边缘。
                .clip(RoundedCornerShape(16.dp))
                .background(visuals.barColor)
        ) {
            // 候选栏始终留在组合树中（底层），避免 AndroidView 反复 attach/detach 导致渲染不同步
            CandidateContent(
                candidateContent = candidateContent,
                visible = candidateVisible,
            )
            // Idle 态用滑入/滑出 + 淡入/淡出（与 IdleContent 内部子态展开收起动画一致），
            // 候选栏消失时工具栏从左滑入，候选栏出现时工具栏向左滑出。
            AnimatedVisibility(
                visible = barState == KawaiiBarStateMachine.State.Idle,
                enter = slideInHorizontally(animationSpec = androidx.compose.animation.core.tween(200)) { -it } +
                    fadeIn(animationSpec = androidx.compose.animation.core.tween(200)),
                exit = slideOutHorizontally(animationSpec = androidx.compose.animation.core.tween(150)) { -it } +
                    fadeOut(animationSpec = androidx.compose.animation.core.tween(150)),
            ) {
                IdleContent(
                    subState = idleSubState,
                    callbacks = callbacks,
                    visuals = visuals,
                    splitKeyboardEnabled = splitKeyboardEnabled,
                    menuRotation = menuRotation,
                    dockedButtons = dockedButtons,
                    trailingSlots = trailingSlots,
                    numberRowContent = numberRowContent,
                    inlineSuggestionContent = inlineSuggestionContent,
                    clipboardContent = clipboardContent,
                )
            }
            AnimatedVisibility(
                visible = barState == KawaiiBarStateMachine.State.Title,
                enter = fadeIn(animationSpec = androidx.compose.animation.core.tween(200)),
                exit = fadeOut(animationSpec = androidx.compose.animation.core.tween(150)),
            ) {
                if (callbacks.morePageVisible) {
                    // 更多页兼作布局编辑页：顶栏不再是「返回 + 标题」，而是与键盘页同形的
                    // 「返回 + 可停靠行」，这样工具栏按钮在该页照常可见、照常可拖动。
                    MorePageBar(
                        callbacks = callbacks,
                        visuals = visuals,
                        splitKeyboardEnabled = splitKeyboardEnabled,
                        dockedButtons = dockedButtons,
                        trailingSlots = trailingSlots,
                    )
                } else {
                    TitleContent(
                        titleData = titleData,
                        callbacks = callbacks,
                        visuals = visuals,
                        extensionContent = titleExtensionContent,
                    )
                }
            }
            // 右侧固定元素：键盘页（Idle）与更多页（Title + morePageVisible）都要显示。
            // 不跟随可停靠行滚动 —— 「更多」因此恒在最右；也不跟随 Idle 的滑入/滑出，仅淡入/淡出。
            AnimatedVisibility(
                visible = barState == KawaiiBarStateMachine.State.Idle ||
                    (barState == KawaiiBarStateMachine.State.Title && callbacks.morePageVisible),
                enter = fadeIn(animationSpec = androidx.compose.animation.core.tween(200)),
                exit = fadeOut(animationSpec = androidx.compose.animation.core.tween(150)),
            ) {
                FixedTrailingRow(
                    callbacks = callbacks,
                    visuals = visuals,
                    showMore = showMore,
                )
            }
        }
    }
}

/** 右侧固定元素占用的最小宽度：按实际槽位数预留，中间内容据此避让。 */
@Composable
private fun trailingInset(slots: Int): Dp = LocalToolbarHeight.current * slots

@Composable
private fun IdleContent(
    subState: IdleSubState,
    callbacks: ToolbarCallbacks,
    visuals: ToolbarVisuals,
    splitKeyboardEnabled: Boolean,
    menuRotation: Float,
    dockedButtons: List<ToolbarButton>,
    trailingSlots: Int,
    numberRowContent: @Composable () -> Unit,
    inlineSuggestionContent: @Composable () -> Unit,
    clipboardContent: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(LocalToolbarHeight.current)
            // 右侧固定元素由 FixedTrailingRow 绘制，中间内容在此避让
            .padding(end = trailingInset(trailingSlots)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧：菜单按钮（键盘页展开/收起工具栏）
        MenuButton(
            onClick = callbacks.onMenuClick,
            iconColor = visuals.iconColor,
            rotation = menuRotation,
        )

        // 中间内容区
        Box(
            modifier = Modifier
                .weight(1f)
                .height(LocalToolbarHeight.current),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = subState,
                transitionSpec = {
                    if (targetState == IdleSubState.Toolbar && initialState != IdleSubState.Toolbar) {
                        // 展开工具栏：从左滑入
                        slideInHorizontally { -it } + fadeIn() togetherWith
                            slideOutHorizontally { it / 3 } + fadeOut()
                    } else if (targetState != IdleSubState.Toolbar && initialState == IdleSubState.Toolbar) {
                        // 收起工具栏：向左滑出
                        slideInHorizontally { it / 3 } + fadeIn() togetherWith
                            slideOutHorizontally { -it } + fadeOut()
                    } else {
                        fadeIn() togetherWith fadeOut()
                    }
                },
                label = "toolbarContent",
            ) { state ->
                when (state) {
                    IdleSubState.Empty -> { /* 空白 */ }
                    IdleSubState.Toolbar -> {
                        DockedButtonsRow(
                            callbacks = callbacks,
                            visuals = visuals,
                            splitKeyboardEnabled = splitKeyboardEnabled,
                            dockedButtons = dockedButtons,
                        )
                    }
                    IdleSubState.Clipboard -> {
                        clipboardContent()
                    }
                    IdleSubState.NumberRow -> {
                        numberRowContent()
                    }
                    IdleSubState.InlineSuggestion -> {
                        inlineSuggestionContent()
                    }
                }
            }
        }
    }
}

/**
 * 更多页的顶栏：左侧返回（编辑模式下隐藏），中间与键盘页共用同一条可停靠行。
 *
 * 编辑模式下左侧留空 —— 编辑期间不允许离开该页，必须先点「完成」或让页面被顶掉。
 */
@Composable
private fun MorePageBar(
    callbacks: ToolbarCallbacks,
    visuals: ToolbarVisuals,
    splitKeyboardEnabled: Boolean,
    dockedButtons: List<ToolbarButton>,
    trailingSlots: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(LocalToolbarHeight.current)
            .padding(end = trailingInset(trailingSlots)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!callbacks.editing) {
            ToolbarIconButton(
                onClick = callbacks.onBackToKeyboard,
                iconRes = R.drawable.ic_baseline_arrow_back_24,
                iconColor = visuals.iconColor,
                contentDescription = stringResource(R.string.toolbar_back_to_keyboard),
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .height(LocalToolbarHeight.current),
            contentAlignment = Alignment.Center,
        ) {
            DockedButtonsRow(
                callbacks = callbacks,
                visuals = visuals,
                splitKeyboardEnabled = splitKeyboardEnabled,
                dockedButtons = dockedButtons,
            )
        }
    }
}

/**
 * 右侧固定元素：可选的更多按钮 + 尾随按钮。
 *
 * - 更多按钮：[showMore] 为 false 时不渲染（收起态与候选/剪贴板/数字行子态）；
 *   更多页上渲染但置灰不可点（已在目标页，无重复入口）。
 * - 尾随按钮：键盘页为收起键盘，更多页为「编辑 / 完成」。
 */
@Composable
private fun FixedTrailingRow(
    callbacks: ToolbarCallbacks,
    visuals: ToolbarVisuals,
    showMore: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(LocalToolbarHeight.current),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showMore) {
            IconButton(
                onClick = callbacks.onMore,
                // 更多页上该按钮只是一个位置标识：已在更多页，不再提供入口
                enabled = !callbacks.morePageVisible,
                modifier = Modifier
                    .size(LocalToolbarHeight.current)
                    .inputFeedback(),
                cornerRadius = LocalToolbarHeight.current / 2,
                minWidth = LocalToolbarHeight.current,
                minHeight = LocalToolbarHeight.current,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_baseline_more_horiz_24),
                    contentDescription = stringResource(R.string.status_area),
                    tint = if (callbacks.morePageVisible) {
                        visuals.iconColor.copy(alpha = 0.35f)
                    } else {
                        visuals.iconColor
                    },
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        if (callbacks.morePageVisible) {
            ToolbarIconButton(
                onClick = callbacks.onEditToggle,
                iconRes = if (callbacks.editing) R.drawable.ic_baseline_check_24
                else R.drawable.ic_baseline_drag_handle_24,
                iconColor = visuals.iconColor,
                contentDescription = stringResource(
                    if (callbacks.editing) R.string.toolbar_edit_done
                    else R.string.toolbar_edit_layout
                ),
            )
        } else {
            HideKeyboardButton(
                onClick = callbacks.onHideKeyboard,
                onSwipeLeft = callbacks.onNumberRowShow,
                iconColor = visuals.iconColor,
            )
        }
    }
}

/**
 * 可停靠按钮行：撤销/重做两个固定槽位（受偏好控制显隐，不参与拖动）之后，
 * 依次渲染布局里的可移动按钮。
 *
 * 横向滚动承载「按钮放不下」的情形 —— 图标不缩小、右侧固定元素不被挤压；
 * 「更多」不在本行内，故永远贴着可停靠区最右端。
 */
@Composable
private fun DockedButtonsRow(
    callbacks: ToolbarCallbacks,
    visuals: ToolbarVisuals,
    splitKeyboardEnabled: Boolean,
    dockedButtons: List<ToolbarButton>,
) {
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(LocalToolbarHeight.current)
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (callbacks.showUndoRedo) {
            ToolbarIconButton(
                onClick = callbacks.onUndo,
                iconRes = R.drawable.ic_baseline_undo_24,
                iconColor = visuals.iconColor,
                contentDescription = stringResource(R.string.undo),
            )
            ToolbarIconButton(
                onClick = callbacks.onRedo,
                iconRes = R.drawable.ic_baseline_redo_24,
                iconColor = visuals.iconColor,
                contentDescription = stringResource(R.string.redo),
            )
        }
        dockedButtons.forEach { button ->
            val usable = callbacks.buttonUsable(button)
            ToolbarMovableButton(
                button = button,
                // 不可用时置灰但仍占位：位置本身是布局信息，直接消失会让用户以为按钮丢失
                contentColor = if (usable) visuals.iconColor else visuals.iconColor.copy(alpha = 0.35f),
                enabled = usable,
                splitKeyboardEnabled = splitKeyboardEnabled,
                onClick = { callbacks.onButtonClick(button) },
            )
        }
    }
}

@Composable
private fun CandidateContent(
    candidateContent: @Composable () -> Unit,
    visible: Boolean,
) {
    // 淡入/淡出过渡，消除候选栏出现/消失时的硬切
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(200),
        label = "candidateAlpha",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(LocalToolbarHeight.current)
            .padding(end = 40.dp)
            .graphicsLayer { this.alpha = alpha },
        contentAlignment = Alignment.CenterStart,
    ) {
        candidateContent()
    }
}

@Composable
private fun TitleContent(
    titleData: TitleData?,
    callbacks: ToolbarCallbacks,
    visuals: ToolbarVisuals,
    extensionContent: @Composable (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(LocalToolbarHeight.current),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 返回按钮
        ToolbarIconButton(
            onClick = callbacks.onTitleBack,
            iconRes = R.drawable.ic_baseline_arrow_back_24,
            iconColor = visuals.iconColor,
        )

        // 标题文本
        if (titleData?.showTitle == true) {
            top.yukonga.miuix.kmp.basic.Text(
                text = titleData.title,
                color = visuals.textColor,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        // 扩展内容
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterEnd,
        ) {
            extensionContent?.invoke()
        }
    }
}

@Composable
private fun MenuButton(
    onClick: () -> Unit,
    iconColor: Color,
    rotation: Float = 0f,
    modifier: Modifier = Modifier,
) {
    var iconRotation by remember { mutableFloatStateOf(rotation) }
    val animatedRotation by animateFloatAsState(targetValue = iconRotation, label = "menuRotation")
    LaunchedEffect(rotation) { iconRotation = rotation }

    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(LocalToolbarHeight.current)
            .inputFeedback(),
        cornerRadius = LocalToolbarHeight.current / 2,
        minWidth = LocalToolbarHeight.current,
        minHeight = LocalToolbarHeight.current,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_baseline_expand_more_24),
            contentDescription = stringResource(R.string.expand_toolbar),
            tint = iconColor,
            modifier = Modifier
                .size(24.dp)
                .rotate(animatedRotation),
        )
    }
}

@Composable
private fun HideKeyboardButton(
    onClick: () -> Unit,
    onSwipeLeft: (() -> Unit)? = null,
    iconColor: Color,
    modifier: Modifier = Modifier,
) {
    var iconRotation by remember { mutableFloatStateOf(0f) }
    var cumulativeDx by remember { mutableFloatStateOf(0f) }
    var swipeLeftTriggered by remember { mutableStateOf(false) }

    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(LocalToolbarHeight.current)
            .inputFeedback()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        cumulativeDx = 0f
                        swipeLeftTriggered = false
                    },
                    onDragEnd = {
                        iconRotation = 0f
                    },
                    onDragCancel = {
                        iconRotation = 0f
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val (dx, dy) = dragAmount
                        // 垂直向下拖拽超过阈值时隐藏键盘
                        if (dy > size.height / 2f) {
                            onClick()
                        }
                        // 水平拖拽时旋转图标反馈
                        if (onSwipeLeft != null && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                            cumulativeDx += dx
                            iconRotation = (cumulativeDx / size.width * 90f).coerceIn(-45f, 45f)
                            if (!swipeLeftTriggered && cumulativeDx < -size.width / 3f) {
                                swipeLeftTriggered = true
                                onSwipeLeft()
                            }
                        }
                    },
                )
            },
        cornerRadius = LocalToolbarHeight.current / 2,
        minWidth = LocalToolbarHeight.current,
        minHeight = LocalToolbarHeight.current,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_baseline_arrow_drop_down_24),
            contentDescription = stringResource(R.string.hide_keyboard),
            tint = iconColor,
            modifier = Modifier
                .size(24.dp)
                .rotate(iconRotation),
        )
    }
}

@Composable
private fun ToolbarIconButton(
    onClick: () -> Unit,
    iconRes: Int,
    iconColor: Color,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    ToolbarIconButtonImpl(
        onClick = onClick,
        iconColor = iconColor,
        modifier = modifier,
    ) { tint ->
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun ToolbarIconButtonImpl(
    onClick: () -> Unit,
    iconColor: Color,
    modifier: Modifier = Modifier,
    icon: @Composable (Color) -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(LocalToolbarHeight.current)
            .inputFeedback(),
        cornerRadius = LocalToolbarHeight.current / 2,
        minWidth = LocalToolbarHeight.current,
        minHeight = LocalToolbarHeight.current,
    ) {
        icon(iconColor)
    }
}
