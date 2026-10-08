package io.legado.app.eink.bridge

import io.legado.app.domain.model.settings.BookshelfSettings
import io.legado.app.eink.contract.BookshelfStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BookshelfSettings → BookshelfStyle 策划投影逐字段验证（设计 §4/§5）：
 * 投影键的对应关系、布局模式 0/非 0 语义、gridCoverWidth/titleMaxLines
 * 非法值回落、样式快照反向写投影六键。
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
            bookshelfTitleMaxLines = 4,
        ).toBookshelfStyle()

        assertTrue(style.showUnreadBadge)
        assertFalse(style.highlightNewChapter)
        assertFalse(style.showLatestChapter)
        assertFalse(style.isGridLayout)
        assertEquals(5, style.gridCoverWidth)
        assertEquals(4, style.titleMaxLines)
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
    fun `标题行数越界回落 2`() {
        assertEquals(2, BookshelfSettings(bookshelfTitleMaxLines = 0).toBookshelfStyle().titleMaxLines)
        assertEquals(2, BookshelfSettings(bookshelfTitleMaxLines = 6).toBookshelfStyle().titleMaxLines)
    }

    @Test
    fun `样式快照反向写投影六键`() {
        val style = BookshelfStyle(
            showUnreadBadge = false,
            highlightNewChapter = false,
            showLatestChapter = false,
            isGridLayout = false,
            gridCoverWidth = 88,
            titleMaxLines = 3,
        )
        val settings = BookshelfSettings(
            bookshelfSort = 2,
            bookshelfLayoutModeLandscape = 1,
        ).withStyleProjection(style)

        assertFalse(settings.showUnread)
        assertFalse(settings.showUnreadNew)
        assertFalse(settings.bookshelfShowLatestChapter)
        assertEquals(0, settings.bookshelfLayoutModePortrait)
        assertEquals(88, settings.bookshelfGridCoverWidth)
        assertEquals(3, settings.bookshelfTitleMaxLines)
        // 非本通道键不受影响：排序键与横屏键保持原值
        assertEquals(2, settings.bookshelfSort)
        assertEquals(1, settings.bookshelfLayoutModeLandscape)
    }

    @Test
    fun `样式快照反向写网格分支`() {
        val settings = BookshelfSettings(bookshelfLayoutModePortrait = 0)
            .withStyleProjection(BookshelfStyle(isGridLayout = true))
        assertEquals(1, settings.bookshelfLayoutModePortrait)
    }

    @Test
    fun `标题小字体与对齐配置主动忽略`() {
        val base = BookshelfSettings(
            bookshelfTitleSmallFont = false,
            bookshelfTitleCenter = true,
        )
        val changed = base.copy(
            bookshelfTitleSmallFont = true,
            bookshelfTitleCenter = false,
        )
        assertEquals(base.toBookshelfStyle(), changed.toBookshelfStyle())
    }

    @Test
    fun `默认设置投影等于契约默认快照`() {
        val style = BookshelfSettings().toBookshelfStyle()
        assertEquals(BookshelfStyle(), style)
    }
}
