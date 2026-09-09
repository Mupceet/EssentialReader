package io.legado.app.eink.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快照默认值必须与宿主 BookshelfSettings 的字段默认值语义一致
 * （showUnread=true、showUnreadNew=true、bookshelfShowLatestChapter=true、
 * bookshelfLayoutModePortrait=1 网格、bookshelfLayoutGridPortrait=3），
 * 同时等于当前 eink 书架的硬编码行为——插件宿主发射默认快照即现状。
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
