package io.legado.app.eink.util

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * onEachParallel 并发消费语义：flatMapMerge 限并发，action 异常中断
 * collect，并发为 1 时退化为顺序执行。
 */
class OnEachParallelTest {

    @Test
    fun `并发上限受 concurrency 约束且全部元素被消费`() = runTest {
        val inFlight = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val processed = mutableSetOf<Int>()

        val collected = (1..50).asFlow()
            .onEachParallel(concurrency = 3) { value ->
                val cur = inFlight.incrementAndGet()
                peak.accumulateAndGet(cur) { a, b -> maxOf(a, b) }
                delay(10)
                inFlight.decrementAndGet()
                processed += value
            }
            .toList()

        assertEquals(50, collected.size)
        assertEquals((1..50).toSet(), processed)
        assertEquals(
            "挂起式 action 期间应吃满并发额度",
            3,
            peak.get(),
        )
    }

    @Test
    fun `action 异常中断 collect`() = runTest {
        val processed = AtomicInteger(0)
        try {
            (1..10).asFlow()
                .onEachParallel(concurrency = 2) {
                    processed.incrementAndGet()
                    if (it == 2) error("boom")
                }
                .toList()
            fail("action 抛出的异常应传播到 collect")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
    }

    @Test
    fun `concurrency 为 1 时按序执行`() = runTest {
        val order = mutableListOf<Int>()

        val collected = (1..5).asFlow()
            .onEachParallel(concurrency = 1) { order.add(it) }
            .toList()

        assertEquals(listOf(1, 2, 3, 4, 5), order)
        assertEquals(listOf(1, 2, 3, 4, 5), collected)
    }

    @Test
    fun `空流直接完成`() = runTest {
        val actions = AtomicInteger(0)
        val collected = emptyList<Int>().asFlow()
            .onEachParallel(4) { actions.incrementAndGet() }
            .toList()
        assertTrue(collected.isEmpty())
        assertEquals(0, actions.get())
    }
}
