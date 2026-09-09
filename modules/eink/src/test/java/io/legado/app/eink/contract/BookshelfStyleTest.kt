package io.legado.app.eink.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快照默认值必须与宿主 BookshelfSettings 的字段默认值语义一致
 * （showUnread=true、showUnreadNew=true、bookshelfShowLatestChapter=true、
 * bookshelfLayoutModePortrait=1 网格、bookshelfGridCoverWidth=120）。
 *
 * 注意「默认 120dp 封面宽」在手机竖屏（360dp）推导 2 列、七英寸墨水屏
 * （617dp）推导 4 列，与旧固定 3 列/Adaptive(96dp) 观感不同：列宽主导
 * 是既定设计修订（列数由模块按可用宽推导），非回归。
 */
class BookshelfStyleTest {

    @Test
    fun `默认值与宿主书架设置默认值对齐`() {
        val style = BookshelfStyle()
        assertTrue(style.showUnreadBadge)
        assertTrue(style.highlightNewChapter)
        assertTrue(style.showLatestChapter)
        assertTrue(style.isGridLayout)
        assertEquals(120, style.gridCoverWidth)
    }
}
