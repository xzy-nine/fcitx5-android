/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

class FcitxDispatcher(private val controller: FcitxController) : CoroutineDispatcher() {

    class WrappedRunnable(private val runnable: Runnable) : Runnable by runnable {
        private val time = System.currentTimeMillis()

        override fun run() {
            val delta = System.currentTimeMillis() - time
            if (delta > JOB_WAITING_LIMIT) {
                Timber.w("$this has waited $delta ms to get run since created!")
            }
            runnable.run()
        }

        override fun toString(): String = "WrappedRunnable[${hashCode()}]"
    }

    // this is fcitx main thread
    private val internalDispatcher = Executors.newSingleThreadExecutor {
        Thread(it).apply {
            name = "fcitx-main"
        }
    }.asCoroutineDispatcher()

    private val internalScope = CoroutineScope(internalDispatcher)

    interface FcitxController {
        fun nativeStartup()
        fun nativeLoopOnce()
        fun nativeScheduleEmpty()
        fun nativeExit()
    }

    private val runningLock = Mutex()

    private val queue = ConcurrentLinkedQueue<WrappedRunnable>()

    private val isRunning = AtomicBoolean(false)

    /**
     * 由 fcitx 线程在即将阻塞于 `nativeLoopOnce()` 之前置位、返回后清零。
     * `dispatch()` 仅在该标志为真时才调用 `nativeScheduleEmpty()`——
     * 线程正在排空队列时本轮 `poll()` 即可取到刚入队的任务，无需额外 JNI 唤醒。
     *
     * 无漏唤醒证明：dispatch 的顺序是 `offer` → 读标志。
     *  - 读到 true ⇒ fcitx 线程已置位，正阻塞或即将阻塞 ⇒ 唤醒它。
     *  - 读到 false ⇒ 线程在排空或即将回到循环顶；置位发生在循环顶，
     *    而置位后紧跟一次 `queue.isNotEmpty()` 复查，若期间有任务入队复查必命中、
     *    直接排空而不阻塞。故「offer 未被本轮 poll 取到」与「标志为 false」不会同时成立。
     */
    private val wakeupNeeded = AtomicBoolean(false)

    /**
     * Start the dispatcher
     * This function returns immediately
     */
    fun start() {
        Timber.d("FcitxDispatcher start()")
        internalScope.launch {
            runningLock.withLock {
                if (isRunning.compareAndSet(false, true)) {
                    Timber.d("nativeStartup()")
                    controller.nativeStartup()
                    while (isActive && isRunning.get()) {
                        // 标记即将阻塞，让并发 dispatch 能据此决定是否唤醒。
                        wakeupNeeded.set(true)
                        // 复查队列：若置位与 offer 之间有任务入队，直接排空，避免漏唤醒。
                        if (queue.isNotEmpty()) {
                            wakeupNeeded.set(false)
                        } else {
                            // blocking...
                            controller.nativeLoopOnce()
                            wakeupNeeded.set(false)
                        }
                        // do scheduled jobs
                        while (true) {
                            val block = queue.poll() ?: break
                            block.run()
                        }
                    }
                    Timber.i("nativeExit()")
                    controller.nativeExit()
                }
            }
        }
    }

    /**
     * Stop the dispatcher
     * This function blocks until fully stopped
     */
    fun stop(): List<Runnable> {
        Timber.i("FcitxDispatcher stop()")
        return if (isRunning.compareAndSet(true, false)) {
            runBlocking {
                controller.nativeScheduleEmpty()
                runningLock.withLock {
                    val rest = queue.toList()
                    queue.clear()
                    rest
                }
            }
        } else emptyList()
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (!isRunning.get()) {
            throw IllegalStateException("Dispatcher is not in running state!")
        }
        queue.offer(WrappedRunnable(block))
        // 仅当 fcitx 线程正阻塞在 nativeLoopOnce 时才需要唤醒；
        // 它正在排空队列时本轮 poll 会取到刚入队的任务，无需额外的 JNI 唤醒。
        if (wakeupNeeded.get()) {
            controller.nativeScheduleEmpty()
        }
    }

    companion object {
        const val JOB_WAITING_LIMIT = 2000L
    }

}