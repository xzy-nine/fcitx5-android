/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.log.LogExportViewModel
import org.fcitx.fcitx5.android.utils.Logcat
import org.fcitx.fcitx5.android.ui.main.log.LogView
import org.fcitx.fcitx5.android.utils.DeviceInfo
import org.fcitx.fcitx5.android.utils.iso8601UTCDateTime
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 日志页的两种入口：实时日志与崩溃日志。 */
enum class LogMode { Live, Crash }

/**
 * 日志查看页。
 *
 * 实时模式持续读取 logcat；崩溃模式展示上次崩溃的堆栈并附带崩溃前的日志。
 * 支持过滤与导出为文本文件。
 */
@Composable
fun LogScreen(
    mode: LogMode,
    crashStackTrace: String?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // 与宿主 Activity 共用同一个导出模型实例
    val exportModel: LogExportViewModel = remember {
        androidx.lifecycle.ViewModelProvider(
            context as androidx.lifecycle.ViewModelStoreOwner,
        )[LogExportViewModel::class.java]
    }
    var logView by remember { mutableStateOf<LogView?>(null) }
    var query by remember { mutableStateOf("") }
    val fromCrash = mode == LogMode.Crash

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> exportModel.destination(uri) }

    // 导出的结果反馈
    LaunchedEffect(Unit) {
        exportModel.state.collect { state ->
            when (state.phase) {
                LogExportViewModel.Phase.READY -> {
                    if (exportModel.launchPicker()) {
                        runCatching {
                            exportLauncher.launch(
                                BuildConfig.APPLICATION_ID + "-" + iso8601UTCDateTime() + ".txt",
                            )
                        }.onFailure { exportModel.pickerFailed(it as Exception) }
                    }
                }
                LogExportViewModel.Phase.SUCCESS -> {
                    exportModel.consumeResult()
                    context.toast(R.string.done)
                }
                LogExportViewModel.Phase.FAILURE -> {
                    exportModel.consumeResult()
                    state.error?.let { context.toast(it) }
                }
                else -> Unit
            }
        }
    }

    PageScaffold(
        title = stringResource(if (fromCrash) R.string.crash_logs else R.string.real_time_logs),
        onBack = onBack,
        floatingActionButton = {
            ArrowPreference(
                title = stringResource(R.string.export),
                enabled = !exportModel.state.value.busy,
                modifier = Modifier.padding(16.dp),
                onClick = {
                    val view = logView ?: return@ArrowPreference
                    exportModel.prepare(DeviceInfo.get(context), view.snapshotLines())
                },
            )
        },
    ) {
        item {
            Card(
                modifier = Modifier.padding(12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                TextField(
                    value = query,
                    onValueChange = { value ->
                        query = value
                        logView?.setFilterQuery(value)
                    },
                    label = stringResource(R.string.search),
                )
                if (!fromCrash) {
                    ArrowPreference(
                        title = stringResource(R.string.clear),
                        onClick = {
                            logView?.clear()
                            // 同时清掉已保存的崩溃报告
                            runCatching {
                                org.fcitx.fcitx5.android.ui.main.log.CrashReportStore(
                                    FcitxApplication.getInstance().directBootAwareContext.noBackupFilesDir,
                                ).delete()
                            }
                        },
                    )
                }
            }
        }

        item {
            AndroidView(
                factory = { ctx ->
                    LogView(ctx).also { view ->
                        view.setFilterQuery(query)
                        if (fromCrash) {
                            view.append("--------- Crash stacktrace")
                            view.append(crashStackTrace ?: "<empty>")
                            view.setLogcat(Logcat(FcitxApplication.getLastPid()))
                        } else {
                            view.setLogcat(Logcat())
                        }
                        logView = view
                    }
                },
                modifier = Modifier.fillMaxWidth().fillMaxSize(),
            )
        }
    }
}
