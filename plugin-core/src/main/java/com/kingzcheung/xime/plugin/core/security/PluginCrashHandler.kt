/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Kingz Cheung
 *
 * 移植自 Xime (https://github.com/ximeiorg/xime)，见仓库根 NOTICE.md。
 */
package com.kingzcheung.xime.plugin.core.security

import android.app.Application
import android.util.Log

/**
 * 宿主进程级未捕获异常处理器（Xime `security/crash/PluginCrashHandler` 的无 UI 版本）。
 *
 * Xime 原实现会在插件相关崩溃时拉起 Material3 崩溃 Activity（security/crash 包）；
 * 本分支只移植 ASR 子集，**不移植任何 Compose/Material3 UI**，因此这里把原来的
 * "回调插件中心 / 崩溃页" 行为降级为 `android.util.Log` 记录，并始终把异常交回
 * 默认处理器（与 Xime 行为一致：Lua 在沙箱内执行，脚本错误不会传播到宿主进程，
 * 栈帧无法归属到具体插件，`findCulpritPluginId` 恒为 null，故最终必然委派默认处理器）。
 */
object PluginCrashHandler : Thread.UncaughtExceptionHandler {

    private const val TAG = "PluginCrashHandler"

    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    @Volatile
    private var initialized = false

    fun initialize(context: Application) {
        if (initialized) {
            Log.w(TAG, "PluginCrashHandler already initialized")
            return
        }
        initialized = true
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
        Log.i(TAG, "Plugin crash handler registered (log-only, no crash UI)")
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            Log.e(TAG, "Uncaught exception on ${thread.name}（非插件异常，交给默认处理器）", throwable)
        } catch (e: Exception) {
            Log.e(TAG, "Error in PluginCrashHandler", e)
        } finally {
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
