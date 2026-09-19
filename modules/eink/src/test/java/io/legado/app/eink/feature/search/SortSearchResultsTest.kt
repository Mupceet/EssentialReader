package io.legado.app.eink.feature.search

import io.legado.app.eink.contract.SearchBookUiModel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 与主搜索页 sortedWithSearchPriority 同语义：
 * 精确命中 → 标签命中 → 包含命中 → 其他；前三桶按 origins 数降序稳定排序，
 * 其他桶保持首见顺序。
 */
class SortSearchResultsTest {

    @Test
    fun `bucket priority equal then tags then contains then other`() {
        val contains = searchBook(name = "修真四万年后传")
        val other = searchBook(name = "大道朝天")
        val equal = searchBook(name = "修真四万年")
        val tags = searchBook(
            name = "大道朝天",
            kind = "修真四万年,东方玄幻",
        )

        val sorted = sortSearchResults(
            books = listOf(contains, other, equal, tags),
            keyword = "修真四万年",
        )

        assertEquals(listOf(equal, tags, contains, other), sorted)
    }

    @Test
    fun `within bucket higher origins count first and ties keep first-seen order`() {
        val low = searchBook(name = "修真四万年外传", originsCount = 1)
        val wide = searchBook(name = "修真四万年同人", originsCount = 5)
        val tieA = searchBook(name = "新修真四万年", originsCount = 3)
        val tieB = searchBook(name = "修真四万年别传", originsCount = 3)

        val sorted = sortSearchResults(
            books = listOf(low, wide, tieA, tieB),
            keyword = "修真四万年",
        )

        assertEquals(listOf(wide, tieA, tieB, low), sorted)
    }

    @Test
    fun `author hit shares the equal bucket with name hit`() {
        val byAuthor = searchBook(name = "别的书", author = "修真四万年")
        val byName = searchBook(name = "修真四万年", originsCount = 2)

        val sorted = sortSearchResults(listOf(byAuthor, byName), keyword = "修真四万年")

        assertEquals(listOf(byName, byAuthor), sorted)
    }

    @Test
    fun `other bucket keeps insertion order regardless of origins count`() {
        val lowOther = searchBook(name = "凡人修仙传", originsCount = 1)
        val highOther = searchBook(name = "遮天", originsCount = 9)

        val sorted = sortSearchResults(listOf(lowOther, highOther), keyword = "修真四万年")

        assertEquals(listOf(lowOther, highOther), sorted)
    }

    private fun searchBook(
        name: String,
        author: String = "卧牛真人",
        kind: String? = null,
        originsCount: Int = 1,
    ): SearchBookUiModel {
        return SearchBookUiModel(
            bookUrl = "https://example.com/book/$name/$originsCount",
            name = name,
            author = author,
            coverUrl = null,
            intro = "",
            latestChapterTitle = null,
            origin = "https://example.com",
            originName = "示例源",
            kind = kind,
            originsCount = originsCount,
        )
    }
}
