package io.legado.app.eink.bridge

import io.legado.app.data.entities.Book
import io.legado.app.utils.cnCompare
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 六模式 × 升降序对拍 View 版 BookshelfRepository.sortBooks 语义
 * （设计 §3.2）：手动排序显式按 order（不依赖 DAO 自然序）、
 * 未知 sort 回落阅读时间、升降序翻转。
 */
class BookshelfSorterTest {

    private fun book(
        name: String = "",
        author: String = "",
        dur: Long = 0,
        latest: Long = 0,
        order: Int = 0,
    ) = Book(
        name = name,
        author = author,
        durChapterTime = dur,
        latestChapterTime = latest,
        order = order,
    )

    private fun urls(books: List<Book>) = books.map { it.name }

    @Test
    fun `模式 0 与未知值按阅读时间排序`() {
        val books = listOf(book(name = "a", dur = 1), book(name = "b", dur = 3), book(name = "c", dur = 2))
        assertEquals(listOf("a", "c", "b"), urls(books.sortedForBookshelf(0, 0)))
        assertEquals(listOf("b", "c", "a"), urls(books.sortedForBookshelf(0, 1)))
        assertEquals(listOf("b", "c", "a"), urls(books.sortedForBookshelf(9, 1)))
    }

    @Test
    fun `模式 1 按更新时间排序`() {
        val books = listOf(book(name = "a", latest = 1), book(name = "b", latest = 3), book(name = "c", latest = 2))
        assertEquals(listOf("b", "c", "a"), urls(books.sortedForBookshelf(1, 1)))
    }

    @Test
    fun `模式 2 按书名 cnCompare 排序`() {
        val books = listOf(book(name = "B"), book(name = "A"), book(name = "C"))
        assertEquals(listOf("A", "B", "C"), urls(books.sortedForBookshelf(2, 0)))
        assertEquals(listOf("C", "B", "A"), urls(books.sortedForBookshelf(2, 1)))
    }

    @Test
    fun `模式 3 手动排序显式按 order`() {
        // DAO flowAll 自然序是 durChapterTime desc；dur 与 order 交叉
        // 证明排序跟随 order 而非自然序
        val books = listOf(
            book(name = "a", dur = 30, order = 2),
            book(name = "b", dur = 20, order = 0),
            book(name = "c", dur = 10, order = 1),
        )
        assertEquals(listOf("b", "c", "a"), urls(books.sortedForBookshelf(3, 0)))
        assertEquals(listOf("a", "c", "b"), urls(books.sortedForBookshelf(3, 1)))
    }

    @Test
    fun `模式 4 按更新与阅读时间的较大值排序`() {
        val books = listOf(
            book(name = "a", dur = 5, latest = 1),
            book(name = "b", dur = 1, latest = 9),
            book(name = "c", dur = 3, latest = 3),
        )
        // max 键：a=5（取 dur）、b=9（取 latest）、c=3，降序为 [b, a, c]
        // （a 靠 dur 压过 c，证明确是 max 而非 latest 单键；对拍 View 版 sortBooks 模式 4）
        assertEquals(listOf("b", "a", "c"), urls(books.sortedForBookshelf(4, 1)))
    }

    @Test
    fun `模式 5 按作者 cnCompare 排序`() {
        val books = listOf(book(name = "1", author = "B"), book(name = "2", author = "A"))
        assertEquals(listOf("2", "1"), urls(books.sortedForBookshelf(5, 0)))
    }

    @Test
    fun `书名比较器与 cnCompare 同源`() {
        // 中文书名在前（collator 语义由宿主 cnCompare 定义，此处锁定装配一致）
        val books = listOf(book(name = "英文"), book(name = "啊"))
        val expected = books.sortedWith { o1, o2 -> o1.name.cnCompare(o2.name) }
        assertEquals(expected, books.sortedForBookshelf(2, 0))
    }
}
