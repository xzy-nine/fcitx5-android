/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.editing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.bar.inputFeedback
import org.fcitx.fcitx5.android.input.bar.repeatableClick
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 文本编辑页按钮回调 */
data class TextEditingCallbacks(
    val onUp: () -> Unit,
    val onDown: () -> Unit,
    val onLeft: () -> Unit,
    val onRight: () -> Unit,
    val onHome: () -> Unit,
    val onEnd: () -> Unit,
    val onSelect: () -> Unit,
    val onSelectAll: () -> Unit,
    val onCut: () -> Unit,
    val onCopy: () -> Unit,
    val onPaste: () -> Unit,
    val onBackspace: () -> Unit,
    val onOpenClipboard: () -> Unit,
)

/**
 * 文本编辑页 Compose 渲染。
 *
 * 复刻 XML 参考布局的四列网格（权重 1 : 1.2 : 1 : 1.2）：
 * - 列1：左方向键（垂直撑满高度）
 * - 列2：上 / 选区 / 下（垂直等分）
 * - 列3：右方向键（垂直撑满高度）
 * - 列4：全选 / 复制 / 粘贴（垂直等分）
 * 底部操作行权重 1.6 : 1.6 : 1.2（|&lt; 与 &gt;| 等宽、⌫ 对齐列4）。
 * 主区域 : 底部 = 3 : 1（对齐原视图 4 等分行：上/选区/下/底部各占 1/4），
 * 高度全部按比例分配，适配可调键盘高度。
 * 按钮使用 miuix 的 [IconButton] 与 [Button]，方向键为图标钮、文字动作为文本钮。
 */
@Composable
fun TextEditingContent(
    hasSelection: Boolean,
    selectActivated: Boolean,
    hapticOnRepeat: Boolean,
    callbacks: TextEditingCallbacks,
    modifier: Modifier = Modifier,
) {
    val gap = 4.dp
    // 方向键按钮用高对比容器色，避免与页面背景（background）色差过小
    val dirColor = MiuixTheme.colorScheme.surfaceContainerHighest
    Column(modifier = modifier
        .fillMaxSize()
        .padding(2.dp)) {
        Row(
            modifier = Modifier
                .weight(3f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            // 列1：左方向键
            DirIconButton(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                dirColor,
                R.drawable.ic_baseline_keyboard_arrow_left_24,
                hapticOnRepeat,
                callbacks.onLeft,
            )
            // 列2：上 / 选区 / 下
            Column(
                modifier = Modifier
                    .weight(1.2f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                DirIconButton(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    dirColor,
                    R.drawable.ic_baseline_keyboard_arrow_up_24,
                    hapticOnRepeat,
                    callbacks.onUp,
                )
                SelectButton(Modifier
                    .weight(1f)
                    .fillMaxWidth(), selectActivated, callbacks.onSelect)
                DirIconButton(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    dirColor,
                    R.drawable.ic_baseline_keyboard_arrow_down_24,
                    hapticOnRepeat,
                    callbacks.onDown,
                )
            }
            // 列3：右方向键
            DirIconButton(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                dirColor,
                R.drawable.ic_baseline_keyboard_arrow_right_24,
                hapticOnRepeat,
                callbacks.onRight,
            )
            // 列4：全选 / 复制 / 粘贴
            Column(
                modifier = Modifier
                    .weight(1.2f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                ActionButton(Modifier
                    .weight(1f)
                    .fillMaxWidth(), stringResource(R.string.select_all), onClick = callbacks.onSelectAll)
                ActionButton(Modifier
                    .weight(1f)
                    .fillMaxWidth(), stringResource(R.string.copy), onClick = callbacks.onCopy)
                ActionButton(Modifier
                    .weight(1f)
                    .fillMaxWidth(), stringResource(R.string.paste), onClick = callbacks.onPaste)
            }
        }
        Spacer(modifier = Modifier.height(gap))
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            // 行首 |< 与行尾 >| 等宽，各占左侧三列一半；退格 ⌫ 对齐列4
            DirIconButton(
                Modifier
                    .weight(1.6f)
                    .fillMaxHeight(),
                dirColor,
                R.drawable.ic_baseline_first_page_24,
                hapticOnRepeat,
                callbacks.onHome,
            )
            DirIconButton(
                Modifier
                    .weight(1.6f)
                    .fillMaxHeight(),
                dirColor,
                R.drawable.ic_baseline_last_page_24,
                hapticOnRepeat,
                callbacks.onEnd,
            )
            ActionButton(Modifier
                .weight(1.2f)
                .fillMaxHeight(), "⌫", repeatable = true, hapticOnRepeat = hapticOnRepeat, onClick = callbacks.onBackspace)
        }
    }
}

/**
 * 方向键按钮：长按连续触发（复刻旧 `CustomGestureView(repeatEnabled = true)`）。
 *
 * 单击与重复均由 [repeatableClick] 统一派发，因此 miuix 按钮自身的 `onClick` 传空实现，
 * 避免短按被派发两次。
 */
@Composable
private fun DirIconButton(
    modifier: Modifier,
    color: Color,
    iconRes: Int,
    hapticOnRepeat: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = {},
        modifier = modifier.repeatableClick(hapticOnRepeat = hapticOnRepeat, onClick = onClick),
        backgroundColor = color,
        cornerRadius = 10.dp,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.size(28.dp),
        )
    }
}

@Composable
private fun SelectButton(
    modifier: Modifier,
    activated: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.inputFeedback(),
        colors = if (activated) ButtonDefaults.buttonColorsPrimary()
        else ButtonDefaults.buttonColors(),
        cornerRadius = 10.dp,
    ) {
        Text(text = stringResource(R.string.selection), fontSize = 14.sp)
    }
}

/**
 * 文本动作按钮（全选/复制/粘贴/退格）。
 *
 * 仅退格（[repeatable] = true）走 [repeatableClick] 支持长按连续删除；全选/复制/粘贴
 * 为一次性动作，用普通点击即可，避免长按误触连续触发。
 */
@Composable
private fun ActionButton(
    modifier: Modifier,
    text: String,
    repeatable: Boolean = false,
    hapticOnRepeat: Boolean = false,
    onClick: () -> Unit,
) {
    val clickModifier = if (repeatable) {
        Modifier.repeatableClick(hapticOnRepeat = hapticOnRepeat, onClick = onClick)
    } else {
        Modifier.inputFeedback().clickable(onClick = onClick)
    }
    Button(
        onClick = {},
        modifier = modifier.then(clickModifier),
        colors = ButtonDefaults.buttonColorsPrimary(),
        cornerRadius = 10.dp,
    ) {
        Text(text = text, fontSize = 14.sp)
    }
}