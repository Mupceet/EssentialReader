package io.legado.app.eink.designsystem.pager

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
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
 *
 * [settledPageCount] 是纯布局判定函数，用 [FakeLayoutInfo] 直接锚定
 * 「视口填满」各形态的落定值——含整除填满盲区回归（末项不被截断、
 * 可见项全部完整且其后仍有项；真机：界面字体 A + 1.1 缩放目录空白）。
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

    // ==================================================================
    // settledPageCount：视口填满两形态的落定判定（纯函数，fake 布局）
    // ==================================================================

    @Test
    fun `末项被截断时按完整项数落定`() {
        // 16 项可见（等高 43px），末项底边越过视口底 23px
        val info = layoutInfo(rowHeight = 43, rows = 16, overhang = 23, total = 500)
        assertEquals(15, settledPageCount(info))
    }

    @Test
    fun `整除填满时同样落定——可见项全完整且其后仍有项`() {
        // 16 行恰好填满视口（16×43=688），下一项从视口底边起不进可见集；
        // 修复前该形态永不落定：pageItemCount=0 无法翻页，遮盖常驻整页空白
        val info = layoutInfo(rowHeight = 43, rows = 16, overhang = 0, total = 500)
        assertEquals(16, settledPageCount(info))
    }

    @Test
    fun `单页恰好装满时不落定`() {
        val info = layoutInfo(rowHeight = 43, rows = 16, overhang = 0, total = 16)
        assertEquals(0, settledPageCount(info))
    }

    @Test
    fun `单页未满时不落定`() {
        val info = layoutInfo(rowHeight = 43, rows = 5, overhang = 0, total = 5)
        assertEquals(0, settledPageCount(info))
    }

    @Test
    fun `首项即被截断时不落定`() {
        // 退化布局（异常字体度量撑爆行高）：count=0，交由单页放行分支处理
        val info = layoutInfo(rowHeight = 1000, rows = 1, overhang = 400, total = 500)
        assertEquals(0, settledPageCount(info))
    }

    @Test
    fun `末项截断且为列表最后一项时按完整项数落定`() {
        // 一页装不下 4 项、第 4 项截断：页大小 = 前 3 项（尾页不满一页）
        val info = layoutInfo(rowHeight = 43, rows = 4, overhang = 10, total = 4)
        assertEquals(3, settledPageCount(info))
    }
}

/** 等高连续行布局：rows 行自 offset 0 排开，末行越过视口底 [overhang]px。 */
private fun layoutInfo(rowHeight: Int, rows: Int, overhang: Int, total: Int): LazyListLayoutInfo =
    FakeLayoutInfo(
        visibleItemsInfo = (0 until rows).map { index ->
            FakeItemInfo(index = index, offset = index * rowHeight, size = rowHeight)
        },
        viewportStartOffset = 0,
        viewportEndOffset = rows * rowHeight - overhang,
        totalItemsCount = total,
    )

private class FakeItemInfo(
    override val index: Int,
    override val offset: Int,
    override val size: Int,
) : LazyListItemInfo {
    override val key: Any get() = index
}

private class FakeLayoutInfo(
    override val visibleItemsInfo: List<LazyListItemInfo>,
    override val viewportStartOffset: Int,
    override val viewportEndOffset: Int,
    override val totalItemsCount: Int,
) : LazyListLayoutInfo
