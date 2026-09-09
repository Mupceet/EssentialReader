package io.legado.app.eink.designsystem.pager

import androidx.compose.foundation.lazy.grid.LazyGridState
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网格固定页分页的纯状态机（与列表版同一套整页跳转模型）：
 * 页首等差序列、尾页截断、未测量禁翻、数据变化后的页首对齐、
 * 恢复后跳过测量、几何变化后 remeasure 的同步清零。
 */
class EInkGridPagerStateTest {

    private fun state(pageStart: Int, pageItemCount: Int): EInkGridPagerState =
        EInkGridPagerState(LazyGridState()).apply { restorePaging(pageStart, pageItemCount) }

    @Test
    fun `翻页可用性按页项数判定`() = runTest {
        val state = state(pageStart = 0, pageItemCount = 12)
        assertFalse(state.canPageUp())
        assertFalse("单页数据时不允许下翻", state.canPageDown(totalItems = 12))
        assertTrue("尾页允许不满一页", state.canPageDown(totalItems = 13))
        assertTrue(state.canPageDown(totalItems = 24))
        assertFalse("未测量前禁止翻页", EInkGridPagerState(LazyGridState()).canPageDown(totalItems = 100))
    }

    @Test
    fun `pageDown 整页前进且尾页截断`() = runTest {
        val state = state(pageStart = 0, pageItemCount = 12)
        state.pageDown(totalItems = 30)
        assertEquals(12, state.pageStart)
        state.pageDown(totalItems = 30)
        assertEquals("尾页从 24 起（6 项不满一行）", 24, state.pageStart)
        state.pageDown(totalItems = 30)
        assertEquals(24, state.pageStart)
    }

    @Test
    fun `pageUp 整页后退且首页不动`() = runTest {
        val state = state(pageStart = 12, pageItemCount = 12)
        state.pageUp()
        assertEquals(0, state.pageStart)
        state.pageUp()
        assertEquals(0, state.pageStart)
    }

    @Test
    fun `realignToPageStart 数据缩短时收敛到最后一个完整页起点`() = runTest {
        val state = state(pageStart = 24, pageItemCount = 12)
        state.realignToPageStart(totalItems = 30)
        assertEquals(24, state.pageStart)
        state.realignToPageStart(totalItems = 20)
        assertEquals("20 项数据的最后页首为 12", 12, state.pageStart)
        state.realignToPageStart(totalItems = 0)
        assertEquals(0, state.pageStart)
    }

    @Test
    fun `measureOnFirstLayout 已恢复过分页时跳过测量`() = runTest {
        val state = state(pageStart = 12, pageItemCount = 12)
        state.measureOnFirstLayout()
        assertEquals(12, state.pageStart)
        assertEquals(12, state.pageItemCount)
    }

    @Test
    fun `remeasure 重置页首并作废旧页项数等待重测`() = runTest {
        // 几何变化（旋转/分屏改变列数）后：旧 pageStart/旧 pageItemCount
        // 必须同步失效。清零后的重新实测（measureOnFirstLayout 经 snapshotFlow
        // 等待首个布局帧）在纯 JVM 测试上无布局到达、按设计挂起，用短超时
        // 界定挂起段——生产中由布局到达完成计数（重入首次布局的同一套计数逻辑）。
        val state = state(pageStart = 24, pageItemCount = 12)
        withTimeoutOrNull(1_000) { state.remeasure() }
        assertEquals("页首拉回第一页", 0, state.pageStart)
        assertEquals("旧页项数作废，等待按当前布局重测", 0, state.pageItemCount)
    }
}
