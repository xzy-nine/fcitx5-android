/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.service.VoiceUiState
import com.kingzcheung.xime.speech.RecognitionState
import org.fcitx.fcitx5.android.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * custom: IME 内的语音输入面板（miuix）。
 *
 * 结构与 whisperIME 的语音面板一致（进度行 / 中间状态 / 底部三按钮行），
 * 主题与组件全部换成 miuix（不引入 Material3），并按本仓库 IME 的约束，
 * 由 [VoiceInputWindow] 包一层 miuix `Scaffold` 提供 Overlay 宿主。
 */
@Composable
fun ComposeVoicePanel(
    state: VoiceUiState,
    spectrum: FloatArray,
    permissionGranted: Boolean,
    autoMode: Boolean,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    onToggleAutoMode: () -> Unit,
    onCancel: () -> Unit,
    onBackToKeyboard: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModels: () -> Unit,
    onGrantPermission: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val listening = state.voiceRecognitionState == RecognitionState.LISTENING
    val processing = state.voiceRecognitionState == RecognitionState.PROCESSING

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 引擎名 + 状态
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.voicePluginName.ifBlank { stringResource(R.string.voice_input) },
                color = colors.onSurfaceVariantSummary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(stateTextRes(state.voiceRecognitionState, listening)),
                color = colors.onSurfaceVariantSummary,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }

        Spacer(Modifier.height(6.dp))

        if (processing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
        }

        if (!permissionGranted) {
            Text(
                text = stringResource(R.string.voice_record_permission_missing),
                color = colors.onSurface,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            TextButton(
                text = stringResource(R.string.voice_grant_permission),
                onClick = onGrantPermission,
            )
            Spacer(Modifier.height(4.dp))
        }

        // 识别文本（部分结果）—— 主区域
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (state.voiceRecognizedText.isNotEmpty()) {
                Text(
                    text = state.voiceRecognizedText,
                    color = colors.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
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

        // 频谱可视化
        SpectrumBars(
            spectrum = spectrum,
            listening = listening,
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp),
        )

        Spacer(Modifier.height(8.dp))

        // 底部三按钮行：取消 / 麦克风 / 自动模式
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onCancel,
                backgroundColor = colors.secondaryContainer,
                cornerRadius = 20.dp,
                minWidth = 40.dp,
                minHeight = 40.dp,
                modifier = Modifier.semantics {
                    contentDescription = "Cancel"
                },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_baseline_close_24),
                    contentDescription = null,
                    tint = colors.onSecondaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }

            MicButton(
                listening = listening,
                autoMode = autoMode,
                onPressStart = onPressStart,
                onPressEnd = onPressEnd,
            )

            IconButton(
                onClick = onToggleAutoMode,
                backgroundColor = if (autoMode) colors.primary else colors.secondaryContainer,
                cornerRadius = 20.dp,
                minWidth = 40.dp,
                minHeight = 40.dp,
                modifier = Modifier.semantics {
                    contentDescription = "Auto mode"
                },
            ) {
                Icon(
                    // 自动模式只有一个矢量图：开/关用底色与前景色区分
                    painter = painterResource(R.drawable.ic_baseline_auto_awesome_24),
                    contentDescription = null,
                    tint = if (autoMode) colors.onPrimary else colors.onSecondaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // 次级入口：设置 / 模型 / 返回键盘
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                text = stringResource(R.string.voice_open_settings),
                onClick = onOpenSettings,
            )
            TextButton(
                text = stringResource(R.string.voice_models),
                onClick = onOpenModels,
            )
            TextButton(
                text = stringResource(R.string.voice_back_to_keyboard),
                onClick = onBackToKeyboard,
            )
        }
    }
}

/**
 * 大号麦克风按钮：自动模式下点按切换，非自动模式下按住说话。
 *
 * 用 pointerInput 自己实现按下/抬起（whisperIME 的语音面板也是这么做的），
 * 不依赖 miuix IconButton 是否支持长按回调。
 */
@Composable
private fun MicButton(
    listening: Boolean,
    autoMode: Boolean,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val background = if (listening) colors.primary else colors.secondaryContainer
    val content = if (listening) colors.onPrimary else colors.onSecondaryContainer

    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(background)
            .pointerInput(autoMode, listening) {
                detectTapGestures(
                    onPress = {
                        if (!autoMode) {
                            onPressStart()
                            tryAwaitRelease()
                            onPressEnd()
                        }
                    },
                    onTap = {
                        if (autoMode) {
                            if (listening) onPressEnd() else onPressStart()
                        }
                    },
                )
            }
            .semantics { contentDescription = "Voice input" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_baseline_keyboard_voice_24),
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(32.dp),
        )
    }
}

/**
 * 频谱条形可视化（自绘；数据来自 Xime 的 SpectrumAnalyzer，16 个对数频段）。
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
        for (i in 0 until count) {
            val value = spectrum.getOrElse(i) { 0f }.coerceIn(0f, 1f)
            // 最小可见高度，避免静音时整条消失
            val h = (size.height * (0.08f + 0.92f * value))
            val left = i * (barWidth + gap)
            drawRoundRect(
                color = barColor,
                topLeft = androidx.compose.ui.geometry.Offset(left, size.height - h),
                size = androidx.compose.ui.geometry.Size(barWidth, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f),
            )
        }
    }
}

private fun stateTextRes(state: RecognitionState, listening: Boolean): Int = when (state) {
    RecognitionState.IDLE -> R.string.voice_state_idle
    RecognitionState.LISTENING -> R.string.voice_state_listening
    RecognitionState.PROCESSING -> if (listening) R.string.voice_state_listening
    else R.string.voice_state_processing
    RecognitionState.ERROR -> R.string.voice_state_error
}
