/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.compose.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.sync.webdav.AutoDictSync
import org.fcitx.fcitx5.android.sync.webdav.DictReload
import org.fcitx.fcitx5.android.sync.webdav.SyncRestorer
import org.fcitx.fcitx5.android.sync.webdav.WebDavSyncConfig
import org.fcitx.fcitx5.android.sync.webdav.WebDavSyncEngine
import org.fcitx.fcitx5.android.sync.webdav.WebDavSyncEngine.RemoteEntry
import org.fcitx.fcitx5.android.ui.main.compose.dialog.SimpleConfirmDialog
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.formatDateTime
import org.fcitx.fcitx5.android.utils.toast
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.icon.extended.Show
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import timber.log.Timber
import java.io.File

private const val TAG = "WebDavSync"

/**
 * 失败详情文案：异常类名 + message。
 * 部分异常（NPE / 无参 SecurityException / 取消异常）message 为 null，
 * 此时至少能把异常类名暴露出来，便于定位。
 */
private fun Throwable.readable(): String {
    val msg = message?.takeIf { it.isNotBlank() }
    return if (msg == null) javaClass.simpleName else "${javaClass.simpleName}: $msg"
}

private fun logFailure(what: String, e: Throwable) {
    // ConciseTree 会丢弃 Throwable，因此把堆栈写进 message 文本
    Timber.e("$TAG $what failed: ${e.javaClass.name}, message=${e.message}, trace=${e.stackTraceToString()}")
}

