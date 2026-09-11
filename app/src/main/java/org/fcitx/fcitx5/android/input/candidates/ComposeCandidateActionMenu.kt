/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates

import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.core.CandidateAction
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.input.bar.inputFeedback
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.wm.createComposeWindowView
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 候选操作菜单状态：锚点用窗口绝对坐标（[Rect]），菜单覆盖层填满 InputView，坐标可直接使用。 */
data class CandidateActionMenuState(
    val idx: Int,
    val title: String,
    val actions: List<CandidateAction>,
    val anchor: Rect,
)

/**
 * 候选操作菜单 Compose 覆盖层。
 *
 * 挂在 `InputView` 最顶层（`popup.root` 同级，填满 InputView），由统一的 ComposeView 宿主
 * （[createComposeWindowView]）承载，替代原先在 IME 内用系统 `PopupMenu` 的实现。
 *
 * 定位：候选调用方提供「窗口绝对坐标 [Rect]」作为锚点，菜单优先显示在锚点下方，
 * 空间不足时显示其上，并按覆盖层尺寸做边缘钳制。
 *
 * 触摸：菜单外区域为透明 dismiss 蒙层；菜单内动作行点击后触发对应 fcitx 动作并关闭。
 */
class ComposeCandidateActionMenu :
    UniqueComponent<ComposeCandidateActionMenu>(),
    Dependent,
    ManagedHandler by managedHandler() {

    private val service by manager.inputMethodService()
    private val fcitx by manager.fcitx()

    private val _state = MutableStateFlow<CandidateActionMenuState?>(null)
    val state: StateFlow<CandidateActionMenuState?> = _state.asStateFlow()

    val root: ComposeView by lazy {
        createComposeWindowView(service) {
            CandidateActionMenuOverlay(
                state = _state.collectAsState().value,
                onActionClick = { action ->
                    triggerAction(action)
                    dismiss()
                },
                onDismiss = ::dismiss,
            )
        }
    }

    fun show(idx: Int, text: String, anchor: Rect) {
        service.lifecycleScope.launch {
            val actions = fcitx.runOnReady { getCandidateActions(idx) }
            if (actions.isEmpty()) return@launch
            if (_state.value?.idx == idx) return@launch
            _state.value = CandidateActionMenuState(idx, text, actions.toList(), anchor)
            if (root.isAttachedToWindow) {
                InputFeedbacks.hapticFeedback(root, longPress = true)
            }
        }
    }

    fun dismiss() {
        _state.value = null
    }

    private fun triggerAction(action: CandidateAction) {
        val idx = _state.value?.idx ?: return
        fcitx.runIfReady { triggerCandidateAction(idx, action.id) }
    }
}

@Composable
private fun CandidateActionMenuOverlay(
    state: CandidateActionMenuState?,
    onActionClick: (CandidateAction) -> Unit,
    onDismiss: () -> Unit,
) {
    if (state == null) return
    val density = LocalDensity.current
    val radius = 12.dp
    val margin = 8.dp

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val overlayW = with(density) { maxWidth.toPx() }
        val overlayH = with(density) { maxHeight.toPx() }
        val marginPx = with(density) { margin.toPx() }

        // 菜单外区域点击关闭（透明蒙层）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
        )

        // 菜单本体尺寸，用于边缘钳制（onSizeChanged 触发重组后偏移即正确）
        var menuW by remember { mutableFloatStateOf(0f) }
        var menuH by remember { mutableFloatStateOf(0f) }

        val xPx = state.anchor.left.toFloat()
            .coerceIn(marginPx, (overlayW - menuW - marginPx).coerceAtLeast(marginPx))
        // 优先显示在锚点下方；空间不足（或首帧尺寸未知）时显示在上方
        val placeBelow =
            menuH == 0f || (overlayH - state.anchor.bottom.toFloat()) >= state.anchor.top.toFloat()
        val availableY = (overlayH - menuH - marginPx).coerceAtLeast(marginPx)
        val yPx = if (placeBelow) {
            (state.anchor.bottom.toFloat() + marginPx).coerceIn(marginPx, availableY)
        } else {
            (state.anchor.top.toFloat() - menuH - marginPx).coerceIn(marginPx, availableY)
        }

        Surface(
            modifier = Modifier
                .offset { IntOffset(xPx.roundToInt(), yPx.roundToInt()) }
                .onSizeChanged {
                    menuW = it.width.toFloat()
                    menuH = it.height.toFloat()
                },
            shape = RoundedCornerShape(radius),
            color = MiuixTheme.colorScheme.background,
            shadowElevation = 12.dp,
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                // 标题（候选词），仅展示不可点
                Text(
                    text = state.title,
                    color = MiuixTheme.colorScheme.primary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                if (state.actions.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MiuixTheme.colorScheme.dividerLine)
                    )
                }
                state.actions.forEach { action ->
                    CandidateActionMenuRow(action = action, onClick = onActionClick)
                }
            }
        }
    }
}

@Composable
private fun CandidateActionMenuRow(
    action: CandidateAction,
    onClick: (CandidateAction) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .inputFeedback()
            .clickable { onClick(action) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = action.text,
            color = MiuixTheme.colorScheme.onSurface,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}