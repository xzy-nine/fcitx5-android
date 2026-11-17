/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber
import top.yukonga.miuix.kmp.preference.CheckboxPreference

/**
 * 通用多选列表页（如「启用的输入方案」）。
 *
 * 数据协议与引擎一致：子配置里 [option] 是一段以换行分隔的行，
 * 每行三列 `选中状态\t标识\t显示名`。修改后整段写回。
 */
@Composable
fun MultiSelectScreen(
    title: String,
    addon: String,
    path: String,
    option: String,
    min: Int,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fcitx = remember { FcitxDaemon.connect("compose-multiselect") }

    var items by remember { mutableStateOf<List<MultiSelectItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var dirty by remember { mutableStateOf(false) }

    // 保存必须在断开连接之前完成：先写回，再释放连接。
    fun persist() {
        if (!dirty) return
        val selected = items.count { it.selected }
        if (selected < min) {
            context.toast(context.getString(R.string.generic_multiselect_min, min))
            return
        }
        val snapshot = items
        dirty = false
        // 可能已在离开页面的过程中，写入需脱离组合作用域完成。
        MainScope().launch(NonCancellable + Dispatchers.IO) {
            runCatching {
                fcitx.runOnReady {
                    setAddonSubConfig(
                        addon,
                        path,
                        RawConfig(arrayOf(RawConfig(option, encodeMultiSelectItems(snapshot)))),
                    )
                }
            }.onFailure { Timber.w(it, "多选列表保存失败") }
        }
    }

    // 进入页面时读取一次；离开时若改动过则写回。
    androidx.compose.runtime.LaunchedEffect(addon, path, option) {
        runCatching {
            withContext(Dispatchers.IO) {
                fcitx.runOnReady {
                    val raw = getAddonSubConfig(addon, path)
                    val cfg = raw.findByName("cfg") ?: raw
                    parseMultiSelectItems(cfg.findByName(option)?.value.orEmpty())
                }
            }
        }.onSuccess { items = it }
            .onFailure { Timber.w(it, "多选列表读取失败") }
        loading = false
    }

    DisposableEffect(Unit) {
        onDispose {
            persist()
            FcitxDaemon.disconnect("compose-multiselect")
        }
    }

    PageScaffold(
        title = title,
        onBack = {
            // 先写回，再由页面移除触发 onDispose 释放连接。
            persist()
            onBack()
        },
    ) {
        if (loading) {
            item {
                Text(
                    text = stringResource(R.string.loading),
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else if (items.isEmpty()) {
            // 无条目时不显示占位文案：引擎尚未提供可选项，页面保持空列表即可。
        } else {
            items(items) { item ->
                val index = items.indexOf(item)
                CheckboxPreference(
                    title = item.label,
                    checked = item.selected,
                    onCheckedChange = { checked ->
                        items = items.toMutableList().also { list ->
                            list[index] = item.copy(selected = checked)
                        }
                        dirty = true
                    },
                )
            }
        }
    }
}

/** 多选列表的一项。 */
data class MultiSelectItem(val selected: Boolean, val id: String, val label: String)

/**
 * 解析引擎返回的多选载荷。
 *
 * 每行三列：选中标记、标识、显示名；列数不足的行忽略。
 */
internal fun parseMultiSelectItems(text: String): List<MultiSelectItem> {
    if (text.isBlank()) return emptyList()
    return text.lineSequence().mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val parts = line.split('\t', limit = 3)
        if (parts.size < 3) return@mapNotNull null
        MultiSelectItem(
            selected = parts[0] == "1",
            id = parts[1],
            label = sanitizeMultiSelectLabel(parts[2]),
        )
    }.toList()
}

/** 写回多选载荷，与 [parseMultiSelectItems] 互逆。 */
internal fun encodeMultiSelectItems(items: List<MultiSelectItem>): String =
    items.joinToString("\n") { "${if (it.selected) "1" else "0"}\t${it.id}\t${it.label}" }

/** 去掉会破坏行/列结构的字符。 */
private fun sanitizeMultiSelectLabel(raw: String): String {
    val cleaned = raw.replace('\t', ' ').replace('|', ' ').trim()
    return cleaned.ifEmpty { raw.trim() }
}
