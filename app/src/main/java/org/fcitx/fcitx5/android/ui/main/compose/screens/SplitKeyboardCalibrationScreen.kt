/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.screens

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.SplitKeyboardStateManager
import org.fcitx.fcitx5.android.utils.DeviceInfoCollector
import org.fcitx.fcitx5.android.utils.DeviceType
import org.fcitx.fcitx5.android.utils.toast
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 默认阈值：键盘宽高比达到该值时分体。 */
private const val DEFAULT_THRESHOLD = 1.4f
private const val THRESHOLD_MIN = 100
private const val THRESHOLD_MAX = 400
private const val DEFAULT_GAP = 20
private const val GAP_MIN = 5
private const val GAP_MAX = 60

/** 按设备类型给出推荐阈值与理由。 */
private fun recommendedThreshold(context: android.content.Context, info: DeviceInfoCollector.DeviceInfo): Pair<Float, String> =
    when (info.deviceType) {
        DeviceType.TABLET -> 1.15f to context.getString(R.string.split_keyboard_recommend_reason_tablet)
        DeviceType.FOLDABLE -> {
            if (info.hasMultipleScreens) {
                val minWidth = info.screenConfigs.minOfOrNull { it.smallestWidthDp } ?: 440
                (minWidth / 380f) to context.getString(R.string.split_keyboard_recommend_reason_foldable_multi, minWidth)
            } else {
                1.25f to context.getString(R.string.split_keyboard_recommend_reason_foldable)
            }
        }
        DeviceType.LARGE_PHONE -> 1.6f to context.getString(R.string.split_keyboard_recommend_reason_large_phone)
        DeviceType.SMALL_PHONE -> 1.5f to context.getString(R.string.split_keyboard_recommend_reason_small_phone)
        DeviceType.PHONE -> 1.4f to context.getString(R.string.split_keyboard_recommend_reason_phone)
    }

/** 按设备类型给出推荐间隙百分比。 */
private fun recommendedGap(type: DeviceType): Int = when (type) {
    DeviceType.TABLET -> 25
    DeviceType.FOLDABLE -> 20
    else -> 18
}

/** 设备类型的本地化名称。 */
private fun localizedType(context: android.content.Context, type: DeviceType): String = context.getString(
    when (type) {
        DeviceType.TABLET -> R.string.split_keyboard_device_type_tablet
        DeviceType.FOLDABLE -> R.string.split_keyboard_device_type_foldable
        DeviceType.LARGE_PHONE -> R.string.split_keyboard_device_type_large_phone
        DeviceType.SMALL_PHONE -> R.string.split_keyboard_device_type_small_phone
        DeviceType.PHONE -> R.string.split_keyboard_device_type_phone
    },
)

/**
 * 预览区的渲染状态：持有一份真实键盘实例，按阈值与间隙模拟分体。
 *
 * 分体效果通过容器内边距模拟，避免改动键盘自身的布局状态。
 */
private class KeyboardPreviewState(private val context: android.content.Context) {

    private val imeEntry = org.fcitx.fcitx5.android.ui.main.settings.preview.PreviewInputMethodEntry.create()
    private val blurMask = org.fcitx.fcitx5.android.ui.main.settings.behavior.preview.PreviewKeyBlurMaskView(context)
    private var keyboard: org.fcitx.fcitx5.android.input.keyboard.TextKeyboard? = null
    private var attached = false

    /** 首次挂载时构建键盘；之后只更新参数。 */
    fun attach(container: android.widget.FrameLayout) {
        if (attached) return
        attached = true
        val metrics = context.resources.displayMetrics
        val theme = org.fcitx.fcitx5.android.data.theme.ThemeManager.activeTheme
        val keyBorder = org.fcitx.fcitx5.android.data.theme.ThemeManager.prefs.keyBorder.getValue()
        container.background = theme.backgroundDrawable(keyBorder)
        container.addView(
            blurMask,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        val heightPercent = if (context.resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        ) {
            org.fcitx.fcitx5.android.data.prefs.AppPrefs.getInstance()
                .keyboard.keyboardHeightPercentLandscape.getValue()
        } else {
            org.fcitx.fcitx5.android.data.prefs.AppPrefs.getInstance()
                .keyboard.keyboardHeightPercent.getValue()
        }
        val keyboardHeight = metrics.heightPixels * heightPercent / 100
        val kb = org.fcitx.fcitx5.android.input.keyboard.TextKeyboard(
            context, theme, imeEntry, isPreview = true,
        )
        container.addView(
            kb,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                keyboardHeight,
            ),
        )
        kb.onAttach()
        kb.setTextScale(1.0f)
        kb.onInputMethodUpdate(imeEntry)
        blurMask.applyTheme(theme, keyBorder)
        blurMask.bindKeyboard(kb)
        kb.post { blurMask.refreshMask(hierarchyChanged = true) }
        keyboard = kb
    }

