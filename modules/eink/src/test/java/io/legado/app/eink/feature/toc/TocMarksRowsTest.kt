package io.legado.app.eink.feature.toc

import io.legado.app.eink.contract.BookmarkUiModel
import io.legado.app.eink.contract.MarkingUiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** 书签/笔记 Tab 的章节聚合与卡片时间显示（纯函数）。 */
class TocMarksRowsTest {

    private fun bookmark(chapter: Int, name: String, id: Long) = BookmarkUiModel(
        id = id, chapterIndex = chapter, chapterName = name,
        bookText = "摘录$id", content = "",
    )

    private fun marking(chapter: Int, name: String, id: String, thought: Boolean) = MarkingUiModel(
        id = id, chapterIndex = chapter, chapterName = name,
        selectedText = "划线$id", note = if (thought) "想法$id" else "",
        thought = thought, createdAt = 1_700_000_000_000L,
    )

    @Test
    fun `书签按章节聚合为章节头加卡片行`() {
        val rows = bookmarkRows(
            listOf(
                bookmark(2, "第二章", 1),
                bookmark(2, "第二章", 2),
                bookmark(5, "第五章", 3),
            ),
        )
        assertEquals(5, rows.size)
        val header0 = rows[0] as TocMarkRow.ChapterHeader
        assertEquals(2, header0.chapterIndex)
        assertEquals("第二章", header0.chapterName)
        assertEquals(2, header0.itemCount)
        assertTrue(rows[1] is TocMarkRow.Bookmark)
        assertTrue(rows[2] is TocMarkRow.Bookmark)
        val header1 = rows[3] as TocMarkRow.ChapterHeader
        assertEquals(5, header1.chapterIndex)
        assertEquals(1, header1.itemCount)
        assertTrue(rows[4] is TocMarkRow.Bookmark)
    }

    @Test
    fun `笔记按章节聚合且保持组内顺序`() {
        val rows = markingRows(
            listOf(
                marking(1, "第一章", "a", thought = false),
                marking(1, "第一章", "b", thought = true),
                marking(3, "第三章", "c", thought = false),
            ),
        )
        assertEquals(5, rows.size)
        assertEquals(
            listOf("a", "b", "c"),
            rows.filterIsInstance<TocMarkRow.Marking>().map { it.marking.id },
        )
        assertEquals(2, (rows[0] as TocMarkRow.ChapterHeader).itemCount)
        assertEquals(1, (rows[3] as TocMarkRow.ChapterHeader).itemCount)
    }

    @Test
    fun `空章名回落占位且空列表无行`() {
        val rows = bookmarkRows(listOf(bookmark(3, "", 1)))
        assertEquals("第 4 章", (rows[0] as TocMarkRow.ChapterHeader).chapterName)
        assertTrue(bookmarkRows(emptyList()).isEmpty())
        assertTrue(markingRows(emptyList()).isEmpty())
    }

    @Test
    fun `回到当前定位章节头行`() {
        val rows = bookmarkRows(
            listOf(bookmark(2, "第二章", 1), bookmark(5, "第五章", 2)),
        )
        assertEquals(0, currentChapterRowIndex(rows, currentChapterIndex = 2))
        assertEquals(2, currentChapterRowIndex(rows, currentChapterIndex = 5))
        assertNull(currentChapterRowIndex(rows, currentChapterIndex = 9))
    }

    @Test
    fun `卡片时间格式化与脏值过滤`() {
        // 时间格式化按默认时区显示：测试内固定时区，避免跟随运行环境漂移
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            val now = 1_760_000_000_000L
            assertEquals(
                "2023-11-15 06:13",
                formatMarkTime(1_700_000_000_000L, nowMillis = now),
            )
            assertNull("早于 2000 视为脏数据", formatMarkTime(0L, nowMillis = now))
            assertNull("明显未来视为脏数据", formatMarkTime(now + 10L * 86_400_000L, nowMillis = now))
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
