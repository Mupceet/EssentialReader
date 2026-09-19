package io.legado.app.eink.feature.bookshelf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 角标组合规则（语义对齐 View 版 BookItem：unreadText 受 showUnread 门控、
 * showUpdateBadge = showUnread && showUnreadNew && isNew）。
 */
class BookshelfBadgeRuleTest {

    @Test
    fun `刷新中显示省略号且优先于未读数`() {
        assertEquals("…", shelfBadgeText(isUpdating = true, showUnreadBadge = true, unreadCount = 5))
        assertEquals("…", shelfBadgeText(isUpdating = true, showUnreadBadge = false, unreadCount = 5))
    }

    @Test
    fun `未读角标受宿主开关门控`() {
        assertEquals("5", shelfBadgeText(false, true, 5))
        assertNull(shelfBadgeText(false, false, 5))
    }

    @Test
    fun `未读为 0 不显示角标`() {
        assertNull(shelfBadgeText(false, true, 0))
    }

    @Test
    fun `高亮需角标可见且开关开启且发现新章`() {
        assertTrue(shelfBadgeHighlight(false, true, true, true))
        assertFalse(shelfBadgeHighlight(false, true, false, true))
        assertFalse(shelfBadgeHighlight(false, false, true, true))
        assertFalse(shelfBadgeHighlight(false, true, true, false))
        assertFalse(shelfBadgeHighlight(true, true, true, true))
    }
}