    /** 按当前阈值与间隙刷新预览的分体状态。 */
    fun update(container: android.widget.FrameLayout, threshold: Float, gapPercent: Int) {
        val kb = keyboard ?: return
        kb.setHorizontalGapScale(gapPercent / 100f)
        kb.refreshStyle()
        val metrics = context.resources.displayMetrics
        val keyboardPrefs = org.fcitx.fcitx5.android.data.prefs.AppPrefs.getInstance().keyboard
        val keyboardWidthDp = org.fcitx.fcitx5.android.utils.DeviceInfoCollector.calculateKeyboardWidthDp(
            context,
            keyboardPrefs.keyboardSidePadding.getValue(),
            keyboardPrefs.keyboardSidePaddingLandscape.getValue(),
        )
        val heightPercent = if (context.resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        ) {
            keyboardPrefs.keyboardHeightPercentLandscape.getValue()
        } else {
            keyboardPrefs.keyboardHeightPercent.getValue()
        }
        val keyboardHeightDp = (metrics.heightPixels / metrics.density) * heightPercent / 100f
        val ratio = if (keyboardHeightDp > 0f) keyboardWidthDp / keyboardHeightDp else 0f
        val split = ratio >= threshold
        // 分体时向两侧各让出间距，未分体时保持满宽
        val sidePadding = if (split) {
            (gapPercent / 100f * keyboardWidthDp * metrics.density / 2f).toInt()
        } else 0
        container.setPadding(sidePadding, 0, sidePadding, 0)
        blurMask.refreshMask()
    }

    /** 释放键盘实例。 */
    fun release() {
        keyboard?.onDetach()
        keyboard = null
        attached = false
    }
}
/**
 * 分体键盘校准页。
 *
 * 分体由键盘宽高比触发：比例达到阈值即分成左右两半，间隙按键盘宽度的百分比给出。
 * 页面提供阈值与间隙两个调节，并按当前设备给出推荐值。
 */
