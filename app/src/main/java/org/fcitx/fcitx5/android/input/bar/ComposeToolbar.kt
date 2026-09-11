/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import org.fcitx.fcitx5.android.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton

/**
 * Compose 工具栏
 *
 * 三态切换：Idle / Candidate / Title
 */
@Composable
fun ComposeToolbar(
    barState: KawaiiBarStateMachine.State,
    idleSubState: IdleSubState,
    titleData: TitleData?,
    callbacks: ToolbarCallbacks,
    visuals: ToolbarVisuals,
    expandButtonState: ExpandButtonStateMachine.State,
    splitKeyboardEnabled: Boolean = false,
    menuRotation: Float = 0f,
    modifier: Modifier = Modifier,
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
            visible = barState == KawaiiBarStateMachine.State.Candidate,
        )
        when (barState) {
            KawaiiBarStateMachine.State.Idle -> {
                IdleContent(
                    subState = idleSubState,
                    callbacks = callbacks,
                    visuals = visuals,
                    splitKeyboardEnabled = splitKeyboardEnabled,
                    menuRotation = menuRotation,
                    numberRowContent = numberRowContent,
                    inlineSuggestionContent = inlineSuggestionContent,
                    clipboardContent = clipboardContent,
                )
            }
            KawaiiBarStateMachine.State.Title -> {
                TitleContent(
                    titleData = titleData,
                    callbacks = callbacks,
                    visuals = visuals,
                    extensionContent = titleExtensionContent,
                )
            }
            else -> {}
        }
    }
}

@Composable
private fun IdleContent(
    subState: IdleSubState,
    callbacks: ToolbarCallbacks,
    visuals: ToolbarVisuals,
    splitKeyboardEnabled: Boolean,
    menuRotation: Float,
    numberRowContent: @Composable () -> Unit,
    inlineSuggestionContent: @Composable () -> Unit,
    clipboardContent: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ComposeKawaiiBarComponent.HEIGHT.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧：菜单按钮
        MenuButton(
            onClick = callbacks.onMenuClick,
            iconColor = visuals.iconColor,
            rotation = menuRotation,
        )

        // 中间内容区
        Box(
            modifier = Modifier
                .weight(1f)
                .height(ComposeKawaiiBarComponent.HEIGHT.dp),
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
                        ToolbarButtonsRow(
                            callbacks = callbacks,
                            visuals = visuals,
                            splitKeyboardEnabled = splitKeyboardEnabled,
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

        // 右侧：收起键盘按钮
        HideKeyboardButton(
            onClick = callbacks.onHideKeyboard,
            onSwipeLeft = callbacks.onNumberRowShow,
            iconColor = visuals.iconColor,
        )
    }
}

@Composable
private fun CandidateContent(
    candidateContent: @Composable () -> Unit,
    visible: Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ComposeKawaiiBarComponent.HEIGHT.dp)
            .padding(end = 40.dp)
            .graphicsLayer {
                alpha = if (visible) 1f else 0f
            },
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
            .height(ComposeKawaiiBarComponent.HEIGHT.dp),
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
private fun ToolbarButtonsRow(
    callbacks: ToolbarCallbacks,
    visuals: ToolbarVisuals,
    splitKeyboardEnabled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ComposeKawaiiBarComponent.HEIGHT.dp)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolbarIconButton(
            onClick = callbacks.onUndo,
            iconRes = R.drawable.ic_baseline_undo_24,
            iconColor = visuals.iconColor,
            contentDescription = "Undo",
        )
        ToolbarIconButton(
            onClick = callbacks.onRedo,
            iconRes = R.drawable.ic_baseline_redo_24,
            iconColor = visuals.iconColor,
            contentDescription = "Redo",
        )
        ToolbarIconButton(
            onClick = callbacks.onCursorMove,
            iconRes = R.drawable.ic_cursor_move,
            iconColor = visuals.iconColor,
            contentDescription = "Cursor Move",
        )
        ToolbarIconButton(
            onClick = callbacks.onClipboard,
            iconRes = R.drawable.ic_clipboard,
            iconColor = visuals.iconColor,
            contentDescription = "Clipboard",
        )
        ToolbarIconButton(
            onClick = callbacks.onSplitKeyboardToggle,
            iconRes = if (splitKeyboardEnabled) R.drawable.ic_baseline_keyboard_24 else R.drawable.ic_split_keyboard,
            iconColor = visuals.iconColor,
            contentDescription = "Split Keyboard",
        )
        ToolbarIconButton(
            onClick = callbacks.onMore,
            iconRes = R.drawable.ic_baseline_more_horiz_24,
            iconColor = visuals.iconColor,
            contentDescription = "More",
        )
        ToolbarIconButton(
            onClick = callbacks.onTune,
            iconRes = R.drawable.ic_baseline_tune_24,
            iconColor = visuals.iconColor,
            contentDescription = "Tune",
        )
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
    androidx.compose.runtime.LaunchedEffect(rotation) { iconRotation = rotation }

    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(ComposeKawaiiBarComponent.HEIGHT.dp)
            .inputFeedback(),
        cornerRadius = ComposeKawaiiBarComponent.HEIGHT.dp / 2,
        minWidth = ComposeKawaiiBarComponent.HEIGHT.dp,
        minHeight = ComposeKawaiiBarComponent.HEIGHT.dp,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_baseline_expand_more_24),
            contentDescription = "Menu",
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
            .size(ComposeKawaiiBarComponent.HEIGHT.dp)
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
        cornerRadius = ComposeKawaiiBarComponent.HEIGHT.dp / 2,
        minWidth = ComposeKawaiiBarComponent.HEIGHT.dp,
        minHeight = ComposeKawaiiBarComponent.HEIGHT.dp,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_baseline_arrow_drop_down_24),
            contentDescription = "Hide Keyboard",
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
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(ComposeKawaiiBarComponent.HEIGHT.dp)
            .inputFeedback(),
        cornerRadius = ComposeKawaiiBarComponent.HEIGHT.dp / 2,
        minWidth = ComposeKawaiiBarComponent.HEIGHT.dp,
        minHeight = ComposeKawaiiBarComponent.HEIGHT.dp,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = iconColor,
            modifier = Modifier.size(24.dp),
        )
    }
}
