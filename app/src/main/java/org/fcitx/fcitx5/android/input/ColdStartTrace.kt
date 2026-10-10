/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import org.fcitx.fcitx5.android.BuildConfig
import timber.log.Timber

/**
 * 输入视图冷启动分阶段计时。
 *
 * 计时区间为 [begin] 到 [finish]：起点是框架首次调用 `onCreateInputView`，
 * 终点是输入视图的首个预绘制回调。区间内每次 [mark] 记录「距起点」与「距上一标记」两个耗时，
 * [finish] 输出一行汇总；[pass] 记录组合次数，[duration] 记录重复操作（如整树测量）的次数与耗时。
 *
 * 区间外（未 [begin] 或已 [finish]）的所有调用均为空操作，不产生日志。
 */
object ColdStartTrace {

    /** 单次计时最多记录的阶段数，防止异常路径下无限增长。 */
    private const val MAX_STAGES = 48

    private val lock = Any()

    @Volatile
    private var active = false
    private var startUptime = 0L
    private var lastUptime = 0L
    private val stages = ArrayList<String>(MAX_STAGES)
    private val marks = ArrayList<Int>(MAX_STAGES)
    private val passes = HashMap<String, Int>()

    /** 开始一次计时并清空上次结果。 */
    fun begin() {
        synchronized(lock) {
            active = true
            startUptime = SystemClock.uptimeMillis()
            lastUptime = startUptime
            stages.clear()
            marks.clear()
            passes.clear()
        }
    }

    /** 记录一个阶段；未在计时区间内则忽略。 */
    fun mark(stage: String) {
        synchronized(lock) {
            if (!active) return
            if (stages.size >= MAX_STAGES) return
            val now = SystemClock.uptimeMillis()
            val total = (now - startUptime).toInt()
            val delta = (now - lastUptime).toInt()
            lastUptime = now
            stages.add(stage)
            marks.add(total)
            Timber.d("cold start: %s +%dms (delta %dms)", stage, total, delta)
        }
    }

    /** 同一计时区间内最多记录一次的 [mark]，供每次重组都会执行的位置使用。 */
    fun markOnce(stage: String) {
        synchronized(lock) {
            if (!active || stages.contains(stage)) return
        }
        mark(stage)
    }

    /** 记录一次组合/重组（每次计数），用于识别首帧内的重复组合。 */
    fun pass(stage: String) {
        synchronized(lock) {
            if (!active) return
            val count = (passes[stage] ?: 0) + 1
            passes[stage] = count
            Timber.d(
                "cold start pass: %s #%d +%dms",
                stage, count, (SystemClock.uptimeMillis() - startUptime).toInt()
            )
        }
    }

    /** 记录一次带耗时的重复操作（每次测量），用于识别重复的整树测量与单次代价。 */
    fun duration(stage: String, durationMs: Long) {
        synchronized(lock) {
            if (!active) return
            val count = (passes[stage] ?: 0) + 1
            passes[stage] = count
            Timber.d(
                "cold start duration: %s #%d took %dms (at +%dms)",
                stage, count, durationMs, (SystemClock.uptimeMillis() - startUptime).toInt()
            )
        }
    }

    /** 记录末阶段并输出汇总，随后结束本次计时。 */
    fun finish(stage: String) {
        mark(stage)
        synchronized(lock) {
            if (!active) return
            active = false
            Timber.d(
                "cold start summary: debug=%b total=%dms [%s]",
                BuildConfig.DEBUG,
                marks.lastOrNull() ?: 0,
                stages.zip(marks) { name, at -> "$name=$at" }.joinToString(", ")
            )
        }
    }
}

/** 在 [View] 首次预绘制时结束本次冷启动计时。 */
fun View.traceFirstPreDraw(stage: String) {
    val observer = viewTreeObserver
    observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            val current = viewTreeObserver
            if (current.isAlive) current.removeOnPreDrawListener(this)
            ColdStartTrace.finish(stage)
            return true
        }
    })
}