@Composable
fun SplitKeyboardCalibrationScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val manager = remember { SplitKeyboardStateManager.getInstance() }
    val keyboardPrefs = remember { AppPrefs.getInstance().keyboard }

    var threshold by remember { mutableFloatStateOf(manager.getSplitKeyboardThreshold()) }
    var gap by remember { mutableIntStateOf((manager.getSplitGapPercent() * 100).toInt()) }
    var deviceInfo by remember { mutableStateOf<DeviceInfoCollector.DeviceInfo?>(null) }
    var keyboardWidthDp by remember { mutableIntStateOf(0) }

    // 键盘尺寸设置变化时刷新宽度，预览与推荐值都以此为准
    DisposableEffect(Unit) {
        val listener = org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider.OnChangeListener {
            keyboardWidthDp = DeviceInfoCollector.calculateKeyboardWidthDp(
                context,
                keyboardPrefs.keyboardSidePadding.getValue(),
                keyboardPrefs.keyboardSidePaddingLandscape.getValue(),
            )
        }
        keyboardPrefs.registerOnChangeListener(listener)
        onDispose { keyboardPrefs.unregisterOnChangeListener(listener) }
    }

    LaunchedEffect(Unit) {
        deviceInfo = DeviceInfoCollector.collect(context)
        keyboardWidthDp = DeviceInfoCollector.calculateKeyboardWidthDp(
            context,
            keyboardPrefs.keyboardSidePadding.getValue(),
            keyboardPrefs.keyboardSidePaddingLandscape.getValue(),
        )
    }

    val orientationLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val splitEnabled = keyboardPrefs.splitKeyboardEnabled.getValue()
    val willSplit = splitEnabled && keyboardWidthDp >= threshold

    PageScaffold(title = stringResource(R.string.split_keyboard_calibration_title), onBack = onBack) {
        // 实时预览：用真实键盘按当前阈值与间隙渲染，分体效果直接可见
        item {
            Box(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                val previewState = remember { KeyboardPreviewState(context) }
                AndroidView(
                    factory = { ctx ->
                        android.widget.FrameLayout(ctx).apply {
                            minimumHeight = (200 * ctx.resources.displayMetrics.density).toInt()
                            previewState.attach(this)
                        }
                    },
                    update = { container -> previewState.update(container, threshold, gap) },
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                )
                DisposableEffect(previewState) {
                    onDispose { previewState.release() }
                }
            }
        }

        item { SmallTitle(text = stringResource(R.string.split_keyboard_threshold_label)) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                Text(
                    text = String.format("%.2fx", threshold),
                    modifier = Modifier.padding(16.dp),
                )
                Slider(
                    value = threshold * 100f,
                    onValueChange = { value ->
                        threshold = value / 100f
                        manager.setSplitKeyboardThreshold(threshold)
                        keyboardWidthDp = DeviceInfoCollector.calculateKeyboardWidthDp(
                            context,
                            keyboardPrefs.keyboardSidePadding.getValue(),
                            keyboardPrefs.keyboardSidePaddingLandscape.getValue(),
                        )
                    },
                    valueRange = THRESHOLD_MIN.toFloat()..THRESHOLD_MAX.toFloat(),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        item { SmallTitle(text = stringResource(R.string.split_keyboard_gap_label)) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                Text(
                    text = "$gap %",
                    modifier = Modifier.padding(16.dp),
                )
                Slider(
                    value = gap.toFloat(),
                    onValueChange = { value ->
                        gap = value.toInt()
                        manager.setSplitGapPercent(gap)
                    },
                    valueRange = GAP_MIN.toFloat()..GAP_MAX.toFloat(),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        item { SmallTitle(text = stringResource(R.string.split_keyboard_info_type)) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                val info = deviceInfo
                if (info == null) {
                    Text(
                        text = stringResource(R.string.rime_engine_loading),
                        modifier = Modifier.padding(16.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                } else {
                    val orientation = context.getString(
                        if (orientationLandscape) R.string.split_keyboard_orientation_landscape
                        else R.string.split_keyboard_orientation_portrait,
                    )
                    val (recommended, reason) = recommendedThreshold(context, info)
                    Text(
                        text = buildString {
                            append(context.getString(R.string.split_keyboard_info_type))
                            append(": ").append(localizedType(context, info.deviceType))
                            append("  ").append(context.getString(R.string.split_keyboard_info_orientation))
                            append(": ").append(orientation)
                            append("  ").append(context.getString(R.string.split_keyboard_info_min_width))
                            append(": ").append(info.smallestWidthDp).append("dp")
                            append("\n")
                            append(context.getString(R.string.split_keyboard_info_keyboard_width))
                            append(": ").append(keyboardWidthDp).append("dp")
                            append("  ").append(context.getString(R.string.split_keyboard_info_split))
                            append(": ").append(
                                context.getString(
                                    if (willSplit) R.string.split_keyboard_split_yes
                                    else R.string.split_keyboard_split_no,
                                ),
                            )
                            append("\n")
                            append(context.getString(R.string.split_keyboard_info_threshold))
                            append(": ").append(String.format("%.2fx", threshold))
                            append("  ").append(context.getString(R.string.split_keyboard_info_gap))
                            append(": ").append(gap).append('%')
                            append("\n")
                            append(context.getString(R.string.split_keyboard_info_recommended))
                            append(": ").append(String.format("%.2f", recommended))
                            append("dp (").append(reason).append(')')
                            if (info.hasMultipleScreens) {
                                append("  ").append(context.getString(R.string.split_keyboard_info_multi_screen))
                                append(": ").append(info.screenConfigs.size)
                            }
                        },
                        modifier = Modifier.padding(16.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                ArrowPreference(
                    title = stringResource(R.string.split_keyboard_auto_calibrate),
                    modifier = Modifier.weight(1f).padding(end = 4.dp),
                    onClick = {
                        val info = deviceInfo ?: return@ArrowPreference
                        val (recommended, reason) = recommendedThreshold(context, info)
                        threshold = recommended
                        gap = recommendedGap(info.deviceType)
                        manager.setSplitKeyboardThreshold(recommended)
                        manager.setSplitGapPercent(gap)
                        context.toast(
                            context.getString(
                                R.string.split_keyboard_auto_calibrate_toast,
                                String.format("%.2fx", recommended),
                                gap,
                                reason,
                            ),
                        )
                    },
                )
                ArrowPreference(
                    title = stringResource(R.string.split_keyboard_reset),
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                    onClick = {
                        threshold = DEFAULT_THRESHOLD
                        gap = DEFAULT_GAP
                        manager.setSplitKeyboardThreshold(DEFAULT_THRESHOLD)
                        manager.setSplitGapPercent(DEFAULT_GAP)
                        context.toast(R.string.split_keyboard_reset_toast)
                    },
                )
            }
        }
    }
}
