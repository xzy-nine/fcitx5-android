/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.update.MirrorRule
import org.fcitx.fcitx5.android.ui.main.update.UpdatePrefs
import org.fcitx.fcitx5.android.ui.main.update.UpdateRepository
import org.fcitx.fcitx5.android.utils.toast
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 编辑单条镜像规则。
 *
 * 保存前实际执行一次替换：只编译正则无法发现非法的分组引用或多余的转义，
 * 那类错误会在下载时才暴露。
 */
@Composable
fun UpdateMirrorEditScreen(
    mirrorId: String?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val existing = remember(mirrorId) {
        mirrorId?.let { id -> UpdatePrefs.loadMirrorRules(context).firstOrNull { it.id == id } }
    }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var pattern by remember { mutableStateOf(existing?.pattern.orEmpty()) }
    var replacement by remember { mutableStateOf(existing?.replacement.orEmpty()) }

    fun save() {
        val trimmedName = name.trim()
        val trimmedPattern = pattern.trim()
        val trimmedReplacement = replacement.trim()
        if (trimmedName.isEmpty() || trimmedPattern.isEmpty() || trimmedReplacement.isEmpty()) {
            context.toast(R.string.update_mirror_fields_required)
            return
        }
        when (UpdateRepository.validateMirrorRule(trimmedPattern, trimmedReplacement)) {
            UpdateRepository.MirrorRuleValidation.PATTERN_INVALID -> {
                context.toast(R.string.update_mirror_pattern_invalid)
                return
            }
            UpdateRepository.MirrorRuleValidation.REPLACEMENT_INVALID -> {
                context.toast(R.string.update_mirror_replacement_invalid)
                return
            }
            UpdateRepository.MirrorRuleValidation.VALID -> Unit
        }
        val list = UpdatePrefs.loadMirrorRules(context).toMutableList()
        if (mirrorId == null) {
            list += UpdatePrefs.newMirrorRule(trimmedName, trimmedPattern, trimmedReplacement)
        } else {
            val index = list.indexOfFirst { it.id == mirrorId }
            val rule = MirrorRule(mirrorId, trimmedName, trimmedPattern, trimmedReplacement)
            if (index >= 0) list[index] = rule else list += rule
        }
        UpdatePrefs.saveMirrorRules(context, list)
        onBack()
    }

    fun delete() {
        val id = mirrorId ?: return
        val list = UpdatePrefs.loadMirrorRules(context).toMutableList()
        if (list.removeAll { it.id == id }) {
            UpdatePrefs.saveMirrorRules(context, list)
            if (UpdatePrefs.loadSelectedMirrorId(context) == id) {
                UpdatePrefs.saveSelectedMirrorId(context, null)
            }
        }
        onBack()
    }

    PageScaffold(title = stringResource(R.string.update_mirror_edit_title), onBack = onBack) {
        item {
            Card(
                modifier = Modifier.padding(12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.update_mirror_name_label),
                )
                TextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = stringResource(R.string.update_mirror_pattern_label),
                )
                TextField(
                    value = replacement,
                    onValueChange = { replacement = it },
                    label = stringResource(R.string.update_mirror_replace_label),
                )
                ArrowPreference(
                    title = stringResource(R.string.save),
                    onClick = { save() },
                )
                if (mirrorId != null) {
                    ArrowPreference(
                        title = stringResource(R.string.delete),
                        onClick = { delete() },
                    )
                }
            }
        }
    }
}