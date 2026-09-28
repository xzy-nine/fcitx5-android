/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.bar

import timber.log.Timber

/**
 * custom(临时诊断)：候选栏「被错误挤压」排查日志。
 *
 * 现象为**概率性**、多出现在输入法冷启动后的第一次输入，故需要把「请求高度 / 实测高度 /
 * insets 补偿」等值带上相对进程启动的时间戳打出来，便于横向对比多次冷启动日志。
 *
 * **只在内容变化时输出**：本对象挂在 `toolbarHeight` getter 与 `onComputeInsets` 这类
 * 高频调用点上，逐次打印会把信号淹没在重复行里（同值重复对排查毫无信息量）。
 * 用 [logChange] 按 (event, detail) 去重 —— 同一条日志只有内容变化才重新打印，
 * 于是「值没变」天然不产生输出，序列本身就是「变化点时间线」。
 *
 * 排查结束后应连同各调用点一起删除。
 * 过滤：`adb logcat -s CBSqueeze`
 */
object ToolbarHeightTrace {

    private const val TAG = "CBSqueeze"

    private val processStart = System.currentTimeMillis()

    /** 去重表：key -> 上次打印的 dedupeKey。仅主线程访问，无需加锁。 */
    private val lastLogged = HashMap<String, String>()

    /**
     * 按 [dedupeKey] 去重后打印 [detail]。
     *
     * 两者刻意分开：**去重维度**只取「我们真正关心的那个变化」（如几何尺寸），
     * 而 [detail] 可以带更丰富的上下文（如当前 barState / 候选数）。
     * 这样打字时内容每帧都变、几何不变，就不会刷屏；几何一变立刻有行。
     * 值回到旧值时也会重新打印（因为上次记录的是中间值），故「变化点」完整保留。
     *
     * @param key        日志类别，独立去重（同一类别内比较 [dedupeKey]）
     * @param dedupeKey  去重维度：与上次相同则整条跳过
     * @param detail     实际打印内容
     */
    fun logChange(key: String, dedupeKey: String, detail: String) {
        if (lastLogged[key] == dedupeKey) return
        lastLogged[key] = dedupeKey
        log(key, detail)
    }

    /**
     * 清除某类别的去重记录，使下一次 [logChange] 必定输出。
     * 供「周期性监测 + 恢复后复位」的探针使用（如布局停摆监测）。
     */
    fun reset(key: String) {
        lastLogged.remove(key)
    }

    /** 无条件打印。用于低频、每次都必须留痕的点（如候选集更新）。 */
    fun log(event: String, detail: String) {
        Timber.tag(TAG).d("[+${System.currentTimeMillis() - processStart}ms] $event | $detail")
    }
}
