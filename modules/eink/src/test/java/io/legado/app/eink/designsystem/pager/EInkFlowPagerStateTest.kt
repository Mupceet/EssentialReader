package io.legado.app.eink.designsystem.pager

import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 变高列表分页：页首按布局实测推进（不裁半截、不漏条目）、章节头与卡片
 * 同页、跳转重起页序列。滚动调度在测试 JVM 上无布局，翻页只验证页首
 * 数学（layoutInfo 为空时安全跳过，同 EInkListPagerState 先例）。
 */
class EInkFlowPagerStateTest {

    private fun span(index: Int, offset: Int, size: Int) =
        EInkFlowItemSpan(index = index, offset = offset, size = size)

    @Test
    fun `下一页页首取最后一条完整可见条目之后`() {
        // 章节头 40 + 卡片 120 + 卡片 80；视口下沿 210 → 第三条只露出一半
        val items = listOf(span(0, 0, 40), span(1, 40, 120), span(2, 160, 80))
        assertEquals(2, nextFlowPageStart(items, viewportEndOffset = 210))
        // 恰好放得下 → 推进到下一条
        assertEquals(3, nextFlowPageStart(items, viewportEndOffset = 240))
    }

    @Test
    fun `页底是章节头时整组留给下一页`() {
        val items = listOf(span(0, 0, 40), span(1, 40, 120), span(2, 160, 80))
        // 视口 240 时最后完整可见是章节头（index 2）→ 回退一条，下一页从章节头起
        assertEquals(
            2,
            nextFlowPageStart(items, viewportEndOffset = 240, keepWithNext = { it == 2 }),
        )
    }

    @Test
    fun `单条超过视口时推进到下一条且空列表返回null`() {
        // 唯一可见条目比视口高（整屏一张长卡片）：下一页从下一条开始，避免卡死
        assertEquals(1, nextFlowPageStart(listOf(span(0, 0, 300)), viewportEndOffset = 200))
        assertNull(nextFlowPageStart(emptyList(), viewportEndOffset = 200))
    }

    @Test
    fun `未测量时不可下翻且翻页不移动`() = runTest {
        val state = EInkFlowPagerState(LazyListState())
        assertFalse(state.canPageDown(totalItems = 10))
        state.pageDown(totalItems = 10)
        assertEquals(0, state.pageStart)
        assertFalse(state.canPageUp())
    }

    @Test
    fun `上一页弹回记录页首跳转后重起页序列`() = runTest {
        val state = EInkFlowPagerState(LazyListState())
        // 前进过两页（页首栈 [0, 2]）
        state.restorePaging(pageStart = 5, visited = listOf(0, 2))
        assertTrue(state.canPageUp())
        state.pageUp()
        assertEquals(2, state.pageStart)
        state.pageUp()
        assertEquals(0, state.pageStart)
        assertFalse(state.canPageUp())

        // 跳转（回到当前/去底部）：页首对齐目标条并清空历史
        state.jumpToItem(index = 9, totalItems = 12)
        assertEquals(9, state.pageStart)
        assertTrue("跳转后仍可回退（退一条）", state.canPageUp())
        state.pageUp()
        assertEquals(8, state.pageStart)
    }

    @Test
    fun `数据缩短后页首收敛到末条`() = runTest {
        val state = EInkFlowPagerState(LazyListState())
        state.restorePaging(pageStart = 9, visited = emptyList())
        state.realignToPageStart(totalItems = 3)
        assertEquals(2, state.pageStart)
        state.realignToPageStart(totalItems = 0)
        assertEquals(0, state.pageStart)
    }
}
