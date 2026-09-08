package io.legado.app.eink.feature.search

import io.legado.app.eink.contract.SearchBookUiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索空态判定：仅在「已发起搜索 + 不在搜索中 + 无结果」三者同时成立
 * 时展示空页，搜索中与未搜索都不误报。
 */
class SearchUiStateTest {

    private fun book(name: String = "修真四万年") = SearchBookUiModel(
        bookUrl = "https://example.com/$name",
        name = name,
        author = "卧牛真人",
        coverUrl = null,
        intro = "",
        latestChapterTitle = null,
        origin = "https://example.com",
        originName = "示例源",
    )

    @Test
    fun `未搜索时不展示空态`() {
        assertFalse(SearchUiState().showEmpty)
        assertFalse(SearchUiState(isEmptyResult = true).showEmpty)
    }

    @Test
    fun `搜索中不展示空态`() {
        assertFalse(
            SearchUiState(searched = true, isSearching = true, isEmptyResult = true).showEmpty,
        )
    }

    @Test
    fun `搜索结束且无结果时展示空态`() {
        assertTrue(
            SearchUiState(searched = true, isSearching = false, isEmptyResult = true).showEmpty,
        )
    }

    @Test
    fun `有结果时不展示空态`() {
        assertFalse(
            SearchUiState(
                results = listOf(book()),
                searched = true,
                isSearching = false,
                isEmptyResult = false,
            ).showEmpty,
        )
    }

    @Test
    fun `结果条目身份键为源与地址拼接`() {
        assertEquals(
            "https://example.com-https://example.com/修真四万年",
            book().resultKey,
        )
    }
}
