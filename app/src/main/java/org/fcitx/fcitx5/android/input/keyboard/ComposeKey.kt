/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs.PunctuationPosition
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Border
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Variant
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.utils.styledFloat
import top.yukonga.miuix.kmp.basic.Icon

/**
 * Compose 版按键原语（`Docs/KeyboardComposePlan.md` 批次 B）。
 *
 * **手势模型**：每键一个 `pointerInput`（§4.1）。多指并发不需要任何额外代码 ——
 * 不同手指的 DOWN 命中不同的键节点，各自独立跑手势协程；与 View 侧
 * 「DOWN 固定目标 + 移出本键即取消、不换键」是 1:1 的（§4.4）。
 *
 * 语义来源：`CustomGestureView.onTouchEvent` + `BaseKeyboard.createKeyView` 的
 * `setOnClickListener / setOnLongClickListener / onRepeatListener / onGestureListener /
 * onDoubleTapListener` 拟合（D5/D6/D8）。
 *
 * **字号**：按 View 侧口径复刻 —— `KeyView` 用的是 `COMPLEX_UNIT_DIP`，即**不随系统字号缩放**，
 * 且键盘按键的 `AutoScaleTextView.scaleMode` 是默认的 `Mode.None`（**不自动缩放、溢出裁切**，
 * 只有 Picker / 气泡小键盘 / 候选才用 `Proportional`）。故此处用 `dp.toSp()` 抵消 fontScale，
 * 也不做 shrink-to-fit。
 */
