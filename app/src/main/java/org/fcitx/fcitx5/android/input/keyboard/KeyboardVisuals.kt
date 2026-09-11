/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Border
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Variant

/**
 * 键盘外观的「主题桥」：把 fcitx View 侧的主题数据源（[Theme] + 主题外观偏好）翻译成
 * 与 View 无关的 Compose 值，供 Compose 按键消费（`Docs/KeyboardComposePlan.md` 批次 A）。
 *
 * **数据源不变**：只用 [Theme] 的颜色字段 + `ThemePrefs` 的边框/圆角/边距偏好，
 * 不切换 miuix `colorScheme` —— 否则会丢掉用户自定义主题（含背景图），
 * 见 `KeyboardComposePlan.md` §2.2。
 *
 * 抽取目的与 [KeyboardLayoutMath] 相同：让 View / Compose 两套实现读**同一份数字**，
 * 不随时间漂移；本文件是纯数据 + 纯函数（除 [rememberKeyboardVisuals] 外无 Compose 副作用），
 * 可直接单测。
 *
 * 与 View 侧 `KeyView` 的逐项对应：
 *
 * | 本文件 | View 出处 |
 * |---|---|
 * | [bordered] / [borderStroke] / [rippled] / [cornerRadius] | `KeyView` init（`ThemePrefs` 偏好） |
 * | [hMargin] / [vMargin] + [hMarginFor] / [vMarginFor] | `KeyView.hMargin/vMargin`（`def.margin` 为假时归零） |
 * | [backgroundColorFor] / [textColorFor] | `KeyView` 背景组装 + `TextKeyView` 文字色 |
 * | [hasBorder] / [borderWidth] | `KeyView` init 的 `bordered && def.border != Off \|\| def.border == On` |
 */
@Immutable
data class KeyboardVisuals(
    /** 键边框偏好（`ThemePrefs.keyBorder`）。关闭时键无边框也无阴影。 */
    val bordered: Boolean,
    /** 边框描边模式（`ThemePrefs.keyBorderStroke`）：true = 描边，false = 底部假阴影。 */
    val borderStroke: Boolean,
    /** 按下高亮用 ripple（`ThemePrefs.keyRippleEffect`）。 */
    val rippled: Boolean,
    /** 键圆角（`ThemePrefs.keyRadius`）。 */
    val cornerRadius: Dp,
    /** 键横向视觉内缩（`def.margin == true` 时生效）。 */
    val hMargin: Dp,
    /** 键纵向视觉内缩（`def.margin == true` 时生效）。 */
    val vMargin: Dp,
    val keyboardColor: Color,
    val keyBackgroundColor: Color,
    val keyTextColor: Color,
    val altKeyBackgroundColor: Color,
    val altKeyTextColor: Color,
    val accentKeyBackgroundColor: Color,
    val accentKeyTextColor: Color,
    /** 按下高亮色（View 侧是 ripple/StateListDrawable 的色源）。 */
    val keyPressHighlightColor: Color,
    /** 边框色 / 假阴影色。 */
    val keyShadowColor: Color,
    val spaceBarColor: Color,
    val dividerColor: Color,
) {

    /** 键底色，对应 `KeyView` 组装 `bkgColor` 的 `when (def.variant)`。 */
    fun backgroundColorFor(variant: Variant): Color = when (variant) {
        Variant.Normal, Variant.AltForeground -> keyBackgroundColor
        Variant.Alternative -> altKeyBackgroundColor
        Variant.Accent -> accentKeyBackgroundColor
    }

    /** 键面文字/图标色，对应 `TextKeyView.setTextColor` / `ImageView.configure`。 */
    fun textColorFor(variant: Variant): Color = when (variant) {
        Variant.Normal -> keyTextColor
        Variant.AltForeground, Variant.Alternative -> altKeyTextColor
        Variant.Accent -> accentKeyTextColor
    }

    /**
     * 该键是否画边框/阴影，对应 `KeyView` init 的判定：
     * `(bordered && def.border != Off) || def.border == On`。
     * [Border.Special]（空格条/回车键）仍走普通边框判定，特殊底另画。
     */
    fun hasBorder(border: Border): Boolean =
        (bordered && border != Border.Off) || border == Border.On

    /** 描边宽度；[borderStroke] 为 false 时返回 null（改用底部假阴影，见 §4.5-⑥）。 */
    val borderWidth: Dp?
        get() = if (bordered && borderStroke) 1.dp else null

    /** [KeyDef.Appearance.margin] 为假时视觉边距归零（与 `KeyView.init` 一致）。 */
    fun hMarginFor(margin: Boolean): Dp = if (margin) hMargin else 0.dp

    /** 见 [hMarginFor]。 */
    fun vMarginFor(margin: Boolean): Dp = if (margin) vMargin else 0.dp
}

