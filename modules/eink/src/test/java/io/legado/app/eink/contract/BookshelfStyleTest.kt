package io.legado.app.eink.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快照默认值必须与宿主 BookshelfSettings 的字段默认值语义一致
 * （showUnread=true、showUnreadNew=true、bookshelfShowLatestChapter=true、
 * bookshelfLayoutModePortrait=1 网格、bookshelfLayoutGridPortrait=3）。
 *
 * 注意「默认 3 列」与旧 Adaptive(96dp) 在宽屏设备（617dp 7 英寸墨水屏）
 * 的 4-5 列不同：列数跟随宿主设置是既定设计决策（宿主完整模式同样
 * 默认 3 列），非回归。
 */
class BookshelfStyleTest {

    @Test
    fun `默认值与宿主书架设置默认值对齐`() {
        val style = BookshelfStyle()
        assertTrue(style.showUnreadBadge)
        assertTrue(style.highlightNewChapter)
        assertTrue(style.showLatestChapter)
        assertTrue(style.isGridLayout)
        assertEquals(3, style.gridColumns)
    }
}
