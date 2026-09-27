/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.withTimeoutOrNull
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.voice.VoicePermissionHelper
import org.fcitx.fcitx5.android.data.voice.VoicePermissionState
import org.fcitx.fcitx5.android.data.voice.VoiceRecognitionState
import org.fcitx.fcitx5.android.data.voice.VoiceUiState
import org.fcitx.fcitx5.android.input.keyboard.BackspaceKey
import org.fcitx.fcitx5.android.input.keyboard.ComposeKey
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.preferenceState
import org.fcitx.fcitx5.android.input.keyboard.spaceAndBackspaceGestureListener
import org.fcitx.fcitx5.android.input.keyboard.spaceAndBackspaceSwipeSpec
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 麦克风直径。 */
private const val MIC_SIZE_DP = 96

/** 点按/长按判定阈值：按下不超过它抬起＝点按切换，超过＝按住说话。 */
private const val HOLD_THRESHOLD_MS = 280L

/** 右侧删除键列宽度。 */
private val DELETE_COLUMN_WIDTH = 56.dp

/** 删除键的稳定 keyId（与键盘/Picker 的 id 空间错开）。 */
private const val VOICE_PANEL_BACKSPACE_KEY_ID = -0x2000

/**
 * custom: IME 语音面板的覆盖层宿主。
 *
 * 面板不是独立 `InputWindow`（那会顶掉键盘窗口 → 工具栏不可见、空格长按的手势被 CANCEL），
 * 而是挂在键盘窗口容器之上的 Compose 覆盖层：键盘窗口保持 attach，空格键的指针流不断，
 * 物理松手才能被键盘侧收到并停止识别（见 `CommonKeyActionListener.onKeyActionRelease`）。
 */
@Composable
fun VoicePanelHost(
    voice: VoiceInputComponent,
    keyActionListener: KeyActionListener?,
) {
    val visible by voice.panelVisible.collectAsState()
    // 不可见时不组合内容（宿主 View 的 GONE 由 InputView 经 panelVisibleListener 直接控制，
    // 从而既不挡键盘触摸、也不会把面板卡在隐藏态）
    if (!visible) return

    val view = LocalView.current
    LaunchedEffect(Unit) { VoicePermissionState.refresh(view.context) }

    val state by voice.state.collectAsState()
    val spectrum by voice.spectrum.collectAsState()
    val permissionGranted by VoicePermissionState.granted.collectAsState()

    ComposeVoicePanel(
        state = state,
        spectrum = spectrum,
        permissionGranted = permissionGranted,
        keyActionListener = keyActionListener,
        onMicStart = { voice.startRecognition() },
        onMicStop = { voice.stopRecognition() },
        onStopDiscard = { voice.cancelSession() },
        onGrantPermission = { VoicePermissionHelper.requestRecordAudioPermission(view.context) },
    )
}

/**
 * custom: IME 内的语音输入面板（miuix）。
 *
 * 布局：左侧自上而下为「状态行（引擎名 / 状态文案）→ 识别文本 → 对称频谱 → 大麦克风」，
 * 右侧为一列**标准删除键**（与主键盘同键型：按下删除、长按连发、横向滑动移动光标/删除选区）。
 * × 在麦克风左侧略上方：停止识别并丢弃尚未上屏的文本（面板保留，可继续说话）。
 * 没有自动模式开关、没有设置/模型入口（回键盘走工具栏返回键或空格路径出字自动收起）。
 */
@Composable
fun ComposeVoicePanel(
    state: VoiceUiState,
    spectrum: FloatArray,
    permissionGranted: Boolean,
    keyActionListener: KeyActionListener?,
    onMicStart: () -> Unit,
    onMicStop: () -> Unit,
    onStopDiscard: () -> Unit,
    onGrantPermission: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val listening = state.recognitionState == VoiceRecognitionState.LISTENING
    val preparing = state.recognitionState == VoiceRecognitionState.PREPARING
    val processing = state.recognitionState == VoiceRecognitionState.PROCESSING
    val busy = preparing || processing

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 状态行：引擎名 + 状态
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.engineName.ifBlank { stringResource(R.string.voice_input) },
                    color = colors.onSurfaceVariantSummary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(stateTextRes(state.recognitionState)),
                    color = colors.onSurfaceVariantSummary,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }

            // 加载模型 / 等待最终结果：细进度条，配合状态文案
            if (busy) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (!permissionGranted) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.voice_record_permission_missing),
                    color = colors.onSurface,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
                TextButton(
                    text = stringResource(R.string.voice_grant_permission),
                    onClick = onGrantPermission,
                )
            }

            // 识别文本（部分结果）—— 主区域
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                if (state.recognizedText.isNotEmpty()) {
                    Text(
                        text = state.recognizedText,
                        color = colors.onSurface,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else if (state.error != null) {
                    Text(
                        text = state.error.orEmpty(),
                        color = colors.onSurfaceVariantSummary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.voice_partial_hint),
                        color = colors.onSurfaceVariantSummary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
            }

            // 频谱：上下对称、收窄居中
            SpectrumBars(
                spectrum = spectrum,
                listening = listening,
                modifier = Modifier
                    .fillMaxWidth(0.62f)
                    .height(36.dp),
            )

            Spacer(Modifier.height(10.dp))

            // 主操作区：大麦克风居中偏下；× 在其左侧略上方（停止并丢弃未上屏文本）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height((MIC_SIZE_DP + 28).dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                IconButton(
                    onClick = onStopDiscard,
                    backgroundColor = colors.secondaryContainer,
                    cornerRadius = 20.dp,
                    minWidth = 40.dp,
                    minHeight = 40.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        // 麦克风左侧、略高于其中心（随麦克风一并下移）
                        .offset(x = (-(MIC_SIZE_DP / 2 + 30)).dp, y = (-62).dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_close_24),
                        contentDescription = stringResource(R.string.voice_stop_discard),
                        tint = colors.onSecondaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }

                MicButton(
                    listening = listening,
                    // 加载中也可点：第二次点按/再次松手即停止（stopRecognition 自身保证终态）
                    enabled = permissionGranted,
                    onTapToggle = { if (listening) onMicStop() else onMicStart() },
                    onHoldStart = onMicStart,
                    onHoldEnd = onMicStop,
                )
            }
        }

        // 右侧标准删除键
        VoiceDeleteKey(
            keyActionListener = keyActionListener,
            modifier = Modifier
                .width(DELETE_COLUMN_WIDTH)
                .fillMaxHeight()
                .padding(vertical = 8.dp, horizontal = 4.dp),
        )
    }
}

