/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * 把 [InputEventQueue] 中的事件持续投递到共享流。
 *
 * 消费方只有一个：它退出后队列不再被排空，水位停在原处，
 * 之后每一次 [InputEventQueue.awaitCapacity] 都会永久挂起。按键路径在进入引擎 owner 之前
 * 就调用了它，于是引擎线程跟着卡住，界面表现为按键有反馈却没有内容。
 *
 * 因此循环只在协程被取消时结束：单批读取失败与单个投递失败都留在循环内部处理。
 */
internal object EventFlowEmitter {

    suspend fun drain(
        queue: InputEventQueue,
        emit: suspend (FcitxEvent<*>) -> Unit,
    ) {
        while (true) {
            val batch = try {
                queue.receive()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Event queue receive failed")
                continue
            }
            for (event in batch) {
                try {
                    emit(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 一个收集方抛错不得吞掉同批其余事件，也不得结束整个循环。
                    Timber.e(e, "Event delivery failed: " + event.eventType)
                }
            }
        }
    }
}
