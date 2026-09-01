package io.legado.app.eink.bridge

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.eink.contract.BookshelfItemUiModel
import io.legado.app.eink.contract.BookshelfEngine
import io.legado.app.eink.contract.BookshelfTocRefreshResult
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.addType
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isUpError
import io.legado.app.help.book.removeType
import io.legado.app.help.book.sync
import io.legado.app.help.config.AppConfig
import io.legado.app.model.CacheBook
import io.legado.app.model.ReadBook
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min

/**
 * 书架端口实现：转发 DAO/引擎调用 + Book → UiModel 映射 + 单本书目录
 * 刷新管线（复刻 View 版 BookshelfViewModel 的目录刷新，E-Ink VM 保留
 * 并发编排与 updating 标记）。
 *
 * 本上游差异：getChapterListAwait 返回 Result（getOrThrow 解包），
 * 预缓存入队走 CacheBook.getOrCreate(...).addDownload(...)。
 */
internal object BookshelfEngineImpl : BookshelfEngine {

    /** [Book] → [BookshelfItemUiModel]：条目渲染字段的唯一抽取点。 */
    private fun Book.toBookshelfItemUiModel() = BookshelfItemUiModel(
        bookUrl = bookUrl,
        name = name,
        author = author,
        displayAuthor = getRealAuthor(),
        coverUrl = getDisplayCover(),
        origin = origin,
        durChapterTitle = durChapterTitle,
        latestChapterTitle = latestChapterTitle,
        unreadCount = getUnreadChapterNum(),
        hasNewChapter = lastCheckCount > 0,
    )

    override fun observeShelf(): Flow<List<BookshelfItemUiModel>> =
        appDb.bookDao.flowByGroup(BookGroup.IdAll)
            .map { books -> books.map { it.toBookshelfItemUiModel() } }

    override suspend fun deleteNotShelfBooks() {
        appDb.bookDao.deleteNotShelfBook()
    }

    override suspend fun updatableShelfBooks(): List<BookshelfItemUiModel> =
        appDb.bookDao.flowByGroup(BookGroup.IdAll).first()
            .filter { !it.isLocal && it.canUpdate }
            .map { it.toBookshelfItemUiModel() }

    override suspend fun refreshBookToc(bookUrl: String): BookshelfTocRefreshResult {
        val book = appDb.bookDao.getBook(bookUrl) ?: return BookshelfTocRefreshResult.NO_BOOK
        val source = appDb.bookSourceDao.getBookSource(book.origin)
        if (source == null) {
            if (!book.isUpError) {
                book.addType(BookType.updateError)
                appDb.bookDao.update(book)
            }
            return BookshelfTocRefreshResult.NO_SOURCE
        }
        return try {
            val oldBook = book.copy()
            if (book.tocUrl.isBlank()) {
                WebBook.getBookInfoAwait(source, book)
            } else {
                WebBook.runPreUpdateJs(source, book)
            }
            val toc = WebBook.getChapterListAwait(source, book).getOrThrow()
            book.sync(oldBook)
            book.removeType(BookType.updateError)
            if (book.bookUrl == bookUrl) {
                appDb.bookDao.update(book)
            } else {
                // 目录地址重定向，替换书架记录并迁移缓存目录
                appDb.bookDao.replace(oldBook, book)
                BookHelp.updateCacheFolder(oldBook, book)
            }
            appDb.bookChapterDao.delByBook(bookUrl)
            appDb.bookChapterDao.insert(*toc.toTypedArray())
            ReadBook.onChapterListUpdated(book)
            enqueuePreDownload(source, book)
            BookshelfTocRefreshResult.OK
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppLog.put("${book.name} 更新目录失败\n${e.localizedMessage}", e)
            //这里可能因为时间太长书籍信息已经更改,所以重新获取
            appDb.bookDao.getBook(book.bookUrl)?.let { curBook ->
                curBook.addType(BookType.updateError)
                appDb.bookDao.update(curBook)
            }
            BookshelfTocRefreshResult.ERROR
        }
    }

    /**
     * 目录刷新完成后入队预缓存章节（对齐 View 版 BookshelfViewModel）：
     * 当前进度起往后 preDownloadNum 章。
     */
    private fun enqueuePreDownload(source: BookSource, book: Book) {
        if (AppConfig.preDownloadNum == 0) return
        val endIndex = min(
            book.totalChapterNum - 1,
            book.durChapterIndex.plus(AppConfig.preDownloadNum)
        )
        CacheBook.getOrCreate(source, book).addDownload(book.durChapterIndex, endIndex)
    }

    override val isCacheRunning: Boolean
        get() = CacheBook.isRun

    override fun setCacheWorkingState(working: Boolean) {
        CacheBook.setWorkingState(working)
    }

    override suspend fun startCacheProcessJob() {
        CacheBook.startProcessJob(Dispatchers.IO)
    }
}
