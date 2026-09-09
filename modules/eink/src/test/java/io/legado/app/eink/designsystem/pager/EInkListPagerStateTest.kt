package io.legado.app.eink.designsystem.pager

import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 列表固定页分页的纯状态机：页首等差序列、尾页截断、未测量禁翻、
 * 数据变化后的页首对齐。
 * 滚动调度在测试 JVM 上无布局，翻页只验证
 * pageStart 数学（layoutInfo 为空时 scrollToPageStart 安全跳过）。
 */
class EInkListPagerStateTest {

    private fun state(pageStart: Int, pageItemCount: Int): EInkListPagerState =
        EInkListPagerState(LazyListState()).apply { restorePaging(pageStart, pageItemCount) }

    @Test
    fun `翻页可用性按页项数判定`() = runTest {
        val state = state(pageStart = 0, pageItemCount = 5)
        assertFalse(state.canPageUp())
        assertFalse("单页数据时不允许下翻", state.canPageDown(totalItems = 5))
        assertTrue("尾页允许不满一页", state.canPageDown(totalItems = 6))
        assertTrue(state.canPageDown(totalItems = 10))
        assertFalse("未测量前禁止翻页", EInkListPagerState(LazyListState()).canPageDown(totalItems = 100))
    }

    @Test
    fun `pageDown 整页前进且尾页截断`() = runTest {
        val state = state(pageStart = 0, pageItemCount = 5)
        state.pageDown(totalItems = 11)
        assertEquals(5, state.pageStart)
        state.pageDown(totalItems = 11)
        assertEquals("最后一页从 10 起（第 11 项单独成页）", 10, state.pageStart)
        state.pageDown(totalItems = 11)
        assertEquals("到底后不再移动", 10, state.pageStart)
    }

    @Test
    fun `pageUp 整页后退且首页不动`() = runTest {
        val state = state(pageStart = 10, pageItemCount = 5)
        state.pageUp()
        assertEquals(5, state.pageStart)
        state.pageUp()
        assertEquals(0, state.pageStart)
        state.pageUp()
        assertEquals(0, state.pageStart)
    }

    @Test
    fun `jumpToItemAligned 对齐完整页边界`() = runTest {
        val state = state(pageStart = 10, pageItemCount = 5)
        state.jumpToItemAligned(index = 7)
        assertEquals(5, state.pageStart)
        state.jumpToItemAligned(index = -3)
        assertEquals(0, state.pageStart)
    }

    @Test
    fun `resetPaging 归零不依赖测量`() {
        val state = state(pageStart = 10, pageItemCount = 5)
        state.resetPaging()
        assertEquals(0, state.pageStart)
    }

    @Test
    fun `realignToPageStart 数据缩短时收敛到最后一个完整页起点`() = runTest {
        val state = state(pageStart = 10, pageItemCount = 5)
        state.realignToPageStart(totalItems = 12)
        assertEquals("12 项数据的最后一个完整页起点仍为 10", 10, state.pageStart)
        state.realignToPageStart(totalItems = 9)
        assertEquals(5, state.pageStart)
        state.realignToPageStart(totalItems = 0)
        assertEquals("数据清空回第一页", 0, state.pageStart)
    }

    @Test
    fun `realignToPageStart 未测量时不动`() = runTest {
        val state = EInkListPagerState(LazyListState())
        state.realignToPageStart(totalItems = 3)
        assertEquals(0, state.pageStart)
    }

    @Test
    fun `measureOnFirstLayout 已恢复过分页时跳过测量`() = runTest {
        val state = state(pageStart = 3, pageItemCount = 7)
        state.measureOnFirstLayout()
        assertEquals(3, state.pageStart)
        assertEquals(7, state.pageItemCount)
    }
}
