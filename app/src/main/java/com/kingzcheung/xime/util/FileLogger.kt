/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Kingz Cheung
 *
 * 移植自 Xime (https://github.com/ximeiorg/xime) 的 util/FileLogger.kt，
 * 见仓库根 NOTICE.md。
 *
 * 与上游的差异（为适配 fcitx5-android）：
 *  - 去掉文件落盘/轮转/后台 flusher 线程与 SettingsPreferences/BuildConfig 依赖，
 *    改为薄封装 android.util.Log。原因：本仓库的 ASR 原生推理跑在独立 `:asr` 进程，
 *    该进程不初始化 AppPrefs 与用户数据目录，任何依赖它们的日志实现都会在 :asr 里炸；
 *    语音输入期间需要排查的东西交给 logcat / Timber 即可。
 *  - 因此 init() 保留为空实现，仅供调用点兼容。
 */
package com.kingzcheung.xime.util

import android.content.Context
import android.util.Log

object FileLogger {

    private const val TAG = "FileLogger"

    /** 兼容上游：上游用于初始化文件日志。本移植只写 logcat，无需初始化。 */
    @Suppress("UNUSED_PARAMETER")
    fun init(context: Context) = Unit

    fun v(tag: String, message: String) {
        Log.v(tag, message)
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
    }

    fun e(tag: String, message: String) {
        Log.e(tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable?) {
        Log.e(tag, message, throwable)
    }

    /** 兼容上游的显式 flush。 */
    fun flush() = Unit

    fun isInitialized(): Boolean = true

    @Suppress("UNUSED_PARAMETER")
    fun setVerboseLoggingEnabled(enabled: Boolean) = Unit

    /** 便于排查"日志实现是否被换过"。 */
    fun backend(): String = "android.util.Log ($TAG)"
}
