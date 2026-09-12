/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.expanded

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.keyboard.BackspaceKey
import org.fcitx.fcitx5.android.input.keyboard.ComposeKey
import org.fcitx.fcitx5.android.input.keyboard.ImageLayoutSwitchKey
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.ReturnKey
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.popup.PopupActionListener

/**
 * 展开候选页右侧内嵌键盘：竖排 4 个等高等宽键（上翻 / 下翻 / 退格 / 回车）。
 *
 * 键面由 `keyboard/KeyDefPreset.kt` 现成的预设复刻 View 侧 `ExpandedCandidateLayout.Keyboard.Layout`：
 * 上/下翻 = `ImageLayoutSwitchKey(to="U"/"D", variant=Alternative)`，退格 = `BackspaceKey()`，
 * 回车 = `ReturnKey()`（长按弹 emoji 小键盘）。翻页键的 enabled 由网格滚动位置决定
 * （到顶 / 到底置灰），按键行为全部经 [keyActionListener] 路由：U/D 走翻页，其余转发主按键监听。
 *
 * 背景底色交给父级统一处理，这里不绘制。
 */
@Composable
fun ComposeExpandedCandidateKeyboard(
    returnDrawable: Int,
    pageUpEnabled: Boolean,
    pageDownEnabled: Boolean,
    keyActionListener: KeyActionListener?,
    popupActionListener: PopupActionListener?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        ComposeKey(
            def = ImageLayoutSwitchKey(
                R.drawable.ic_baseline_arrow_upward_24,
                to = ExpandedCandidateKeyboard.PageUp,
                variant = KeyDef.Appearance.Variant.Alternative,
            ),
            keyId = 0,
            modifier = Modifier.fillMaxSize().weight(1f),
            keyActionListener = keyActionListener,
            popupActionListener = popupActionListener,
            enabled = pageUpEnabled,
        )
        ComposeKey(
            def = ImageLayoutSwitchKey(
                R.drawable.ic_baseline_arrow_downward_24,
                to = ExpandedCandidateKeyboard.PageDown,
                variant = KeyDef.Appearance.Variant.Alternative,
            ),
            keyId = 1,
            modifier = Modifier.fillMaxSize().weight(1f),
            keyActionListener = keyActionListener,
            popupActionListener = popupActionListener,
            enabled = pageDownEnabled,
        )
        ComposeKey(
            def = BackspaceKey(),
            keyId = 2,
            modifier = Modifier.fillMaxSize().weight(1f),
            keyActionListener = keyActionListener,
            popupActionListener = popupActionListener,
        )
        ComposeKey(
            def = returnKeyWithDrawable(returnDrawable),
            keyId = 3,
            modifier = Modifier.fillMaxSize().weight(1f),
            keyActionListener = keyActionListener,
            popupActionListener = popupActionListener,
        )
    }
}

/**
 * 回车键图标随当前编辑框类型变化（与 `KeyboardWindow` / `TextKeyboardState` 同口径）：
 * `ReturnKey()` 预设图标在 `onReturnDrawableUpdate` 里被换成对应 drawable。
 */
private fun returnKeyWithDrawable(drawable: Int): KeyDef {
    val base = ReturnKey()
    if (drawable == 0) return base
    val image = base.appearance as? KeyDef.Appearance.Image ?: return base
    return KeyDef(
        KeyDef.Appearance.Image(
            src = drawable,
            percentWidth = image.percentWidth,
            variant = image.variant,
            viewId = image.viewId,
        ),
        base.behaviors,
        base.popup,
    )
}

/**
 * 上翻 / 下翻键的 `LayoutSwitchAction` 目标，与 [ComposeExpandedCandidateWindow] 的路由约定一致。
 */
object ExpandedCandidateKeyboard {
    const val PageUp = "U"
    const val PageDown = "D"
}
