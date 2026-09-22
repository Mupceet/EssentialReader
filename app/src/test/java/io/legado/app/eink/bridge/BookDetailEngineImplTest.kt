package io.legado.app.eink.bridge

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 详情页书籍解析查找链（findBookCandidate）优先级锚定：bookUrl 主键
 * 优先——书架同名同作者多记录（合法状态）时「长按哪个书进哪个详情」；
 * name+author 弱定位仅兜底无 url 身份场景。
 */
class BookDetailEngineImplTest {

    private fun book(url: String): Book = Book().apply { bookUrl = url }

    /** 各查找步的调用记录（顺序敏感：优先级颠倒即回归）。 */
    private class Steps {
        val calls = mutableListOf<String>()
        val byUrl: () -> Book? = { calls += "url"; null }
        val byUrlSearch: () -> Book? = { calls += "urlSearch"; null }
        val byNameAuthor: () -> Book? = { calls += "nameAuthor"; null }
        val bySearchNameAuthor: () -> Book? = { calls += "searchNameAuthor"; null }
    }

    @Test
    fun `bookUrl非空时优先精确解析且不触nameAuthor`() {
        val steps = Steps()
        val hit = book("url-1")
        val result = findBookCandidate(
            bookUrl = "url-1",
            byUrl = { steps.calls += "url"; hit },
            byUrlSearch = steps.byUrlSearch,
            byNameAuthor = steps.byNameAuthor,
            bySearchNameAuthor = steps.bySearchNameAuthor,
        )
        assertEquals(hit, result)
        assertEquals(listOf("url"), steps.calls)
    }

    @Test
    fun `book表未命中回退搜索记录再回退nameAuthor`() {
        val steps = Steps()
        val searchHit = book("url-2")
        val nameHit = book("url-3")
        val result = findBookCandidate(
            bookUrl = "url-2",
            byUrl = { steps.calls += "url"; null },
            byUrlSearch = { steps.calls += "urlSearch"; searchHit },
            byNameAuthor = steps.byNameAuthor,
            bySearchNameAuthor = steps.bySearchNameAuthor,
        )
        assertEquals(searchHit, result)
        assertEquals(listOf("url", "urlSearch"), steps.calls)

        // url 两表都落空 → 才轮到 name+author 弱定位（同名多条时此步固定
        // 命中 DAO 首行，歧义不可避免——但仅限无 url 身份场景）
        val fallback = findBookCandidate(
            bookUrl = "url-2",
            byUrl = { steps.calls += "url"; null },
            byUrlSearch = { steps.calls += "urlSearch"; null },
            byNameAuthor = { steps.calls += "nameAuthor"; nameHit },
            bySearchNameAuthor = steps.bySearchNameAuthor,
        )
        assertEquals(nameHit, fallback)
        assertEquals(listOf("url", "urlSearch", "url", "urlSearch", "nameAuthor"), steps.calls)
    }

    @Test
    fun `bookUrl空白跳过url两步直接弱定位`() {
        val steps = Steps()
        val nameHit = book("url-4")
        val result = findBookCandidate(
            bookUrl = "",
            byUrl = steps.byUrl,
            byUrlSearch = steps.byUrlSearch,
            byNameAuthor = { steps.calls += "nameAuthor"; nameHit },
            bySearchNameAuthor = steps.bySearchNameAuthor,
        )
        assertEquals(nameHit, result)
        assertEquals(listOf("nameAuthor"), steps.calls)
    }

    @Test
    fun `全部落空返回null`() {
        val steps = Steps()
        val result = findBookCandidate(
            bookUrl = "url-5",
            byUrl = steps.byUrl,
            byUrlSearch = steps.byUrlSearch,
            byNameAuthor = steps.byNameAuthor,
            bySearchNameAuthor = steps.bySearchNameAuthor,
        )
        assertNull(result)
        assertEquals(listOf("url", "urlSearch", "nameAuthor", "searchNameAuthor"), steps.calls)
    }

    @Test
    fun `书源名优先取记录自带originName`() {
        assertEquals(
            "记录名",
            resolveDisplaySource(
                originName = "记录名",
                origin = "https://a.example",
                lookedUpSourceName = "书源表名",
            ),
        )
    }

    @Test
    fun `originName空白回退书源表名再回退origin`() {
        assertEquals(
            "书源表名",
            resolveDisplaySource(
                originName = "",
                origin = "https://a.example",
                lookedUpSourceName = "书源表名",
            ),
        )
        assertEquals(
            "https://a.example",
            resolveDisplaySource(
                originName = " ",
                origin = "https://a.example",
                lookedUpSourceName = null,
            ),
        )
    }

    @Test
    fun `本地书不展示书源行`() {
        assertNull(
            resolveDisplaySource(
                originName = "",
                origin = BookType.localTag,
                lookedUpSourceName = null,
            ),
        )
        // 本地导入 origin 可能带路径后缀，同样不展示
        assertNull(
            resolveDisplaySource(
                originName = "",
                origin = "${BookType.localTag}/Books/local.txt",
                lookedUpSourceName = null,
            ),
        )
    }
}