/** WebDAV 服务器登录弹窗：编辑连接信息并测试连接。 */
@Composable
private fun WebDavLoginDialog(
    initial: WebDavSyncConfig,
    onSave: (serverUrl: String, username: String, password: String, deviceName: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var show by remember { mutableStateOf(true) }
    var serverUrl by remember { mutableStateOf(initial.serverUrl) }
    var username by remember { mutableStateOf(initial.username) }
    var password by remember { mutableStateOf(initial.password) }
    var deviceName by remember {
        mutableStateOf(initial.deviceName.ifBlank { WebDavSyncConfig.defaultDeviceName() })
    }
    var showPassword by remember { mutableStateOf(false) }
    var connectionResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    fun dismiss() {
        show = false
        onDismiss()
    }

    WindowDialog(
        show = show,
        title = stringResource(R.string.webdav_login_dialog_title),
        onDismissRequest = { dismiss() },
    ) {
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            TextField(
                value = serverUrl,
                onValueChange = { serverUrl = it; connectionResult = null },
                label = stringResource(R.string.webdav_server_url),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (serverUrl.trim().startsWith("http://", ignoreCase = true)) {
                Text(
                    text = stringResource(R.string.webdav_http_warning),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            TextField(
                value = username,
                onValueChange = { username = it; connectionResult = null },
                label = stringResource(R.string.webdav_username),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            TextField(
                value = password,
                onValueChange = { password = it; connectionResult = null },
                label = stringResource(R.string.webdav_password),
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            imageVector = if (showPassword) MiuixIcons.Hide else MiuixIcons.Show,
                            contentDescription = null,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            TextField(
                value = deviceName,
                onValueChange = { deviceName = it },
                label = stringResource(R.string.webdav_device_name),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            ArrowPreference(
                title = stringResource(R.string.webdav_test_connection),
                summary = connectionResult ?: stringResource(R.string.webdav_test_summary),
                onClick = {
                    if (testing || serverUrl.isBlank() || username.isBlank()) {
                        context.toast(R.string.webdav_need_server)
                        return@ArrowPreference
                    }
                    testing = true
                    val cfg = initial.copy(
                        serverUrl = serverUrl.trim(),
                        username = username,
                        password = password,
                        deviceName = deviceName.ifBlank { WebDavSyncConfig.defaultDeviceName() },
                    )
                    scope.launch {
                        val result = WebDavSyncEngine.testConnection(cfg)
                        testing = false
                        result.onSuccess { connectionResult = it }
                            .onFailure { connectionResult = it.message ?: context.getString(R.string.webdav_restore_failed) }
                    }
                },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, start = 0.dp, end = 0.dp),
        ) {
            TextButton(
                text = context.getString(android.R.string.cancel),
                onClick = { dismiss() },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = context.getString(android.R.string.ok),
                onClick = {
                    show = false
                    onSave(
                        serverUrl.trim(),
                        username,
                        password,
                        deviceName.ifBlank { WebDavSyncConfig.defaultDeviceName() },
                    )
                    onDismiss()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

@Composable
fun WebDavSyncScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val initial = remember { WebDavSyncConfig.load() }

    var serverUrl by remember { mutableStateOf(initial.serverUrl) }
    var username by remember { mutableStateOf(initial.username) }
    var password by remember { mutableStateOf(initial.password) }
    var deviceName by remember {
        mutableStateOf(initial.deviceName.ifBlank { WebDavSyncConfig.defaultDeviceName() })
    }
    var autoDict by remember { mutableStateOf(initial.dictAutoSync) }
    // 已持久化的同步状态（指纹/远端 mtime/摘要）：界面保存配置时原样保留，
    // 否则每次操作前的 save() 都会清掉引擎写入的“内容未变则跳过”凭据
    var syncState by remember { mutableStateOf(initial) }
    var showServerDialog by remember { mutableStateOf(false) }

    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<String?>(null) }
    var lastSync by remember { mutableStateOf(initial.lastSyncDescription) }

    var pickPrefs by remember { mutableStateOf(false) }
    var prefsList by remember { mutableStateOf<List<RemoteEntry>?>(null) }
    var confirmDict by remember { mutableStateOf(false) }
    var confirmPrefsEntry by remember { mutableStateOf<RemoteEntry?>(null) }

    /** 以 [syncState] 为基础，只覆盖界面编辑的连接字段与自动同步开关。 */
    fun currentCfg() = syncState.copy(
        serverUrl = serverUrl.trim(),
        username = username,
        password = password,
        deviceName = deviceName.ifBlank { WebDavSyncConfig.defaultDeviceName() },
        dictAutoSync = autoDict,
    )

    /** 保存配置；失败时提示并记日志，返回是否成功。 */
    fun persistCfg(cfg: WebDavSyncConfig): Boolean = cfg.save().fold(
        onSuccess = { true },
        onFailure = { e ->
            logFailure("save config", e)
            context.toast(R.string.webdav_save_failed)
            false
        },
    )

    fun requireReady(): Boolean {
        if (serverUrl.isBlank() || username.isBlank()) {
            context.toast(R.string.webdav_need_server)
            return false
        }
        return true
    }

    fun runOperation(
        busyMessage: String,
        op: suspend (WebDavSyncConfig, onProgress: suspend (String) -> Unit) -> Result<String>,
    ) {
        if (busy) return
        if (!requireReady()) return
        busy = true
        progress = busyMessage
        scope.launch {
            val cfg = currentCfg()
            if (!persistCfg(cfg)) {
                busy = false
                progress = null
                return@launch
            }
            val onProgress: suspend (String) -> Unit = { progress = it }
            val result = op(cfg, onProgress)
            // 操作可能写入了指纹/远端 mtime，重新读取以免后续保存覆盖
            syncState = WebDavSyncConfig.load()
            busy = false
            progress = null
            result.onSuccess {
                lastSync = it
                context.toast(it)
            }.onFailure {
                logFailure("runOperation", it)
                context.toast(it.readable())
            }
        }
    }

    fun restorePrefs(entry: RemoteEntry) {
        if (busy) return
        busy = true
        progress = context.getString(R.string.webdav_restoring)
        scope.launch {
            try {
                val cfg = currentCfg()
                // 文件名来自远端，先清洗成单段文件名再落盘，防止路径穿越覆盖缓存其它文件
                val dest = File(
                    context.cacheDir,
                    "webdav/${WebDavSyncEngine.sanitizeFileNamePart(entry.name)}"
                ).apply {
                    parentFile?.mkdirs()
                }
                progress = context.getString(R.string.webdav_downloading_backup, entry.name)
                WebDavSyncEngine.downloadRemoteZip(cfg, entry, dest)
                progress = context.getString(R.string.webdav_importing_prefs)
                SyncRestorer.restorePrefsZip(dest).getOrThrow()
                context.toast(R.string.webdav_prefs_restored)
                AppUtil.showRestartNotification(context)
                delay(500)
                AppUtil.exit()
            } catch (e: Exception) {
                logFailure("restorePrefs", e)
                context.toast(e.readable())
            } finally {
                busy = false
                progress = null
            }
        }
    }

    fun restoreDict() {
        if (busy) return
        busy = true
        progress = context.getString(R.string.webdav_restoring)
        scope.launch {
            try {
                val cfg = currentCfg()
                // 手动恢复：force=true 忽略本地快照，始终按云端内容覆盖本地；
                // 进度逐文件上报（文件名 + 第几个/共几个）
                val onProgress: suspend (String) -> Unit = { progress = it }
                val outcome =
                    WebDavSyncEngine.downloadDictFiles(cfg, force = true, onProgress).getOrThrow()
                if (outcome.downloaded == 0) {
                    context.toast(R.string.webdav_dict_already_latest)
                } else {
                    // 拼音/自定义短语可热重载，无需重启；table 类词库需重建进程才稳定
                    progress = context.getString(R.string.webdav_reloading_dict)
                    DictReload.applyReloadable(outcome.kinds)
                    syncState = WebDavSyncConfig.load()
                    if (DictReload.needsRestart(outcome.kinds)) {
                        // custom: table 词库被改动，引擎没有热重载接口；且引擎重启后
                        // IME 与引擎之间的连接状态不会复位（按键可响应但无法上屏），
                        // 只有重建进程才稳定。与偏好恢复一致：发通知后重启进程。
                        context.toast(R.string.webdav_dict_restored)
                        AppUtil.showRestartNotification(context)
                        delay(500)
                        AppUtil.exit()
                    } else {
                        context.toast(R.string.webdav_dict_restored)
                    }
                }
            } catch (e: Exception) {
                logFailure("restoreDict", e)
                context.toast(e.readable())
            } finally {
                busy = false
                progress = null
            }
        }
    }

    val loginSummary = if (serverUrl.isBlank()) {
        stringResource(R.string.webdav_login_summary_empty)
    } else {
        "${serverUrl}${if (username.isNotBlank()) "（${username}）" else ""}"
    }

    PageScaffold(
        title = stringResource(R.string.webdav_settings_title),
        onBack = onBack,
        contentBottomPadding = 24.dp,
    ) {
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                ArrowPreference(
                    title = stringResource(R.string.webdav_server_login),
                    summary = loginSummary,
                    onClick = { showServerDialog = true },
                )
            }
        }
        item {
            SmallTitle(text = stringResource(R.string.webdav_section_prefs))
        }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                ArrowPreference(
                    title = stringResource(R.string.webdav_upload_prefs),
                    summary = stringResource(R.string.webdav_upload_prefs_summary),
                    onClick = {
                        runOperation(context.getString(R.string.webdav_uploading_prefs)) { cfg, onProgress ->
                            WebDavSyncEngine.uploadPrefs(cfg, onProgress)
                        }
                    },
                )
                ArrowPreference(
                    title = stringResource(R.string.webdav_download_prefs),
                    summary = stringResource(R.string.webdav_download_prefs_summary),
                    onClick = {
                        if (busy || !requireReady()) return@ArrowPreference
                        busy = true
                        progress = context.getString(R.string.webdav_loading_list)
                        scope.launch {
                            val list = WebDavSyncEngine.listRemotePrefs(currentCfg())
                            busy = false
                            progress = null
                            prefsList = list
                            if (list.isEmpty()) {
                                context.toast(R.string.webdav_no_prefs_remote)
                            } else {
                                pickPrefs = true
                            }
                        }
                    },
                )
            }
        }
        item {
            SmallTitle(text = stringResource(R.string.webdav_section_dict))
        }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHighest),
            ) {
                SwitchPreference(
                    title = stringResource(R.string.webdav_auto_dict_enable),
                    summary = stringResource(R.string.webdav_auto_dict_summary),
                    checked = autoDict,
                    onCheckedChange = { value ->
                        // 先持久化成功再更新开关状态，失败时保持原状态并提示
                        val cfg = currentCfg().copy(dictAutoSync = value)
                        if (!persistCfg(cfg)) return@SwitchPreference
                        autoDict = value
                        if (value) {
                            AutoDictSync.startDownloadLoop()
                            context.toast(R.string.webdav_auto_dict_on)
                        }
                    },
                )
                ArrowPreference(
                    title = stringResource(R.string.webdav_upload_dict),
                    summary = stringResource(R.string.webdav_upload_dict_summary),
                    onClick = {
                        runOperation(context.getString(R.string.webdav_uploading_dict)) { cfg, onProgress ->
                            WebDavSyncEngine.uploadDictFiles(cfg, onProgress = onProgress)
                        }
                    },
                )
                ArrowPreference(
                    title = stringResource(R.string.webdav_download_dict),
                    summary = stringResource(R.string.webdav_download_dict_summary),
                    onClick = {
                        if (busy || !requireReady()) return@ArrowPreference
                        confirmDict = true
                    },
                )
            }
        }
        if (lastSync.isNotBlank()) {
            item {
                BasicComponent(
                    title = stringResource(R.string.webdav_last_sync),
                    summary = lastSync,
                )
            }
        }
        progress?.let {
            item {
                BasicComponent(
                    title = stringResource(R.string.webdav_busy),
                    summary = it,
                )
            }
        }
    }

    if (showServerDialog) {
        WebDavLoginDialog(
            initial = currentCfg(),
            onSave = { newUrl, newUser, newPass, newDevice ->
                val cfg = syncState.copy(
                    serverUrl = newUrl.trim(),
                    username = newUser,
                    password = newPass,
                    deviceName = newDevice.ifBlank { WebDavSyncConfig.defaultDeviceName() },
                    dictAutoSync = autoDict,
                )
                // 持久化成功才更新界面字段，失败则保持原配置
                if (persistCfg(cfg)) {
                    serverUrl = newUrl
                    username = newUser
                    password = newPass
                    deviceName = newDevice
                }
            },
            onDismiss = { showServerDialog = false },
        )
    }

    if (pickPrefs && prefsList != null) {
        val list = prefsList.orEmpty()
        var show by remember { mutableStateOf(true) }
        WindowDialog(
            show = show,
            title = stringResource(R.string.webdav_pick_prefs),
            onDismissRequest = {
                show = false
                pickPrefs = false
            },
        ) {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                list.forEach { entry ->
                    ArrowPreference(
                        title = entry.name,
                        summary = if (entry.lastModify > 0) formatDateTime(entry.lastModify) else "",
                        onClick = {
                            show = false
                            pickPrefs = false
                            confirmPrefsEntry = entry
                        },
                    )
                }
            }
        }
    }

    confirmPrefsEntry?.let { entry ->
        SimpleConfirmDialog(
            title = stringResource(R.string.webdav_restore_title),
            message = stringResource(R.string.webdav_restore_msg),
            onConfirm = {
                confirmPrefsEntry = null
                restorePrefs(entry)
            },
            onDismiss = { confirmPrefsEntry = null },
        )
    }

    if (confirmDict) {
        SimpleConfirmDialog(
            title = stringResource(R.string.webdav_restore_dict_title),
            message = stringResource(R.string.webdav_restore_dict_msg),
            onConfirm = {
                confirmDict = false
                restoreDict()
            },
            onDismiss = { confirmDict = false },
        )
    }
}
