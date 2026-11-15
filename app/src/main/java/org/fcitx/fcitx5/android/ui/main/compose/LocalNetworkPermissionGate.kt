/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.compose

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import github.xzynine.webdav.LocalNetworkPermission
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.toast

/**
 * Android 17（SDK 37）本地网络权限门。
 *
 * 自 Android 17 起，目标 SDK 37 的应用访问局域网默认被系统屏蔽，必须先获得
 * `ACCESS_LOCAL_NETWORK` 权限；未授予时连接只会超时而不报明确错误。因此所有访问
 * 局域网 WebDAV 服务器的入口都应先经过本门：不需要权限时直接放行，需要时弹出
 * 系统权限框并挂起等待结果。
 *
 * 目标地址与授权状态的判定（[LocalNetworkPermission]）由 WebDAV 子模块提供，
 * 本类只负责界面侧的权限请求与结果等待。
 */
class LocalNetworkPermissionGate internal constructor(
    private val context: Context,
    private val requestPermission: suspend (String) -> Boolean,
) {

    /**
     * 确保可以访问给定地址。
     *
     * @param url 待访问的远端地址；非局域网地址、已授权或平台未强制限制时立即返回 true
     * @param showDeniedTip 被拒绝时是否提示用户
     * @return true 表示可以继续访问
     */
    suspend fun ensure(url: String?, showDeniedTip: Boolean = true): Boolean {
        if (!LocalNetworkPermission.needsRequest(context, url)) return true
        val granted = requestPermission(LocalNetworkPermission.PERMISSION)
        if (!granted && showDeniedTip) {
            withContext(Dispatchers.Main) {
                context.toast(R.string.webdav_local_network_permission_required)
            }
        }
        return granted
    }
}

/**
 * 在当前组合内创建并记住本地网络权限门。
 *
 * 权限结果通过 [CompletableDeferred] 回传给挂起的调用方，因此 `ensure()` 可以像普通
 * suspend 函数一样在协程中顺序使用。
 */
@Composable
fun rememberLocalNetworkPermissionGate(): LocalNetworkPermissionGate {
    val context = LocalContext.current
    val pending = remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pending.value?.complete(granted)
            pending.value = null
        }
    return remember(context) {
        LocalNetworkPermissionGate(context) { permission ->
            val deferred = CompletableDeferred<Boolean>()
            pending.value = deferred
            launcher.launch(permission)
            deferred.await()
        }
    }
}
