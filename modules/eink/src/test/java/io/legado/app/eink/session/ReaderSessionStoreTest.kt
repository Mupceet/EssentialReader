package io.legado.app.eink.session

import io.legado.app.eink.contract.BookmarkUiModel
import io.legado.app.eink.contract.ChapterUiModel
import io.legado.app.eink.contract.MarkingUiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话缓存状态机：单会话持有、换书替换、迟到推送丢弃、停止清空。
 * （端口订阅与调度在 [ReaderSessionCache]，此处只锚定纯逻辑。）
 */
class ReaderSessionStoreTest {

    private fun chapter(index: Int) = ChapterUiModel(
        index = index, title = "第${index + 1}章",
        url = "u$index", isVolume = false, fileName = "$index.txt",
    )

    private fun bookmark(id: Long) = BookmarkUiModel(
        id = id, chapterIndex = 0, chapterName = "第一章", bookText = "摘录$id", content = "",
    )

    private fun marking(id: String) = MarkingUiModel(
        id = id, chapterIndex = 0, chapterName = "第一章", chapterPos = 10,
        selectedText = "划线$id", note = "", thought = false, createdAt = 1L,
    )

    @Test
    fun `启动后会话快照可直读且默认列表为空`() {
        val store = ReaderSessionStore()
        assertNull("未启动无会话", store.snapshotValue())
        store.begin("book-a", marksAvailable = true)
        val session = store.snapshotValue()!!
        assertEquals("book-a", session.bookUrl)
        assertTrue(session.marksAvailable)
        assertNull("章节未就绪为 null（调用方可回落自加载）", session.chapters)
        assertTrue(session.bookmarks.isEmpty())
        assertTrue(session.markings.isEmpty())
        assertTrue(store.isActive("book-a"))
        assertFalse(store.isActive("book-b"))
    }

    @Test
    fun `目录书签笔记写入同一会话且停止后清空`() {
        val store = ReaderSessionStore()
        store.begin("book-a", marksAvailable = true)
        store.setChapters("book-a", listOf(chapter(0)))
        store.setBookmarks("book-a", listOf(bookmark(1)))
        store.setMarkings("book-a", listOf(marking("m1")))
        val session = store.snapshotValue()!!
        assertEquals(1, session.chapters!!.size)
        assertEquals(1, session.bookmarks.size)
        assertEquals(1, session.markings.size)

        store.clear()
        assertNull(store.snapshotValue())
        assertFalse(store.isActive("book-a"))
    }

    @Test
    fun `换书后旧订阅的迟到推送被丢弃`() {
        val store = ReaderSessionStore()
        store.begin("book-a", marksAvailable = true)
        store.setChapters("book-a", listOf(chapter(0)))

        // 换书：整体替换会话（旧书数据不残留）
        store.begin("book-b", marksAvailable = true)
        assertNull(store.snapshotValue()!!.chapters)

        // 旧书订阅在途的迟到推送不得污染新会话
        store.setChapters("book-a", listOf(chapter(9)))
        store.setBookmarks("book-a", listOf(bookmark(9)))
        assertNull(store.snapshotValue()!!.chapters)
        assertTrue(store.snapshotValue()!!.bookmarks.isEmpty())

        // 新会话自身的写入正常生效
        store.setChapters("book-b", listOf(chapter(1)))
        assertEquals(1, store.snapshotValue()!!.chapters!!.size)
    }

    private fun ReaderSessionStore.snapshotValue(): ReaderSessionSnapshot? = session.value
}
