package io.legado.app.eink.bridge

import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.eink.engine.TocEngine
import io.legado.app.eink.engine.TocFetchResult
import io.legado.app.eink.toc.ChapterUiModel
import io.legado.app.eink.toc.TocBookUiModel
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.addType
import io.legado.app.help.book.isLocal
import io.legado.app.model.webBook.WebBook
import kotlin.coroutines.cancellation.CancellationException

/**
 * 目录端口实现：书籍解析（书架优先、搜索书 notShelf 落库）与联网拉取
 * 目录管线的转发。
 */
internal object TocEngineImpl : TocEngine {

    private fun Book.toUiModel() = TocBookUiModel(
        bookUrl = bookUrl,
        name = name,
        durChapterIndex = durChapterIndex,
        isLocal = isLocal,
    )

    private fun BookChapter.toUiModel() = ChapterUiModel(
        index = index,
        title = title,
        url = url,
        isVolume = isVolume,
        fileName = getFileName(),
    )

    override suspend fun resolveTocBook(bookUrl: String): TocBookUiModel? {
        // 书架记录优先；未加书架的搜索书转 Book 入库（notShelf，不显示于
        // 书架，与 View 版"未加书架直接阅读"行为一致），使进度与目录缓存可写
        val book = appDb.bookDao.getBook(bookUrl)
            ?: appDb.searchBookDao.getSearchBook(bookUrl)?.toBook()?.apply {
                addType(BookType.notShelf)
                save()
            }
        return book?.toUiModel()
    }

    override suspend fun loadChapters(bookUrl: String): List<ChapterUiModel> =
        appDb.bookChapterDao.getChapterList(bookUrl).map { it.toUiModel() }

    override suspend fun fetchChaptersFromSource(bookUrl: String): TocFetchResult {
        val book = appDb.bookDao.getBook(bookUrl) ?: return TocFetchResult.NoSource
        val source = appDb.bookSourceDao.getBookSource(book.origin)
            ?: return TocFetchResult.NoSource
        return try {
            if (book.tocUrl.isEmpty()) {
                WebBook.getBookInfoAwait(source, book)
            }
            val chapters = WebBook.getChapterListAwait(source, book, true).getOrThrow()
            appDb.bookChapterDao.delByBook(bookUrl)
            appDb.bookChapterDao.insert(*chapters.toTypedArray())
            appDb.bookDao.update(book)
            TocFetchResult.Success(chapters.map { it.toUiModel() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TocFetchResult.Failure(e)
        }
    }

    override suspend fun cachedChapterFileNames(bookUrl: String): Set<String> {
        val book = appDb.bookDao.getBook(bookUrl) ?: return emptySet()
        return if (book.isLocal) emptySet() else BookHelp.getChapterFiles(book)
    }

    override suspend fun saveReadingProgress(
        bookUrl: String,
        chapterIndex: Int,
        chapterTitle: String,
    ) {
        val book = appDb.bookDao.getBook(bookUrl) ?: return
        book.durChapterIndex = chapterIndex
        book.durChapterPos = 0
        book.durChapterTitle = chapterTitle
        book.durChapterTime = System.currentTimeMillis()
        appDb.bookDao.update(book)
    }
}
