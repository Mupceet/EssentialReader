package io.legado.app.eink.bridge

import io.legado.app.domain.model.settings.BookshelfSettings
import io.legado.app.eink.contract.BookshelfStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BookshelfSettings → BookshelfStyle 策划投影逐字段验证（设计 §4/§5）：
 * 投影键的对应关系、布局模式 0/非 0 语义、gridCoverWidth 非法值钳制、
 * 布局切换反向写投影。
 */
class BookshelfStyleMapperTest {

    @Test
    fun `各字段按宿主键投影`() {
        val style = BookshelfSettings(
            showUnread = true,
            showUnreadNew = false,
            bookshelfShowLatestChapter = false,
            bookshelfLayoutModePortrait = 0,
            bookshelfGridCoverWidth = 5,
        ).toBookshelfStyle()

        assertTrue(style.showUnreadBadge)
        assertFalse(style.highlightNewChapter)
        assertFalse(style.showLatestChapter)
        assertFalse(style.isGridLayout)
        assertEquals(5, style.gridCoverWidth)
    }

    @Test
    fun `布局模式非 0 为网格`() {
        val style = BookshelfSettings(bookshelfLayoutModePortrait = 1).toBookshelfStyle()
        assertTrue(style.isGridLayout)
    }

    @Test
    fun `封面宽非正回落 120`() {
        assertEquals(120, BookshelfSettings(bookshelfGridCoverWidth = 0).toBookshelfStyle().gridCoverWidth)
        assertEquals(120, BookshelfSettings(bookshelfGridCoverWidth = -2).toBookshelfStyle().gridCoverWidth)
    }

    @Test
    fun `默认设置投影等于契约默认快照`() {
        val style = BookshelfSettings().toBookshelfStyle()
        assertEquals(BookshelfStyle(), style)
    }

    @Test
    fun `布局切换反向写只动竖屏键`() {
        val base = BookshelfSettings(bookshelfSort = 2, bookshelfLayoutModePortrait = 1)
        val toList = base.withGridLayout(false)
        assertEquals(0, toList.bookshelfLayoutModePortrait)
        assertEquals(2, toList.bookshelfSort)
        assertEquals(1, base.withGridLayout(true).bookshelfLayoutModePortrait)
    }
}
