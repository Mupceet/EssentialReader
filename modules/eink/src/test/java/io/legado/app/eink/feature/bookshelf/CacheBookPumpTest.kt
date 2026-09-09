package io.legado.app.eink.feature.bookshelf

import io.legado.app.eink.contract.BookshelfEngine
import io.legado.app.eink.contract.BookshelfItemUiModel
import io.legado.app.eink.contract.BookshelfStyle
import io.legado.app.eink.contract.BookshelfTocRefreshResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 预缓存泵宿主语义：启动即拉起引擎消费循环（与目录刷新并行，无工作态
 * 暂停逻辑）、运行中 start 去重复用、泵结束后可重新启动。
 *
 * 泵作用域经 scopeOverride 注入测试虚拟时钟；[pumpTest] 保证任意退出
 * 路径（含断言失败）先取消泵——否则 runTest 收尾会把「门控挂起的泵」
 * 无限推进直至 OOM。
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
    fun `start 拉起引擎消费循环`() = pumpTest {
        val engine = FakeEngine()

        CacheBookPump.start(engine)
        runCurrent()

        assertEquals(1, engine.startCount.get())
    }

    @Test
    fun `泵运行中重复 start 去重复用`() = pumpTest {
        val engine = FakeEngine()

        val first = CacheBookPump.start(engine)
        runCurrent()
        val second = CacheBookPump.start(engine)

        assertEquals(first, second)
        assertEquals(1, engine.startCount.get())
    }

    @Test
    fun `泵结束后可再次 start`() = pumpTest {
        val engine = FakeEngine(gateOpen = true)

        val first = CacheBookPump.start(engine)
        advanceUntilIdle()
        assertTrue("队列清空后泵任务应自终止", first.isCompleted)

        val second = CacheBookPump.start(engine)
        advanceUntilIdle()

        assertNotEquals(first, second)
        assertEquals(2, engine.startCount.get())
        assertTrue(second.isCompleted)
    }
}

private class FakeEngine(gateOpen: Boolean = false) : BookshelfEngine {
    val startCount = AtomicInteger(0)
    private val gate = CompletableDeferred<Unit>().apply {
        if (gateOpen) complete(Unit)
    }

    override val style: Flow<BookshelfStyle> = emptyFlow()

    override suspend fun setGridLayout(grid: Boolean) = Unit

    override fun observeShelf(): Flow<List<BookshelfItemUiModel>> = emptyFlow()

    override fun lastReadBookUrl(): String? = null

    override suspend fun deleteBooksNotInBookshelf() = Unit

    override suspend fun updatableBooks(): List<BookshelfItemUiModel> = emptyList()

    override suspend fun refreshBookToc(bookUrl: String): BookshelfTocRefreshResult =
        BookshelfTocRefreshResult.OK

    override val isCacheRunning: Boolean get() = false

    override fun setCacheWorkingState(working: Boolean) = Unit

    override suspend fun startCacheProcessJob() {
        startCount.incrementAndGet()
        gate.await()
    }
}
