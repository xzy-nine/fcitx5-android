/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 事件排空循环必须活到协程被取消为止。
 *
 * 它一旦提前退出，[InputEventQueue] 不再被排空，水位停在原处，之后每次
 * [InputEventQueue.awaitCapacity] 都永久挂起；按键路径在进入引擎 owner 之前就等这里，
 * 于是引擎线程一起卡死，界面表现为按键有反馈却没有内容。
 */
class EventFlowEmitterTest {

    private fun panel(text: String) = FcitxEvent.InputPanelEvent(
        FcitxEvent.InputPanelEvent.Data(
            FormattedText(arrayOf(text), intArrayOf(0), text.length),
            FormattedText.Empty, FormattedText.Empty, emptyArray(),
        )
    )

    @Test fun aThrowingDeliveryDoesNotEndTheLoopOrSkipTheRestOfItsBatch() = runBlocking {
        val queue = InputEventQueue()
        val first = panel("n")
        val second = panel("ni")
        val third = panel("nih")
        val delivered = mutableListOf<FcitxEvent<*>>()

        val job = async(start = CoroutineStart.UNDISPATCHED) {
            EventFlowEmitter.drain(queue) { event ->
                delivered += event
                // 第一个事件投递失败，模拟某个收集方抛异常。
                if (event === first) error("collector failed")
            }
        }
        try {
            queue.offer(listOf(first, second))
            withTimeout(5_000) { while (delivered.size < 2) yield() }
            assertEquals(listOf(first, second), delivered)

            // 循环仍在运行：后续批次照常投递。
            queue.offer(listOf(third))
            withTimeout(5_000) { while (delivered.size < 3) yield() }
            assertEquals(listOf(first, second, third), delivered)
            assertTrue("The loop must still be running", job.isActive)
        } finally {
            job.cancel()
        }
    }

    @Test fun drainedBatchesReleaseCapacityEvenWhileDeliveriesFail() = runBlocking {
        // 水位只由排空方下降：投递失败也不得让循环停下，否则等待容量的按键会永久挂起。
        val queue = InputEventQueue(highWatermark = 1)
        val delivered = mutableListOf<FcitxEvent<*>>()

        val job = async(start = CoroutineStart.UNDISPATCHED) {
            EventFlowEmitter.drain(queue) { event ->
                delivered += event
                error("collector failed")
            }
        }
        try {
            queue.offer(listOf(panel("a")))
            queue.offer(listOf(panel("b")))
            // 排空方活着，水位必然回落到阈值以下。
            withTimeout(5_000) { queue.awaitCapacity() }
            // 同一类展示事件在排空前会被合并，投递失败不改变这一点。
            assertEquals(1, delivered.size)
            assertTrue(job.isActive)
        } finally {
            job.cancel()
        }
    }
}
