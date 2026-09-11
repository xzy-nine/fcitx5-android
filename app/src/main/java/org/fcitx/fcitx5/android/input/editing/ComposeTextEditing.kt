/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.editing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.bar.inputFeedback
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface
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
 * 复刻原 constraint 网格：左侧 2 列 3 行的方向键 +
 * 下方 起始/结尾，右侧 30% 高的 全选/剪切(条件)/复制/粘贴/退格 动作列。
 */
@Composable
fun TextEditingContent(
    hasSelection: Boolean,
    selectActivated: Boolean,
    callbacks: TextEditingCallbacks,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxSize()) {
        val dirColor = MiuixTheme.colorScheme.secondaryContainer
        val actionColor = MiuixTheme.colorScheme.primary
        Column(
            modifier = Modifier
                .weight(7f)
                .fillMaxHeight()
                .padding(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                DirButton(Modifier.weight(1f), dirColor, R.drawable.ic_baseline_keyboard_arrow_left_24, callbacks.onLeft)
                DirButton(Modifier.weight(1f), dirColor, R.drawable.ic_baseline_keyboard_arrow_up_24, callbacks.onUp)
                DirButton(Modifier.weight(1f), dirColor, R.drawable.ic_baseline_keyboard_arrow_right_24, callbacks.onRight)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Box(Modifier.weight(1f))
                SelectButton(
                    Modifier.weight(1f),
                    selectActivated,
                    callbacks.onSelect,
                )
                Box(Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Box(Modifier.weight(1f))
                DirButton(Modifier.weight(1f), dirColor, R.drawable.ic_baseline_keyboard_arrow_down_24, callbacks.onDown)
                Box(Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                DirButton(Modifier.weight(1f), dirColor, R.drawable.ic_baseline_first_page_24, callbacks.onHome)
                Box(Modifier.weight(1f))
                DirButton(Modifier.weight(1f), dirColor, R.drawable.ic_baseline_last_page_24, callbacks.onEnd)
            }
        }
        Column(
            modifier = Modifier
                .weight(3f)
                .fillMaxHeight()
                .padding(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ActionButton(
                Modifier.weight(1f),
                actionColor,
                if (hasSelection) "剪切" else "全选",
                onClick = if (hasSelection) callbacks.onCut else callbacks.onSelectAll,
            )
            ActionButton(Modifier.weight(1f), actionColor, "复制", onClick = callbacks.onCopy)
            ActionButton(Modifier.weight(1f), actionColor, "粘贴", onClick = callbacks.onPaste)
            ActionButton(Modifier.weight(1f), actionColor, "退格", onClick = callbacks.onBackspace)
        }
    }
}

@Composable
private fun DirButton(
    modifier: Modifier,
    color: Color,
    iconRes: Int,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .height(48.dp)
            .inputFeedback(),
        shape = RoundedCornerShape(10.dp),
        color = color,
        contentColor = MiuixTheme.colorScheme.onSecondaryContainer,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

@Composable
private fun SelectButton(
    modifier: Modifier,
    activated: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .height(48.dp)
            .inputFeedback(),
        shape = RoundedCornerShape(10.dp),
        color = if (activated) MiuixTheme.colorScheme.primary
        else MiuixTheme.colorScheme.secondaryContainer,
        contentColor = if (activated) MiuixTheme.colorScheme.onPrimary
        else MiuixTheme.colorScheme.onSecondaryContainer,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "选区",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun ActionButton(
    modifier: Modifier,
    color: Color,
    text: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .height(48.dp)
            .inputFeedback(),
        shape = RoundedCornerShape(10.dp),
        color = color,
        contentColor = MiuixTheme.colorScheme.onPrimary,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}