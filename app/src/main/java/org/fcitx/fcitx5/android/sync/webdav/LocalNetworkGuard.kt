/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.sync.webdav

import github.xzynine.webdav.LocalNetworkPermission
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.appContext

/**
 * 目标位于局域网但未获得「本地网络」权限时抛出的异常。
 *
 * 携带用户可见文案（[message]），界面层直接展示即可，不需要再拼接异常类名。
 */
class LocalNetworkPermissionRequiredException(message: String) : Exception(message)

/**
 * Android 17（SDK 37）本地网络访问守卫。
 *
 * 自 Android 17 起，目标 SDK 37 的应用访问局域网默认被系统屏蔽，缺少
 * `ACCESS_LOCAL_NETWORK` 权限时 TCP 只会超时、不返回明确错误。界面入口经
 * `LocalNetworkPermissionGate` 主动申请权限；本守卫用于兜底（后台自动同步、
 * 以及任何绕过界面入口的调用），在真正发起请求前失败并给出可操作文案。
 *
 * 判定逻辑与授权状态由 WebDAV 子模块的 [LocalNetworkPermission] 提供。
 */
object LocalNetworkGuard {

    /** 缺少权限时的用户可见文案。 */
    val deniedMessage: String
        get() = appContext.getString(R.string.webdav_local_network_permission_required)

    /**
     * 目标位于局域网且尚未授权时返回 true。
     *
     * 平台未强制限制（Android 16 及以下）或目标不是局域网地址时恒为 false。
     */
    fun isDenied(url: String?): Boolean =
        LocalNetworkPermission.needsRequest(appContext, url)

    /**
     * 访问给定地址前校验；缺少权限时抛出 [LocalNetworkPermissionRequiredException]。
     */
    fun require(url: String?) {
        if (isDenied(url)) throw LocalNetworkPermissionRequiredException(deniedMessage)
    }
}
