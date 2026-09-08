package io.legado.app.eink.feature.bookshelf

import io.legado.app.eink.contract.BookshelfEngine
import io.legado.app.eink.contract.BookshelfItemUiModel
import io.legado.app.eink.contract.BookshelfTocRefreshResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 预缓存泵宿主语义：启动即置工作态、运行中 start 去重、轮询按刷新态
 * 翻转工作态（目录优先）、泵结束后可重新启动、接管刷新态不起泵。
 *
 * 泵作用域经 scopeOverride 注入测试虚拟时钟（1s 轮询即时推进）；
 * [pumpTest] 保证任意退出路径（含断言失败）先取消泵——否则 runTest
 * 收尾会把「门控挂起的泵 + 1s 轮询」无限推进直至 OOM。
 */
class CacheBookPumpTest {

    private fun pumpTest(block: suspend TestScope.() -> Unit) = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        CacheBookPump.scopeOverride = scope
        try {
            block()
        } finally {
            scope.cancel()
            CacheBookPump.scopeOverride = null
        }
    }

    @Test
    fun `start 启动泵并置工作态为运行`() = pumpTest {
        val engine = FakeEngine()

        CacheBookPump.start(engine) { false }
        runCurrent()

        assertEquals(1, engine.startCount.get())
        assertEquals(listOf(true), engine.workingStates)
    }

    @Test
    fun `刷新进行中启动时工作态为暂停`() = pumpTest {
        val engine = FakeEngine()

        CacheBookPump.start(engine) { true }
        runCurrent()

        assertEquals(listOf(false), engine.workingStates)
    }

    @Test
    fun `泵运行中重复 start 去重`() = pumpTest {
        val engine = FakeEngine()

        val first = CacheBookPump.start(engine) { false }
        runCurrent()
        val second = CacheBookPump.start(engine) { false }

        assertEquals(first, second)
        assertEquals(1, engine.startCount.get())
    }

    @Test
    fun `轮询按刷新态翻转工作态`() = pumpTest {
        val engine = FakeEngine()

        var refreshing = false

        CacheBookPump.start(engine) { refreshing }
        runCurrent()
        assertEquals(true, engine.workingStates.last())

        refreshing = true
        advanceTimeBy(1_001)
        assertEquals("目录刷新进行中应暂停泵", false, engine.workingStates.last())

        refreshing = false
        advanceTimeBy(1_001)
        assertEquals("刷新结束后应恢复泵", true, engine.workingStates.last())
    }

    @Test
    fun `泵结束后可再次 start`() = pumpTest {
        val engine = FakeEngine(gateOpen = true)

        val first = CacheBookPump.start(engine) { false }
        advanceUntilIdle()
        assertTrue("队列清空后泵任务应自终止", first.isCompleted)

        val second = CacheBookPump.start(engine) { false }
        advanceUntilIdle()

        assertNotEquals(first, second)
        assertEquals(2, engine.startCount.get())
        assertTrue(second.isCompleted)
    }

    @Test
    fun `updateRefreshState 单独调用不启动泵`() = pumpTest {
        val engine = FakeEngine()

        CacheBookPump.updateRefreshState { true }
        runCurrent()

        assertEquals(0, engine.startCount.get())
    }

    @Test
    fun `新 start 接管刷新态读取器`() = pumpTest {
        val oldEngine = FakeEngine(gateOpen = true)

        CacheBookPump.start(oldEngine) { false }
        advanceUntilIdle()

        val newEngine = FakeEngine()
        var newRefreshing = true
        CacheBookPump.start(newEngine) { newRefreshing }
        runCurrent()

        assertEquals("接管后以新读取器为准", listOf(false), newEngine.workingStates)
        newRefreshing = false
        advanceTimeBy(1_001)
        assertEquals(listOf(false, true), newEngine.workingStates)
    }
}

private class FakeEngine(gateOpen: Boolean = false) : BookshelfEngine {
    val startCount = AtomicInteger(0)
    val workingStates = mutableListOf<Boolean>()
    private val gate = CompletableDeferred<Unit>().apply {
        if (gateOpen) complete(Unit)
    }

    override fun observeShelf(): Flow<List<BookshelfItemUiModel>> = emptyFlow()

    override fun lastReadBookUrl(): String? = null

    override suspend fun deleteBooksNotInBookshelf() = Unit

    override suspend fun updatableBooks(): List<BookshelfItemUiModel> = emptyList()

    override suspend fun refreshBookToc(bookUrl: String): BookshelfTocRefreshResult =
        BookshelfTocRefreshResult.OK

    override val isCacheRunning: Boolean get() = false

    override fun setCacheWorkingState(working: Boolean) {
        workingStates += working
    }

    override suspend fun startCacheProcessJob() {
        startCount.incrementAndGet()
        gate.await()
    }
}