@Composable
fun ComposeKey(
    def: KeyDef,
    /** 稳定 Int id（D6）：同一键在重组前后必须不变，供 [PopupAction] 索引。 */
    keyId: Int,
    modifier: Modifier = Modifier,
    visuals: KeyboardVisuals = rememberKeyboardVisuals(),
    keyActionListener: KeyActionListener? = null,
    popupActionListener: PopupActionListener? = null,
    enabled: Boolean = true,
    /**
     * 视觉内缩。[KeyboardVisuals.defaultInsets] 是默认值；
     * `expandKeypressArea` 让首尾键触摸区变宽时，由 [ComposeKeyRow] 额外叠加单侧内缩。
     */
    insets: KeyInsets? = null,
    /** 键型专属滑行（空格移动光标 / 退格删除选区），由键盘容器传入，见 [ComposeKeySwipeSpec]。 */
    swipeSpec: ComposeKeySwipeSpec? = null,
    onSwipeGesture: ComposeKeyGestureListener? = null,
    /**
     * 取消世代（§4.3 的 epoch 自增广播）：容器层区域手势（数字行收起、`spaceSwipeMoveCursor`）
     * 生效时递增。键的手势节点随之重启 → 当前手势被取消，**不会**在松手时补发 Press。
     *
     * 这条对应 View 侧 `onInterceptTouchEvent` 抢占后子 View 收到 `ACTION_CANCEL`
     * （`CustomGestureView.cancelGestures()`）。没有它，容器滑动手势会同时上屏一个字符。
     */
    cancelEpoch: Int = 0,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val interactionSource = remember { MutableInteractionSource() }
    // 按下态只为「视觉高亮」服务：读点在 draw 阶段（drawBehind），写点在手势协程，
    // 因此不会引起重组。§4.2 的 miuix `pressable + SinkFeedback` 路线需要再挂一个手势节点
    // 与键自身的 pointerInput 争同一指针，这里按 View 侧语义（高亮而非缩放）自绘。
    val pressed = remember { mutableStateOf(false) }
    val windowBounds = remember { BoundsHolder() }
    val doubleTapState = remember { DoubleTapState() }

    val keyActionListenerState = rememberUpdatedState(keyActionListener)
    val popupActionListenerState = rememberUpdatedState(popupActionListener)
    val swipeGestureState = rememberUpdatedState(onSwipeGesture)
    val enabledState = rememberUpdatedState(enabled)

    // 偏好读取：View 侧是在**手势时刻**读（`if (popupOnKeyPress)` / `delay(longPressDelay)` /
    // `swipeSymbolDirection.checkY(...)` / repeat 里读 hapticOnRepeat），改偏好下一次手势即生效。
    // 这里保持同一口径 —— 传 lambda 而不是取值快照，手势协程里才读。
    val prefs = remember { AppPrefs.getInstance().keyboard }

    // 键面变换（caps/标点/图标）会产出**新的 KeyDef 实例**，但 behaviors/popup 是复用同一份。
    // 这里只按这两者做 key，避免「只是换了个键面」也把 pointerInput 手势节点重启
    // （重启会取消进行中的手势）——id 与尺寸稳定时手势节点必须常驻。
    val spec = remember(def.behaviors, def.popup, swipeSpec) {
        buildKeyGestureSpec(def, swipeSpec)
    }
    val appearance = def.appearance
    // View: KeyView.setEnabled(false) → appearanceView.alpha = styledFloat(disabledAlpha)
    val context = LocalContext.current
    val disabledAlpha = remember(context) { context.styledFloat(android.R.attr.disabledAlpha) }

    Box(
        modifier = modifier
            .pointerInput(spec, enabled, cancelEpoch) {
                if (!enabledState.value) return@pointerInput
                val env = KeyGestureEnv(
                    keyId = keyId,
                    spec = spec,
                    view = view,
                    scope = scope,
                    interactionSource = interactionSource,
                    pressed = pressed,
                    windowBounds = windowBounds,
                    doubleTapState = doubleTapState,
                    popupOnKeyPress = { prefs.popupOnKeyPress.getValue() },
                    swipeSymbolDirection = { prefs.swipeSymbolDirection.getValue() },
                    longPressDelay = { prefs.longPressDelay.getValue() },
                    hapticOnRepeat = { prefs.hapticOnRepeat.getValue() },
                    sendAction = { action, source ->
                        keyActionListenerState.value?.onKeyAction(action, source)
                    },
                    sendPopup = { popupActionListenerState.value?.onPopupAction(it) },
                    onSwipeGesture = { swipeGestureState.value?.onGesture(it) ?: false },
                )
                awaitEachGesture { runKeyGesture(env) }
            }
            .onGloballyPositioned { coordinates ->
                // 只在布局后取一次窗口坐标（D7）：气泡锚点，按下那一刻再读
                val r = coordinates.boundsInWindow()
                windowBounds.rect = Rect(
                    r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt()
                )
            }
            .alpha(if (enabled) 1f else disabledAlpha),
        contentAlignment = Alignment.Center,
    ) {
        // 视觉层：手感区 = 整格（pointerInput 在 padding 之前），视觉区 = 内缩后（§4.5-①）
        val specialShape = visuals.usesSpecialKeyShape(def)
        val effectiveInsets = insets
            ?: if (specialShape) KeyInsets.Zero else visuals.defaultInsets(appearance.margin)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = effectiveInsets.start,
                    end = effectiveInsets.end,
                    top = effectiveInsets.top,
                    bottom = effectiveInsets.bottom,
                )
                .drawBehind {
                    drawKeySkin(
                        visuals = visuals,
                        variant = appearance.variant,
                        border = appearance.border,
                        viewId = appearance.viewId,
                        specialShape = specialShape,
                        pressed = pressed.value,
                    )
                },
        )
        KeyContent(def, visuals, effectiveInsets)
    }
}

// ---------------------------------------------------------------------------
// 键面内容
// ---------------------------------------------------------------------------

@Composable
private fun KeyContent(def: KeyDef, visuals: KeyboardVisuals, insets: KeyInsets) {
    val appearance = def.appearance
    val textColor = visuals.textColorFor(appearance.variant)
    when (appearance) {
        // 注意顺序：ImageText / AltText 都继承自 Text，必须先判子类
        is KeyDef.Appearance.Image -> Icon(
            painter = painterResource(appearance.src),
            contentDescription = null,
            tint = textColor,
        )

        is KeyDef.Appearance.ImageText -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // View 侧 ImageTextKeyView 的图标固定 dp(13)（ImageKeyView 则用固有尺寸）
            Icon(
                painter = painterResource(appearance.src),
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(13.dp),
            )
            KeyText(appearance, appearance.displayText, textColor)
        }

        is KeyDef.Appearance.AltText -> AltTextKeyContent(appearance, visuals, insets, textColor)

        is KeyDef.Appearance.Text -> KeyText(appearance, appearance.displayText, textColor)
    }
}

