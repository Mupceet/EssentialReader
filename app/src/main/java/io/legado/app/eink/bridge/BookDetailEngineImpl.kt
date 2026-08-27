package io.legado.app.eink.bridge

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.eink.bookdetail.BookDetailUiModel
import io.legado.app.eink.engine.BookDetailEngine
import io.legado.app.eink.engine.BookHandle
import io.legado.app.eink.engine.PrefetchResult
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.addType
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.book.removeType
import io.legado.app.model.webBook.WebBook
import kotlin.coroutines.cancellation.CancellationException

/** Book 实体句柄（模块侧只持有/回传，不解读）。 */
internal class BookHandleImpl(val book: Book) : BookHandle

/**
 * 书籍详情端口实现：查找链转发 + 目录预取管线（对齐 View 版
 * 详情页的拉取时机）。
 *
 * 本上游差异：getDisplayIntro 返回可空（UiModel.displayIntro 为 String?）；
 * getChapterListAwait 返回 Result（getOrThrow 解包）。
 */
internal object BookDetailEngineImpl : BookDetailEngine {

    private fun Book.toUiModel() = BookDetailUiModel(
        bookUrl = bookUrl,
        name = name,
        displayAuthor = getRealAuthor(),
        displayCover = getDisplayCover(),
        displayIntro = getDisplayIntro(),
        latestChapterTitle = latestChapterTitle,
        durChapterTitle = durChapterTitle,
        origin = origin,
    )

    override suspend fun findBook(
        name: String,
        author: String,
        bookUrl: String,
    ): Pair<BookHandle, BookDetailUiModel>? {
        val book = appDb.bookDao.getBook(name, author)
            ?: bookUrl.takeIf { it.isNotBlank() }?.let { url ->
                appDb.bookDao.getBook(url)
                    ?: appDb.searchBookDao.getSearchBook(url)?.toBook()
            }
            ?: appDb.searchBookDao.getFirstByNameAuthor(name, author)?.toBook()
            ?: return null
        return BookHandleImpl(book) to book.toUiModel()
    }

    override suspend fun loadBookDetail(bookUrl: String): BookDetailUiModel? =
        appDb.bookDao.getBook(bookUrl)?.toUiModel()

    override suspend fun isBookInBookshelf(bookUrl: String): Boolean =
        appDb.bookDao.getBook(bookUrl)?.let { !it.isNotShelf } ?: false

    override suspend fun prefetchChapters(handle: BookHandle, inShelf: Boolean): PrefetchResult {
        val book = (handle as BookHandleImpl).book
        if (book.isLocal) return PrefetchResult.Skipped
        if (appDb.bookChapterDao.getChapterList(book.bookUrl).isNotEmpty()) {
            return PrefetchResult.Skipped
        }
        val source = appDb.bookSourceDao.getBookSource(book.origin)
            ?: return PrefetchResult.Skipped
        val oldBook = book.copy()
        val chapters = try {
            if (book.tocUrl.isEmpty()) {
                WebBook.getBookInfoAwait(source, book, canReName = true)
            }
            WebBook.getChapterListAwait(source, book, runPerJs = inShelf).getOrThrow()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppLog.put("详情页预取目录出错《${book.name}》\n${e.localizedMessage}", e)
            return PrefetchResult.Skipped
        }
        if (inShelf) {
            if (oldBook.bookUrl == book.bookUrl) {
                appDb.bookDao.update(book)
            } else {
                // 目录地址重定向，替换书架记录并迁移缓存目录
                appDb.bookDao.replace(oldBook, book)
                BookHelp.updateCacheFolder(oldBook, book)
                appDb.bookChapterDao.delByBook(oldBook.bookUrl)
            }
        } else {
            book.addType(BookType.notShelf)
            book.save()
        }
        appDb.bookChapterDao.insert(*chapters.toTypedArray())
        return PrefetchResult.Updated(BookHandleImpl(book), book.toUiModel())
    }

    override suspend fun addToBookshelf(handle: BookHandle): Boolean {
        val book = (handle as BookHandleImpl).book
        return try {
            book.removeType(BookType.notShelf)
            if (book.order == 0) {
                book.order = appDb.bookDao.minOrder - 1
            }
            appDb.bookDao.getBook(book.name, book.author)?.let {
                book.durChapterIndex = it.durChapterIndex
                book.durChapterPos = it.durChapterPos
                book.durChapterTitle = it.durChapterTitle
            }
            book.save()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
    }

    override suspend fun removeFromBookshelf(handle: BookHandle): Boolean {
        val book = (handle as BookHandleImpl).book
        return try {
            book.addType(BookType.notShelf)
            book.save()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
    }
}
