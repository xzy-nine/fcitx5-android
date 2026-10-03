/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 手写输入设置页（独立输入方案）。
 *
 * 结构对齐 [VoiceInputSettingsScreen]：开关 + 边写边上屏 + **识别引擎下拉** +
 * 谷歌数字墨水模型（状态 / 手动下载，中文已随包内置）+ 触控笔手势演示入口。
 * 引擎本身在 IME 覆盖层面板里使用，本页不提供画布。
 */
package org.fcitx.fcitx5.android.ui.main.compose.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.handwriting.DigitalInkModelCatalog
import org.fcitx.fcitx5.android.data.handwriting.GoogleDigitalInkEngine
import org.fcitx.fcitx5.android.data.handwriting.HandwritingEngineKind
import org.fcitx.fcitx5.android.data.handwriting.XiaomiHandwritingEngine
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 谷歌数字墨水模型在设置页的展示态。 */
private enum class GoogleModelState {
    /** 正在探测（首帧）。 */
    Checking,

    /** 当前语言没有数字墨水模型。 */
    Unsupported,

    /** 已下载。 */
    Downloaded,

    /** 未下载（去模型市场下载）。 */
    NotDownloaded,
}

@Composable
fun HandwritingSettingsScreen(
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenGestureDemo: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = AppPrefs.getInstance().handwriting

    // ManagedPreference 不是 Compose State：用版本号驱动重组（与语音设置页同一做法）
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val listener = object : ManagedPreferenceProvider.OnChangeListener {
            override fun onChange(key: String) {
                version += 1
            }
        }
        prefs.registerOnChangeListener(listener)
        onDispose { prefs.unregisterOnChangeListener(listener) }
    }

    val enabled = remember(version) { prefs.handwritingInputEnabled.getValue() }
    val autoCommit = remember(version) { prefs.handwritingAutoCommit.getValue() }
    val stylusToolbox = remember(version) { prefs.stylusToolboxEnabled.getValue() }
    val engine = remember(version) { prefs.handwritingEngine.getValue() }

    // 系统手写引擎能力探测（读系统设置 + 探测引擎 jar；纯本地、无 IO）
    // 分开探测文字识别与手势：两者可能只装了一个
    val systemTextOk = remember(version) { XiaomiHandwritingEngine.isTextRecognitionAvailable(context) }
    val systemGestureOk = remember(version) { XiaomiHandwritingEngine.isGestureAvailable(context) }
    val systemEngineAvailable = systemTextOk || systemGestureOk

    // 引擎下拉：条目 = 声明顺序（系统内置 → 谷歌数字墨水），选中项优先、其后回落
    val engines = HandwritingEngineKind.entries
    val engineIndex = engines.indexOf(engine).coerceAtLeast(0)
    val engineSummary = stringResource(R.string.handwriting_engine_summary) + "\n" +
            if (systemEngineAvailable) {
                stringResource(
                    R.string.handwriting_system_engine_capabilities,
                    if (systemTextOk) "✓" else "✗",
                    if (systemGestureOk) "✓" else "✗",
                )
            } else {
                stringResource(R.string.handwriting_system_engine_unavailable)
            }

    // 谷歌数字墨水：语言 tag 来自「模型市场里选中的语言」，未选时跟随应用/系统语言；
    // 模型下载 / 删除 / 切换全部在模型市场 `digitalink` 分类里完成，本页只回显 + 跳转
    val inkTag = remember(version) { GoogleDigitalInkEngine.languageTag(context) }
    val inkName = remember(inkTag) { DigitalInkModelCatalog.nameOf(inkTag) }
    var inkState by remember { mutableStateOf(GoogleModelState.Checking) }
    // 市场里选中语言 / 下载完成都会写偏好 → `version` 递增 → 这里重查状态
    LaunchedEffect(version, inkTag) {
        inkState = when {
            !GoogleDigitalInkEngine.isLanguageSupported(context) -> GoogleModelState.Unsupported
            GoogleDigitalInkEngine.isModelDownloaded(context, inkTag) -> GoogleModelState.Downloaded
            else -> GoogleModelState.NotDownloaded
        }
    }

    val inkSummary = when (inkState) {
        GoogleModelState.Checking -> stringResource(R.string.digital_ink_checking)
        GoogleModelState.Downloaded -> stringResource(R.string.digital_ink_ready)
        GoogleModelState.NotDownloaded ->
            stringResource(R.string.digital_ink_not_downloaded, inkTag)

        GoogleModelState.Unsupported ->
            stringResource(R.string.digital_ink_unsupported, inkTag)
    }

    PageScaffold(
        title = stringResource(R.string.handwriting_input),
        onBack = onBack,
        contentBottomPadding = 24.dp,
    ) {
        item { SmallTitle(text = stringResource(R.string.group_handwriting)) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.surfaceContainerHighest,
                ),
            ) {
                SwitchPreference(
                    title = stringResource(R.string.handwriting_input_enabled),
                    summary = stringResource(R.string.handwriting_input_enabled_summary),
                    checked = enabled,
                    onCheckedChange = { prefs.handwritingInputEnabled.setValue(it) },
                )
                SwitchPreference(
                    title = stringResource(R.string.handwriting_auto_commit),
                    summary = stringResource(R.string.handwriting_auto_commit_summary),
                    checked = autoCommit,
                    enabled = enabled,
                    onCheckedChange = { prefs.handwritingAutoCommit.setValue(it) },
                )
                SwitchPreference(
                    title = stringResource(R.string.handwriting_stylus_toolbox),
                    summary = stringResource(R.string.handwriting_stylus_toolbox_summary),
                    checked = stylusToolbox,
                    enabled = enabled,
                    onCheckedChange = { prefs.stylusToolboxEnabled.setValue(it) },
                )
                WindowDropdownPreference(
                    items = engines.map { stringResource(it.stringRes) },
                    selectedIndex = engineIndex,
                    title = stringResource(R.string.handwriting_engine),
                    summary = engineSummary,
                    enabled = enabled,
                    onSelectedIndexChange = { index ->
                        prefs.handwritingEngine.setValue(engines[index])
                    },
                )
            }
        }

        item { SmallTitle(text = stringResource(R.string.handwriting_engine_google)) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.surfaceContainerHighest,
                ),
            ) {
                ArrowPreference(
                    title = stringResource(R.string.digital_ink_recognition_language),
                    summary = stringResource(
                        R.string.digital_ink_recognition_language_summary,
                        inkName,
                        inkSummary,
                    ),
                    onClick = onOpenModels,
                )
            }
        }

        item { SmallTitle(text = stringResource(R.string.handwriting_stylus_gestures)) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.surfaceContainerHighest,
                ),
            ) {
                ArrowPreference(
                    title = stringResource(R.string.handwriting_gesture_try),
                    summary = stringResource(R.string.handwriting_stylus_gestures_summary),
                    onClick = onOpenGestureDemo,
                )
            }
        }

    }
}
