package io.legado.app.eink.feature.search

import io.legado.app.eink.contract.SearchBookUiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SearchResultsMergeTest {

    @Test
    fun `duplicate entries within one source delta collapse to single item`() {
        // 七猫源单页返回同名同作者两条 -> UseCase merger 的 upsertBooks
        // 含同一本书两次（origin + bookUrl 完全相同，bookUrl 带 headers 后缀）
        val book = searchBook(
            bookUrl = "https://api-bc.wtzw.com/api/v4/book/detail?&id=1913611" +
                "&sign=05d016b5491762abd86674de32b84e67," +
                """{"headers":{"app-version":"51110","platform":"android"}}""",
            origin = "https://api-bc.wtzw.com",
        )

        val merged = mergeSearchResults(current = emptyList(), delta = listOf(book, book.copy()))

        assertEquals(listOf(book), merged)
    }

    @Test
    fun `repeated upsert of same book overwrites in place keeping first position`() {
        val first = searchBook(bookUrl = "https://a.com/book/1", origin = "https://a.com")
        val other = searchBook(bookUrl = "https://a.com/book/2", origin = "https://a.com")
        val refreshed = first.copy(latestChapterTitle = "新章节")

        val merged = mergeSearchResults(
            current = listOf(first, other),
            delta = listOf(refreshed),
        )

        assertEquals(listOf(refreshed, other), merged)
        assertSame(refreshed, merged.first())
    }

    @Test
    fun `distinct books append after existing results in first-seen order`() {
        val first = searchBook(bookUrl = "https://a.com/book/1", origin = "https://a.com")
        val second = searchBook(bookUrl = "https://b.com/book/2", origin = "https://b.com")
        val third = searchBook(bookUrl = "https://c.com/book/3", origin = "https://c.com")

        val merged = mergeSearchResults(
            current = listOf(first),
            delta = listOf(second, third),
        )

        assertEquals(listOf(first, second, third), merged)
    }

    @Test
    fun `same book from another origin stays a separate entry`() {
        // 身份键为 origin-bookUrl，跨源条目各自展示
        val fromA = searchBook(bookUrl = "https://a.com/book/1", origin = "https://a.com")
        val fromB = searchBook(bookUrl = "https://b.com/book/1", origin = "https://b.com")

        val merged = mergeSearchResults(current = listOf(fromA), delta = listOf(fromB))

        assertEquals(listOf(fromA, fromB), merged)
    }

    private fun searchBook(
        bookUrl: String,
        origin: String,
        name: String = "修真四万年",
        author: String = "卧牛真人",
    ) = SearchBookUiModel(
        bookUrl = bookUrl,
        name = name,
        author = author,
        coverUrl = null,
        intro = "",
        latestChapterTitle = null,
        origin = origin,
        originName = origin,
    )
}
