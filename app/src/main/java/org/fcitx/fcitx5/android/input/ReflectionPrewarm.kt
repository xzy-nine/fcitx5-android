/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.os.SystemClock
import org.fcitx.fcitx5.android.input.broadcast.PreeditEmptyStateComponent
import org.fcitx.fcitx5.android.input.broadcast.PunctuationComponent
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.preedit.ComposePreeditComponent
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.IUniqueComponent
import org.mechdancer.dependency.findSuperGenericTypeRecursively
import timber.log.Timber

/**
 * kotlin-reflect 描述符子系统预热。
 *
 * `org.mechdancer.dependency` 把组件放入 `ConcurrentHashSet` 时调用其 `hashCode()`，
 * 而 [org.mechdancer.dependency.UniqueComponent.hashCode] 取的是 `type` 的 `hashCode`，
 * `type` 由 `IUniqueComponent.defaultType()` 经
 * `KClass.findSuperGenericTypeRecursively(IUniqueComponent::class)` 反射求得。
 * 该调用首次执行会初始化 kotlin-reflect 的内建模块与描述符（元数据反序列化），
 * 实测占输入视图冷启动 300ms 以上。
 *
 * 这里在进程启动阶段用后台线程先走一遍同一条调用路径，把这次初始化挪到输入视图装配之前。
 */
object ReflectionPrewarm {

    /** 预热用到的组件类：覆盖装配时最先进入作用域的几个。 */
    private val WARMUP_CLASSES = listOf(
        PopupComponent::class,
        PunctuationComponent::class,
        ReturnKeyDrawableComponent::class,
        PreeditEmptyStateComponent::class,
        ComposePreeditComponent::class,
        CommonKeyActionListener::class,
        KeyboardWindow::class,
        InputWindowManager::class,
    )

    /** 后台线程预热；失败只记录，不影响功能。 */
    fun prewarmAsync() {
        Thread({
            val begin = SystemClock.uptimeMillis()
            WARMUP_CLASSES.forEach { kClass ->
                try {
                    kClass.findSuperGenericTypeRecursively(IUniqueComponent::class)
                } catch (t: Throwable) {
                    Timber.w(t, "reflection prewarm failed for $kClass")
                }
            }
            Timber.d(
                "reflection prewarm: done in %dms (uptime=%d)",
                SystemClock.uptimeMillis() - begin,
                SystemClock.uptimeMillis()
            )
        }, "reflect-prewarm").apply { isDaemon = true }.start()
    }
}
