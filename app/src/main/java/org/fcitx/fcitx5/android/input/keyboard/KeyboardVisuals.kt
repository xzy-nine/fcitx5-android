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
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
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
    /** IME 主背景色（`Theme.backgroundColor`）：符号滑块等「非按键底」用它。 */
    val backgroundColor: Color,
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

    /** 键的默认视觉内缩（对称），对应 `KeyView` 里 `hMargin/vMargin` 的 drawable inset。 */
    fun defaultInsets(margin: Boolean): KeyInsets =
        KeyInsets.symmetric(hMarginFor(margin), vMarginFor(margin))

    /**
     * 是否为「特殊形状键」（空格条 / 回车键）。
     *
     * `KeyView.onSizeChanged` 一开头就是 `if (bordered) return` —— 也就是说这两个键的特殊底
     * **只在 `keyBorder` 关闭时**生效；开启时它们退回普通键底。这条容易漏，务必按此判。
     */
    fun usesSpecialKeyShape(def: KeyDef): Boolean =
        !bordered && (def.appearance.viewId == R.id.button_space ||
                def.appearance.viewId == R.id.button_return)

    /** 空格条 / 回车键的形态参数（对应 `KeyView.onSizeChanged` 的偏移算式）。 */
    companion object {
        /** 空格条圆角（`KeyView.onSizeChanged` 里固定 `dp(3f)`）。 */
        val SpaceBarCornerRadius = 3.dp
        /** 空格条横向内缩固定 `dp(10)`。 */
        val SpaceBarHorizontalInset = 10.dp
        /** 空格条最小高度 `dp(26)`：低于它不再纵向内缩。 */
        val SpaceBarMinHeight = 26.dp
        /** 空格条纵向内缩上限 `dp(16)`。 */
        val SpaceBarMaxVerticalInset = 16.dp
        /** 回车键圆形直径上限 `dp(35)`。 */
        val ReturnKeyMaxDiameter = 35.dp
    }
}

/**
 * 键的视觉内缩（对应 `KeyView` 里以 drawable inset 实现的边距）。
 *
 * [start] / [end] 是**非对称**的：`expandKeypressArea` 让首尾键的触摸区变宽、内容只往一
 * 侧内缩（View 侧 `layoutMarginLeft/Right`）。
 */
@Immutable
data class KeyInsets(
    val start: Dp = 0.dp,
    val end: Dp = 0.dp,
    val top: Dp = 0.dp,
    val bottom: Dp = 0.dp,
) {
    fun plusStart(extra: Dp) = copy(start = start + extra)
    fun plusEnd(extra: Dp) = copy(end = end + extra)

    companion object {
        val Zero = KeyInsets()
        fun symmetric(horizontal: Dp, vertical: Dp) =
            KeyInsets(horizontal, horizontal, vertical, vertical)
    }
}

/**
 * 把 [ManagedPreference] 包成 Compose 状态：注册 `OnChangeListener`，偏好变化即重组。
 *
 * 只给「View 侧原本就注册监听、不重建即时生效」的那几项用；其余偏好仍是「构造期读一次、
 * 改偏好靠窗口重建」的口径（与 `LongPressDelayProvider` 一致）。
 */
@Composable
fun <T : Any> ManagedPreference<T>.preferenceState(): T {
    var value by remember(this) { mutableStateOf(getValue()) }
    DisposableEffect(this) {
        val listener = ManagedPreference.OnChangeListener<T> { _, newValue -> value = newValue }
        registerOnChangeListener(listener)
        onDispose { unregisterOnChangeListener(listener) }
    }
    return value
}

/**
 * 观察当前 fcitx 主题（换主题即重组）。凡是需要**原始 [Theme] 对象**而非
 * [KeyboardVisuals] 的场景（如要构造 View 侧的 `RecentSymbolsView`）用它。
 */
@Composable
fun rememberActiveTheme(): Theme {
    var theme by remember { mutableStateOf(ThemeManager.activeTheme) }
    DisposableEffect(Unit) {
        val listener = ThemeManager.OnThemeChangeListener { theme = it }
        ThemeManager.addOnChangedListener(listener)
        onDispose { ThemeManager.removeOnChangedListener(listener) }
    }
    return theme
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
    backgroundColor = Color(theme.backgroundColor),
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
    val theme = rememberActiveTheme()
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
