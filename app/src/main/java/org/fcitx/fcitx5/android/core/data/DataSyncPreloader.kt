/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber

/**
 * 数据目录同步的**预热**。
 *
 * [DataManager.sync] 需要读 assets 描述符、查询插件包、拷贝数据文件（词库等）并写回描述符，
 * 是一次体量不小的文件 IO。引擎启动必须等它完成（引擎要读 dataDir 里的词库），
 * 若这步排在引擎启动的关键路径上，输入视图的首帧组合就会与它抢 IO 与 CPU，
 * 表现为「输入法启动时有时会非常卡」。
 *
 * 这里把同步提前到进程启动阶段的后台线程，使它与输入视图的首帧组合**并行**；
 * 引擎启动处只需等它结束（通常早已完成）即可，不再把这段 IO 压在启动关键路径上。
 */
object DataSyncPreloader {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var job: Job? = null

    /**
     * 启动一次预同步（幂等，可重复调用）。
     *
     * 已在进行中或已完成时不重复发起：[DataManager.sync] 自带锁与幂等语义，
     * 但重复执行仍会白做一遍 diff 与文件拷贝。
     */
    fun start() {
        if (job != null) return
        job = scope.launch {
            val begin = System.currentTimeMillis()
            runCatching { DataManager.sync() }
                .onFailure { Timber.w(it, "data sync preload failed") }
            Timber.d("data sync preload: done in %dms", System.currentTimeMillis() - begin)
        }
    }

    /**
     * 等待预同步结束。
     *
     * 未发起过预同步时在此补做一次（同步执行），保证调用方拿到的数据目录一定是完整的。
     */
    fun await() {
        val current = job
        if (current == null) {
            runCatching { DataManager.sync() }
                .onFailure { Timber.w(it, "data sync failed") }
            return
        }
        runCatching { runBlocking { current.join() } }
            .onFailure { Timber.w(it, "data sync preload join failed") }
        // 预同步本身失败时补做一次，保证引擎读到的是完整数据目录
        if (!DataManager.synced) {
            runCatching { DataManager.sync() }
                .onFailure { Timber.w(it, "data sync retry failed") }
        }
    }
}
