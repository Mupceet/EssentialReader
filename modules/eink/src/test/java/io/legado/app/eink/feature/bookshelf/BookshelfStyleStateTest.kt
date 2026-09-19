package io.legado.app.eink.feature.bookshelf

import io.legado.app.eink.contract.BookshelfStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 样式状态宿主语义：覆盖优先（面板改动即时生效）、快照追平清除
 * （落库完成与流发射间不回跳）、追平后快照继续驱动。
 */
class BookshelfStyleStateTest {

    @Test
    fun `提交后覆盖优先于快照`() = runTest {
        val snapshot = MutableStateFlow(BookshelfStyle())
        val state = BookshelfStyleState(snapshot, backgroundScope)
        state.submit(BookshelfStyle(gridCoverWidth = 99, isGridLayout = false))
        val style = state.style.first()
        assertEquals(99, style.gridCoverWidth)
        assertEquals(false, style.isGridLayout)
    }

    @Test
    fun `快照追平同值后清除覆盖且样式保持`() = runTest {
        val snapshot = MutableStateFlow(BookshelfStyle())
        val state = BookshelfStyleState(snapshot, backgroundScope)
        val submitted = BookshelfStyle(gridCoverWidth = 99)
        state.submit(submitted)
        snapshot.value = submitted
        // 覆盖已清：样式 = 快照（同值，观感不变）
        assertEquals(99, state.style.first().gridCoverWidth)
        // 快照继续驱动：改快照即跟随，旧覆盖不再拦截
        snapshot.value = BookshelfStyle(gridCoverWidth = 77)
        assertEquals(77, state.style.first().gridCoverWidth)
    }

    @Test
    fun `未追平时中间快照不误清覆盖`() = runTest {
        val snapshot = MutableStateFlow(BookshelfStyle())
        val state = BookshelfStyleState(snapshot, backgroundScope)
        state.submit(BookshelfStyle(gridCoverWidth = 99))
        snapshot.value = BookshelfStyle(gridCoverWidth = 50)
        assertEquals(99, state.style.first().gridCoverWidth)
    }
}