/**
 * 面板右侧的**标准删除键**。
 *
 * 直接复用主键盘的键型配置与手势监听器（[BackspaceKey] + [spaceAndBackspaceSwipeSpec] +
 * [spaceAndBackspaceGestureListener]），因此按下删除、长按连发、横向滑动移动光标 /
 * 删除选区的行为与键盘、Picker 右栏完全一致；按键动作经主键盘的 [KeyActionListener] 下发。
 */
@Composable
private fun VoiceDeleteKey(
    keyActionListener: KeyActionListener?,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val prefs = remember { AppPrefs.getInstance().keyboard }
    val hapticOnRepeat = prefs.hapticOnRepeat.preferenceState()
    val spaceSwipeMoveCursor = prefs.spaceSwipeMoveCursor.preferenceState()

    val backspaceKey = remember { BackspaceKey() }
    val listenerState = rememberUpdatedState(keyActionListener)
    val swipeSpec = remember(backspaceKey, spaceSwipeMoveCursor) {
        backspaceKey.spaceAndBackspaceSwipeSpec(spaceSwipeMoveCursor)
    }
    val gestureListener = remember(backspaceKey, hapticOnRepeat) {
        backspaceKey.spaceAndBackspaceGestureListener(
            view = view,
            onAction = { action ->
                listenerState.value?.onKeyAction(action, KeyActionListener.Source.Keyboard)
            },
            hapticOnRepeat = hapticOnRepeat,
        )
    }

    ComposeKey(
        def = backspaceKey,
        keyId = VOICE_PANEL_BACKSPACE_KEY_ID,
        modifier = modifier,
        keyActionListener = listenerState.value,
        swipeSpec = swipeSpec,
        onSwipeGesture = gestureListener,
    )
}

/**
 * 大号麦克风按钮：**点按切换 + 长按说话**。
 *
 * - 按下后在 [HOLD_THRESHOLD_MS] 内抬起 = 点按：启动/结束切换；
 * - 超过阈值仍按住 = 长按：立即开始，抬起（或手势被取消）即结束。
 */
@Composable
private fun MicButton(
    listening: Boolean,
    enabled: Boolean,
    onTapToggle: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val background = if (listening) colors.primary else colors.secondaryContainer
    val content = if (listening) colors.onPrimary else colors.onSecondaryContainer

    Box(
        modifier = Modifier
            .size(MIC_SIZE_DP.dp)
            .clip(CircleShape)
            .background(background)
            .then(
                if (!enabled) Modifier else Modifier.pointerInput(listening) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        val released = withTimeoutOrNull(HOLD_THRESHOLD_MS) {
                            waitForUpOrCancellation()
                        }
                        if (released == null) {
                            // 长按：按住说话，抬起（或取消）停止
                            onHoldStart()
                            waitForUpOrCancellation()
                            onHoldEnd()
                        } else {
                            onTapToggle()
                        }
                    }
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_baseline_keyboard_voice_24),
            contentDescription = stringResource(R.string.voice_input),
            tint = content,
            modifier = Modifier.size(40.dp),
        )
    }
}

/**
 * 频谱条形可视化（自绘；数据来自 [VoiceSpectrum]，16 个对数频段）。
 *
 * 以垂直中线为轴对称向上下两侧伸展（原来是只向上升），静音时保留一点可见高度。
 */
@Composable
private fun SpectrumBars(
    spectrum: FloatArray,
    listening: Boolean,
    modifier: Modifier = Modifier,
) {
    val barColor = if (listening) MiuixTheme.colorScheme.primary
    else MiuixTheme.colorScheme.dividerLine

    Canvas(modifier = modifier) {
        val count = VoiceInputComponent.SPECTRUM_BARS
        if (count <= 0) return@Canvas
        val gap = size.width / (count * 6f)
        val barWidth = (size.width - gap * (count - 1)) / count
        val centerY = size.height / 2f
        val maxHalf = size.height / 2f
        for (i in 0 until count) {
            val value = spectrum.getOrElse(i) { 0f }.coerceIn(0f, 1f)
            // 最小可见高度，避免静音时整条消失；上下各 half，整体对称
            val half = maxHalf * (0.12f + 0.88f * value)
            val left = i * (barWidth + gap)
            drawRoundRect(
                color = barColor,
                topLeft = Offset(left, centerY - half),
                size = Size(barWidth, half * 2f),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}

private fun stateTextRes(state: VoiceRecognitionState): Int = when (state) {
    VoiceRecognitionState.IDLE -> R.string.voice_state_idle
    VoiceRecognitionState.PREPARING -> R.string.voice_state_preparing
    VoiceRecognitionState.LISTENING -> R.string.voice_state_listening
    VoiceRecognitionState.PROCESSING -> R.string.voice_state_processing
    VoiceRecognitionState.ERROR -> R.string.voice_state_error
}