/**
 * `AltTextKeyView.applyLayout` 的 Compose 复刻：副文本按 `punctuationPosition` 偏好 +
 * 屏幕方向决定放右上还是底部；`punctuationPosition == None` 时副文本不显示。
 */
@Composable
private fun AltTextKeyContent(
    appearance: KeyDef.Appearance.AltText,
    visuals: KeyboardVisuals,
    insets: KeyInsets,
    textColor: Color,
) {
    val landscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val punctuationPosition = remember { ThemeManager.prefs.punctuationPosition.getValue() }
    val topRight = when (punctuationPosition) {
        PunctuationPosition.TopRight -> true
        PunctuationPosition.None -> false
        // Bottom：竖屏放底部，横屏放右上（View 侧同一分支）
        PunctuationPosition.Bottom -> landscape
    }
    val showAlt = punctuationPosition != PunctuationPosition.None
    val altTextColor = when (appearance.variant) {
        Variant.Normal, Variant.AltForeground, Variant.Alternative -> visuals.altKeyTextColor
        Variant.Accent -> visuals.accentKeyTextColor
    }

    when {
        !showAlt -> KeyText(appearance, appearance.displayText, textColor)

        topRight -> Box(modifier = Modifier.fillMaxSize()) {
            KeyText(
                appearance, appearance.displayText, textColor,
                modifier = Modifier.align(Alignment.Center),
            )
            AltText(
                text = appearance.altText,
                color = altTextColor,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    // View: topMargin = vMargin, rightMargin = hMargin + dp(4)
                    .padding(top = insets.top, end = insets.end + 4.dp),
            )
        }

        else -> Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            KeyText(appearance, appearance.displayText, textColor)
            // View: altText 贴底、bottomMargin = vMargin + dp(2)
            AltText(
                text = appearance.altText,
                color = altTextColor,
                modifier = Modifier.padding(top = 2.dp, bottom = insets.bottom + 2.dp),
            )
        }
    }
}

/** 副文本样式（View 侧 `AltTextKeyView`：固定 dp(10.666667) + BOLD）。 */
@Composable
private fun AltText(text: String, color: Color, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            fontSize = with(density) { 10.666667.dp.toSp() },
            color = color,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        ),
        maxLines = 1,
        softWrap = false,
    )
}

/**
 * 键面主文本。
 *
 * 字号用 `dp.toSp()`：View 侧是 `COMPLEX_UNIT_DIP`，不随系统字号缩放；`scaleMode = None`
 * 表示不缩放、放不下就裁切，所以这里也不加 shrink-to-fit。
 * `textStyle` 的 BOLD/ITALIC 位直接映射到 [FontWeight]/[FontStyle]。
 */
@Composable
private fun KeyText(
    appearance: KeyDef.Appearance.Text,
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            fontSize = with(density) { appearance.textSize.dp.toSp() },
            color = color,
            fontWeight = if (appearance.textStyle and Typeface.BOLD != 0) {
                FontWeight.Bold
            } else {
                FontWeight.Normal
            },
            fontStyle = if (appearance.textStyle and Typeface.ITALIC != 0) {
                FontStyle.Italic
            } else {
                FontStyle.Normal
            },
            textAlign = TextAlign.Center,
        ),
        maxLines = 1,
        softWrap = false,
    )
}

// ---------------------------------------------------------------------------
// 键的皮肤（对应 KeyView 的 Drawable 组装，D11）
// ---------------------------------------------------------------------------