/**
 * 纯函数版本的主题桥，便于单测：[rememberKeyboardVisuals] 与（若需要的）预览工具共用。
 *
 * 纵向/横向边距按屏幕方向二选一，与 `KeyView.init` 一致。传入原语而非 `ThemePrefs`，
 * 避免本函数依赖 Android 的 `SharedPreferences`。
 */
fun buildKeyboardVisuals(
    theme: Theme,
    landscape: Boolean,
    bordered: Boolean,
    borderStroke: Boolean,
    rippled: Boolean,
    keyRadius: Int,
    keyHorizontalMargin: Int,
    keyHorizontalMarginLandscape: Int,
    keyVerticalMargin: Int,
    keyVerticalMarginLandscape: Int,
): KeyboardVisuals = KeyboardVisuals(
    bordered = bordered,
    borderStroke = borderStroke,
    rippled = rippled,
    cornerRadius = keyRadius.dp,
    hMargin = (if (landscape) keyHorizontalMarginLandscape else keyHorizontalMargin).dp,
    vMargin = (if (landscape) keyVerticalMarginLandscape else keyVerticalMargin).dp,
    keyboardColor = Color(theme.keyboardColor),
    keyBackgroundColor = Color(theme.keyBackgroundColor),
    keyTextColor = Color(theme.keyTextColor),
    altKeyBackgroundColor = Color(theme.altKeyBackgroundColor),
    altKeyTextColor = Color(theme.altKeyTextColor),
    accentKeyBackgroundColor = Color(theme.accentKeyBackgroundColor),
    accentKeyTextColor = Color(theme.accentKeyTextColor),
    keyPressHighlightColor = Color(theme.keyPressHighlightColor),
    keyShadowColor = Color(theme.keyShadowColor),
    spaceBarColor = Color(theme.spaceBarColor),
    dividerColor = Color(theme.dividerColor),
)

/**
 * 读取当前主题与主题外观偏好，产出 [KeyboardVisuals]。
 *
 * - **主题是响应式的**：监听 [ThemeManager.addOnChangedListener]，换主题后自动重组；
 * - **偏好不是响应式的**：与 `LongPressDelayProvider` / `repeatableClick` 同一口径
 *   （`ManagedPreference.getValue()` 是普通读取），改边框/圆角/边距偏好需窗口重建才生效
 *   —— 与 View 侧 `KeyView` 在构造期读一次的行为一致。
 */
@Composable
fun rememberKeyboardVisuals(): KeyboardVisuals {
    val landscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var theme by remember { mutableStateOf(ThemeManager.activeTheme) }
    DisposableEffect(Unit) {
        val listener = ThemeManager.OnThemeChangeListener { theme = it }
        ThemeManager.addOnChangedListener(listener)
        onDispose { ThemeManager.removeOnChangedListener(listener) }
    }
    return remember(theme, landscape) {
        val prefs = ThemeManager.prefs
        buildKeyboardVisuals(
            theme = theme,
            landscape = landscape,
            bordered = prefs.keyBorder.getValue(),
            borderStroke = prefs.keyBorderStroke.getValue(),
            rippled = prefs.keyRippleEffect.getValue(),
            keyRadius = prefs.keyRadius.getValue(),
            keyHorizontalMargin = prefs.keyHorizontalMargin.getValue(),
            keyHorizontalMarginLandscape = prefs.keyHorizontalMarginLandscape.getValue(),
            keyVerticalMargin = prefs.keyVerticalMargin.getValue(),
            keyVerticalMarginLandscape = prefs.keyVerticalMarginLandscape.getValue(),
        )
    }
}
