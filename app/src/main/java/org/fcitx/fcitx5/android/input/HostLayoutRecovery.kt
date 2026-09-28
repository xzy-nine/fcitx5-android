/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.os.SystemClock
import android.view.View
import timber.log.Timber

/**
 * custom：「Compose 内容已长高、宿主 ComposeView 仍是旧高度」的布局恢复。
 *
 * ## 背景（实测结论）
 *
 * `AndroidView(InputView)` 内嵌 Compose 的场景下，Compose 互操作宿主 `AndroidViewHolder`
 * 会在**自身 measure 过程中**调用 `setMinimumHeight()` → `requestLayout()`。该请求落在
 * ViewRootImpl 的布局阶段，会被 `requestLayoutDuringLayout()` 吞掉，只在子 View 上留下
 * `PFLAG_FORCE_LAYOUT`，却没有第二次遍历来清除它。
 *
 * 之后 View 层遍历完全停摆（实测 12 秒内 measure/layout/globalLayout 计数一次未增），
 * 而 Compose 内部仍按新高度布局（Column 长到 168px），于是内容被旧宿主高度（110px）
 * 裁掉 —— 表现为候选栏/工具栏被压成一条；且因标志永不清除、**不会自愈**，
 * 只能重启输入法。
 *
 * ## 修法
 *
 * Compose 内容每次布局后，用 [post] 异步核对宿主高度：若仍与内容高度不一致，
 * 就从**非布局上下文**补发一次 `requestLayout()`，把遍历唤醒。
 *
 * 之所以必须 `post` 而非直接调用：直接调用仍处于布局阶段，会被同样的路径吞掉。
 * 而 `post` 的回调在遍历结束后的消息队列里执行，此时请求可正常传播到 ViewRootImpl。
 *
 * ## 为何对健康路径无副作用
 *
 * 健康路径下宿主会在同一帧内完成测量，`post` 回调届时看到「宿主高度 == 内容高度」，
 * 直接返回、不产生任何额外布局请求。只有宿主确实滞后时才补发，故不会造成每帧多遍历。
 *
 * 另设最小重试间隔，避免万一无法恢复时形成紧凑的请求-重排循环；恢复成功即自然静默。
 */
private const val RECOVERY_MIN_INTERVAL_MS = 300L

/** 恢复时间戳的 tag key（用常量哈希避免占用资源 id）。 */
private val RECOVERY_STAMP_KEY = "custom_host_layout_recovery".hashCode()

/**
 * 核对宿主 [View] 高度是否跟上了 Compose 内容高度；滞后则异步补发一次布局请求。
 *
 * 应从承载 Compose 内容、且高度为 `wrap_content` 的那个宿主 View 上调用
 * （本项目中即 `InputView.composeTopView`）。
 *
 * @param contentHeightPx Compose 内容刚布局出的实际高度（px）
 */
fun View.recoverHostLayoutIfStale(contentHeightPx: Int) {
    if (contentHeightPx <= 0 || !isAttachedToWindow) return
    post {
        // 宿主尚未完成首次布局时不介入（冷启动早期本就没有遍历）
        if (height <= 0) return@post
        // 健康路径：宿主已在同帧内跟上，无操作、无额外布局请求
        if (contentHeightPx == height) return@post
        val now = SystemClock.uptimeMillis()
        val last = (getTag(RECOVERY_STAMP_KEY) as? Long) ?: 0L
        if (now - last < RECOVERY_MIN_INTERVAL_MS) return@post
        setTag(RECOVERY_STAMP_KEY, now)
        Timber.w(
            "layout recovery: host height=$height stale, compose content=$contentHeightPx; " +
                    "re-requesting layout"
        )
        requestLayout()
    }
}