private fun DrawScope.drawKeySkin(
    visuals: KeyboardVisuals,
    variant: Variant,
    border: Border,
    viewId: Int,
    specialShape: Boolean,
    pressed: Boolean,
) {
    val radius = CornerRadius(visuals.cornerRadius.toPx())
    val strokeWidth = 1.dp.toPx()
    val hasBorder = visuals.hasBorder(border)

    // Border.Special 且 keyBorder 关闭：空格条 / 回车键走专用形状（KeyView.onSizeChanged）
    if (specialShape) {
        when (viewId) {
            R.id.button_space -> {
                val hInset = KeyboardVisuals.SpaceBarHorizontalInset.toPx()
                val minHeight = KeyboardVisuals.SpaceBarMinHeight.toPx()
                val maxVInset = KeyboardVisuals.SpaceBarMaxVerticalInset.toPx()
                val vInset = if (size.height < minHeight) {
                    0f
                } else {
                    min((size.height - minHeight) / 2f, maxVInset)
                }
                drawRoundRect(
                    color = visuals.spaceBarColor,
                    topLeft = Offset(hInset, vInset),
                    size = Size(
                        width = (size.width - 2f * hInset).coerceAtLeast(0f),
                        height = (size.height - 2f * vInset).coerceAtLeast(0f),
                    ),
                    cornerRadius = CornerRadius(KeyboardVisuals.SpaceBarCornerRadius.toPx()),
                )
                if (pressed) {
                    // KeyView.setupPressHighlight(mask)：与底色同形状的高亮
                    // （ripple 模式 mask 取白色只定形状、颜色由 ripple 提供，二者最终都是
                    // keyPressHighlightColor，故这里直接用该色）
                    drawRoundRect(
                        color = visuals.keyPressHighlightColor,
                        topLeft = Offset(hInset, vInset),
                        size = Size(
                            width = (size.width - 2f * hInset).coerceAtLeast(0f),
                            height = (size.height - 2f * vInset).coerceAtLeast(0f),
                        ),
                        cornerRadius = CornerRadius(
                            KeyboardVisuals.SpaceBarCornerRadius.toPx()
                        ),
                    )
                }
            }

            R.id.button_return -> {
                val diameter = min(
                    min(size.width, size.height),
                    KeyboardVisuals.ReturnKeyMaxDiameter.toPx()
                )
                val topLeft = Offset(
                    x = (size.width - diameter) / 2f,
                    y = (size.height - diameter) / 2f,
                )
                val ovalSize = Size(diameter, diameter)
                drawOval(color = visuals.accentKeyBackgroundColor, topLeft = topLeft, size = ovalSize)
                if (pressed) {
                    drawOval(
                        color = visuals.keyPressHighlightColor,
                        topLeft = topLeft,
                        size = ovalSize,
                    )
                }
            }

            else -> Unit
        }
        return
    }

    if (hasBorder) {
        if (visuals.borderStroke) {
            // borderedKeyBackgroundDrawable：底色 + 1dp 描边
            drawRoundRect(color = visuals.backgroundColorFor(variant), cornerRadius = radius)
            drawRoundRect(
                color = visuals.keyShadowColor,
                cornerRadius = radius,
                style = Stroke(width = strokeWidth),
            )
        } else {
            // shadowedKeyBackgroundDrawable：同尺寸圆角矩形下移 1dp 当假阴影（§4.5-⑥）
            drawRoundRect(
                color = visuals.keyShadowColor,
                topLeft = Offset(0f, strokeWidth),
                size = size,
                cornerRadius = radius,
            )
            drawRoundRect(color = visuals.backgroundColorFor(variant), cornerRadius = radius)
        }
    }
    if (pressed) {
        if (visuals.bordered && visuals.borderStroke && !visuals.rippled) {
            // KeyView.setupPressHighlight 的 StateListDrawable 分支：2dp 边框变深
            drawRoundRect(
                color = visuals.keyShadowColor,
                cornerRadius = radius,
                style = Stroke(width = 2.dp.toPx()),
            )
        } else {
            // 其余分支都是「按下高亮填充」（View 侧 ripple 的静态等价；圆角只在 bordered 时收）
            drawRoundRect(
                color = visuals.keyPressHighlightColor,
                cornerRadius = if (visuals.bordered) radius else CornerRadius.Zero,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 手势
// ---------------------------------------------------------------------------

/** 长按重复间隔，与 `CustomGestureView.RepeatInterval` 一致。 */
private const val RepeatInterval = 50L

/** 由 [KeyDef] 解析出的按键行为（对应 `BaseKeyboard.createKeyView` 的 when 分支集合）。 */
private class KeyGestureSpec(
    val pressAction: KeyAction?,
    val longPressAction: KeyAction?,
    val repeatAction: KeyAction?,
    val doubleTapAction: KeyAction?,
    val swipeAction: KeyAction?,
    val previewPopup: KeyDef.Popup.Preview?,
    val altPreviewPopup: KeyDef.Popup.AltPreview?,
    val longPressPopup: KeyDef.Popup?,
    val swipe: ComposeKeySwipeSpec?,
    val soundEffect: InputFeedbacks.SoundEffect,
)

private fun buildKeyGestureSpec(def: KeyDef, externalSwipe: ComposeKeySwipeSpec?): KeyGestureSpec {
    var press: KeyAction? = null
    var longPress: KeyAction? = null
    var repeat: KeyAction? = null
    var doubleTap: KeyAction? = null
    var swipeAction: KeyAction? = null
    def.behaviors.forEach {
        when (it) {
            is KeyDef.Behavior.Press -> press = it.action
            is KeyDef.Behavior.LongPress -> longPress = it.action
            is KeyDef.Behavior.Repeat -> repeat = it.action
            is KeyDef.Behavior.DoubleTap -> doubleTap = it.action
            is KeyDef.Behavior.Swipe -> swipeAction = it.action
        }
    }
    var preview: KeyDef.Popup.Preview? = null
    var longPressPopup: KeyDef.Popup? = null
    def.popup?.forEach {
        when (it) {
            is KeyDef.Popup.Menu, is KeyDef.Popup.Keyboard ->
                if (longPressPopup == null) longPressPopup = it

            is KeyDef.Popup.Preview -> if (preview == null) preview = it
        }
    }
    // 滑行阈值优先级与 View 侧一致：键型（空格/退格）> Behavior.Swipe > 弹层键
    // （View 里 behaviors 先于 popup 设置阈值，popup 只把 swipeEnabled 置真）
    val swipe = externalSwipe
        ?: if (swipeAction != null) {
            ComposeKeySwipeSpec(
                thresholdX = KeySwipeThresholds.Disabled,
                thresholdY = KeySwipeThresholds.Input,
            )
        } else if (longPressPopup != null) {
            ComposeKeySwipeSpec()
        } else {
            null
        }
    return KeyGestureSpec(
        pressAction = press,
        longPressAction = longPress,
        repeatAction = repeat,
        doubleTapAction = doubleTap,
        swipeAction = swipeAction,
        previewPopup = preview,
        altPreviewPopup = preview as? KeyDef.Popup.AltPreview,
        longPressPopup = longPressPopup,
        swipe = swipe,
        // View 在 createKeyView 里按 `is SpaceKey/...` 覆写音效，而 KeyDefPreset 已把同样的值
        // 写进 appearance.soundEffect，故此处直接取 appearance 即可（显式覆盖也仍然生效）。
        soundEffect = def.appearance.soundEffect,
    )
}

private class BoundsHolder {
    var rect: Rect = Rect()
}

private class DoubleTapState {
    var maybeDoubleTap = false
    var lastClickTime = 0L
}

private class KeyGestureEnv(
    val keyId: Int,
    val spec: KeyGestureSpec,
    val view: View,
    val scope: CoroutineScope,
    val interactionSource: MutableInteractionSource,
    val pressed: MutableState<Boolean>,
    val windowBounds: BoundsHolder,
    val doubleTapState: DoubleTapState,
    val popupOnKeyPress: () -> Boolean,
    val swipeSymbolDirection: () -> SwipeSymbolDirection,
    val longPressDelay: () -> Int,
    val hapticOnRepeat: () -> Boolean,
    val sendAction: (KeyAction, KeyActionListener.Source) -> Unit,
    val sendPopup: (PopupAction) -> Unit,
    val onSwipeGesture: (ComposeKeyGestureEvent) -> Boolean,
) {

    fun action(action: KeyAction) = sendAction(action, KeyActionListener.Source.Keyboard)

    /** View: `GestureType.Down` 时 `PopupAction.PreviewAction`。 */
    fun showPreview() {
        val preview = spec.previewPopup ?: return
        if (!popupOnKeyPress()) return
        sendPopup(PopupAction.PreviewAction(keyId, preview.content, windowBounds.rect))
    }

    /** View: `AltPreview` 的 `GestureType.Move` → `PreviewUpdateAction`。 */
    fun updatePreview(totalY: Int) {
        val alt = spec.altPreviewPopup ?: return
        if (!popupOnKeyPress()) return
        val triggered = swipeSymbolDirection().checkY(totalY)
        sendPopup(
            PopupAction.PreviewUpdateAction(keyId, if (triggered) alt.alternative else alt.content)
        )
    }

    /** View: 预览弹层在 `GestureType.Up` 时消失。 */
    fun dismissPreview() {
        if (spec.previewPopup == null || !popupOnKeyPress()) return
        sendPopup(PopupAction.DismissAction(keyId))
    }

    /** View: popup Menu/Keyboard 的 `setOnLongClickListener`（返回 false，即不消费长按）。 */
    fun showLongPressPopup() {
        when (val popup = spec.longPressPopup) {
            is KeyDef.Popup.Menu ->
                sendPopup(PopupAction.ShowMenuAction(keyId, popup, windowBounds.rect))

            is KeyDef.Popup.Keyboard ->
                sendPopup(PopupAction.ShowKeyboardAction(keyId, popup, windowBounds.rect))

            else -> Unit
        }
    }

    /** View: `onPopupTrigger` —— 问弹层有没有待触发的 [KeyAction]，有则执行并关层。 */
    fun triggerLongPressPopup(): Boolean {
        if (spec.longPressPopup == null) return false
        val trigger = PopupAction.TriggerAction(keyId)
        sendPopup(trigger)
        val action = trigger.outAction ?: return false
        // 注意：弹层触发的动作 source 是 Popup（D5）
        sendAction(action, KeyActionListener.Source.Popup)
        sendPopup(PopupAction.DismissAction(keyId))
        return true
    }
}

private suspend fun AwaitPointerEventScope.runKeyGesture(env: KeyGestureEnv) {
    val down = awaitFirstDown(requireUnconsumed = false)
    // 只消费 DOWN/UP，**不消费 MOVE**：容器层的区域手势（数字行收起、spaceSwipeMoveCursor、
    // 浮动键盘拖动）走父级 `detectDragGestures`，一旦 MOVE 被键消费，父级的位移判定会被取消
    // （`awaitPointerSlopOrCancellation` 遇 isConsumed 即放弃）。这与 View 侧靠
    // `onInterceptTouchEvent` 抢夺的语义等价，见 KeyboardComposePlan §4.5-②。
    down.consume()
    val pointerId = down.id
    val keyWidth = size.width.toFloat()
    val keyHeight = size.height.toFloat()
    val touchSlop = viewConfiguration.touchSlop

    var movedOutside = false
    var longPressTriggered = false
    var repeatStarted = false
    var swipeRepeatTriggered = false
    var gestureConsumed = false

    val swipe = env.spec.swipe
    val swipeRepeatEnabled = swipe?.repeatEnabled == true
    val accumulator = swipe?.let {
        SwipeAccumulator(it.thresholdX.toPx(), it.thresholdY.toPx())
    }

    env.pressed.value = true
    env.interactionSource.tryEmit(PressInteraction.Press(down.position))
    InputFeedbacks.hapticFeedback(env.view)
    InputFeedbacks.soundEffect(env.spec.soundEffect)

    // 按下：预览弹层 + Down 手势回调（View 的顺序：先 haptic/sound，再 dispatch Down）
    env.showPreview()
    if (dispatchKeyGesture(env, ComposeKeyGestureEvent.Type.Down, down.position.x, down.position.y, 0, 0, 0, 0, gestureConsumed)) {
        gestureConsumed = true
    }
    accumulator?.start(down.position.x, down.position.y)

    var longPressJob: Job? = null
    var repeatJob: Job? = null
    if (env.spec.longPressPopup != null) {
        longPressJob = env.scope.launch {
            delay(env.longPressDelay().toLong())
            InputFeedbacks.hapticFeedback(env.view, longPress = true)
            // 不置 longPressTriggered：View 侧弹层的 long click listener 返回 false
            // （performLongClick() == false），不抑制松手时的 Press。
            env.showLongPressPopup()
        }
    } else if (env.spec.longPressAction != null) {
        val longPressAction = env.spec.longPressAction
        longPressJob = env.scope.launch {
            delay(env.longPressDelay().toLong())
            InputFeedbacks.hapticFeedback(env.view, longPress = true)
            longPressTriggered = true
            env.action(longPressAction)
        }
    }
    env.spec.repeatAction?.let { repeatAction ->
        repeatJob = env.scope.launch {
            delay(env.longPressDelay().toLong())
            repeatStarted = true
            while (isActive) {
                val startedAt = SystemClock.uptimeMillis()
                env.action(repeatAction)
                if (env.hapticOnRepeat()) InputFeedbacks.hapticFeedback(env.view)
                val wait = RepeatInterval - (SystemClock.uptimeMillis() - startedAt)
                if (wait > 0) delay(wait)
            }
        }
    }

    try {
        while (true) {
            val event = awaitPointerEvent()
            val change: PointerInputChange =
                event.changes.firstOrNull { it.id == pointerId } ?: break
            val position = change.position

            if (!change.pressed) {
                // ---- UP：与 CustomGestureView.ACTION_UP 同序 ----
                change.consume()
                InputFeedbacks.hapticFeedback(env.view, longPress = true, keyUp = true)
                // 注意顺序：View 侧弹层的监听器是「后注册者在外层 → 先执行」，弹层（Menu/Keyboard）
                // 的 onPopupTrigger 先于 Preview 的 DismissAction。若先 Dismiss 会 removeContainer，
                // 随后的 TriggerAction 拿不到待触发动作 → 只会上屏默认值或什么都不上屏。
                if (dispatchKeyGesture(
                        env, ComposeKeyGestureEvent.Type.Up,
                        position.x, position.y, 0, 0,
                        accumulator?.totalX ?: 0, accumulator?.totalY ?: 0,
                        gestureConsumed
                    )
                ) {
                    gestureConsumed = true
                }
                val shouldPerformClick = !(movedOutside || longPressTriggered ||
                        repeatStarted || swipeRepeatTriggered || gestureConsumed)
                if (shouldPerformClick) performClickOrDoubleTap(env)
                break
            }

            // ---- MOVE ----
            if (!movedOutside &&
                !isPointInKeyBounds(position.x, position.y, keyWidth, keyHeight, touchSlop)
            ) {
                movedOutside = true
                longPressJob?.cancel(); longPressJob = null
                repeatJob?.cancel(); repeatJob = null
                // View: `if (repeatStarted || !swipeEnabled) isPressed = false`
                // —— 滑行键（空格/退格）移出后仍保留按下态，其余键立即取消高亮
                if (repeatStarted || swipe == null) env.pressed.value = false
            }
            if (accumulator == null || longPressTriggered || repeatStarted) continue
            val countX = accumulator.consumeX(position.x)
            val countY = accumulator.consumeY(position.y)
            if (countX != 0 || countY != 0) {
                // 滑行一旦生效就抑制长按/重复（View: consumeSwipe 里 cancel 两个 job）
                if (swipeRepeatEnabled && !swipeRepeatTriggered) swipeRepeatTriggered = true
                longPressJob?.cancel(); longPressJob = null
                repeatJob?.cancel(); repeatJob = null
            }
            if (dispatchKeyGesture(
                    env, ComposeKeyGestureEvent.Type.Move,
                    position.x, position.y, countX, countY,
                    accumulator.totalX, accumulator.totalY, gestureConsumed
                )
            ) {
                gestureConsumed = true
            }
        }
    } catch (cancelled: CancellationException) {
        // pointerInput 被取消（布局切换 / 窗口失焦）：等同 ACTION_CANCEL
        env.doubleTapState.maybeDoubleTap = false
        env.doubleTapState.lastClickTime = 0L
        throw cancelled
    } finally {
        longPressJob?.cancel()
        repeatJob?.cancel()
        env.pressed.value = false
        env.dismissPreview()
        env.interactionSource.tryEmit(PressInteraction.Release(PressInteraction.Press(down.position)))
    }
}

/**
 * 手势事件分发链，顺序与 `BaseKeyboard.createKeyView` 注册 `onGestureListener` 的顺序一致
 * （后注册者在外层 → 先执行）：弹层 → 上下滑符号 → 键型专属（空格/退格）。
 *
 * @return 事件是否被消费（对应 View 的 `gestureConsumed`）
 */
private fun dispatchKeyGesture(
    env: KeyGestureEnv,
    type: ComposeKeyGestureEvent.Type,
    x: Float,
    y: Float,
    countX: Int,
    countY: Int,
    totalX: Int,
    totalY: Int,
    alreadyConsumed: Boolean,
): Boolean {
    var consumed = alreadyConsumed
    fun dispatchToListener(): Boolean = env.onSwipeGesture(
        ComposeKeyGestureEvent(type, consumed, x, y, countX, countY, totalX, totalY)
    )

    when (type) {
        ComposeKeyGestureEvent.Type.Down -> if (dispatchToListener()) consumed = true

        ComposeKeyGestureEvent.Type.Move -> {
            if (env.spec.longPressPopup != null) {
                // View: onPopupChangeFocus 的返回值也会置 gestureConsumed
                val focus = PopupAction.ChangeFocusAction(env.keyId, x, y)
                env.sendPopup(focus)
                if (focus.outResult) consumed = true
            }
            env.updatePreview(totalY)
            if (dispatchToListener()) consumed = true
        }

        ComposeKeyGestureEvent.Type.Up -> {
            // View 的 `event.consumed` 在事件构造时就固定：弹层 listener 在本轮里把
            // gestureConsumed 置真，也不会影响紧跟其后的滑行判定，故这里用入参快照。
            val consumedBeforeDispatch = alreadyConsumed
            if (env.triggerLongPressPopup()) consumed = true
            // 弹层触发之后再关预览气泡（View 的监听器链顺序），否则容器先被移除
            env.dismissPreview()
            if (!consumedBeforeDispatch && env.spec.swipeAction != null &&
                env.swipeSymbolDirection().checkY(totalY)
            ) {
                env.action(env.spec.swipeAction)
                consumed = true
            }
            if (dispatchToListener()) consumed = true
        }
    }
    return consumed
}

/** View: `ACTION_UP` 里的 `shouldPerformClick` 分支（含双击窗口 = `longPressDelay`）。 */
private fun performClickOrDoubleTap(env: KeyGestureEnv) {
    val pressAction = env.spec.pressAction
    val doubleTapAction = env.spec.doubleTapAction
    if (doubleTapAction == null) {
        pressAction?.let { env.action(it) }
        return
    }
    val state = env.doubleTapState
    val now = System.currentTimeMillis()
    if (state.maybeDoubleTap && now - state.lastClickTime <= env.longPressDelay()) {
        state.maybeDoubleTap = false
        env.action(doubleTapAction)
    } else {
        state.maybeDoubleTap = true
        pressAction?.let { env.action(it) }
    }
    state.lastClickTime = now
}
