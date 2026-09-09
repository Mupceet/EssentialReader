package io.legado.app.eink.bridge

import io.legado.app.domain.model.settings.BookshelfSettings
import io.legado.app.eink.contract.BookshelfStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BookshelfSettings → BookshelfStyle 策划投影逐字段验证（设计 §4/§5）：
 * 投影键的对应关系、布局模式 0/非 0 语义、gridColumns 非法值钳制。
 */
class BookshelfStyleMapperTest {

    @Test
    fun `各字段按宿主键投影`() {
        val style = BookshelfSettings(
            showUnread = true,
            showUnreadNew = false,
            bookshelfShowLatestChapter = false,
            bookshelfLayoutModePortrait = 0,
            bookshelfLayoutGridPortrait = 5,
        ).toBookshelfStyle()

        assertTrue(style.showUnreadBadge)
        assertFalse(style.highlightNewChapter)
        assertFalse(style.showLatestChapter)
        assertFalse(style.isGridLayout)
        assertEquals(5, style.gridColumns)
    }

    @Test
    fun `布局模式非 0 为网格`() {
        val style = BookshelfSettings(bookshelfLayoutModePortrait = 1).toBookshelfStyle()
        assertTrue(style.isGridLayout)
    }

    @Test
    fun `网格列数非正回落 3`() {
        assertEquals(3, BookshelfSettings(bookshelfLayoutGridPortrait = 0).toBookshelfStyle().gridColumns)
        assertEquals(3, BookshelfSettings(bookshelfLayoutGridPortrait = -2).toBookshelfStyle().gridColumns)
    }

    @Test
    fun `默认设置投影等于契约默认快照`() {
        val style = BookshelfSettings().toBookshelfStyle()
        assertEquals(BookshelfStyle(), style)
    }
}
