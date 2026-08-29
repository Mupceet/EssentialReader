package io.legado.app.eink.bridge

import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.eink.feature.changesource.ChangeSourceBookUiModel
import io.legado.app.eink.feature.changesource.ChangeSourceResultUiModel
import io.legado.app.eink.engine.BookHandle
import io.legado.app.eink.engine.ChangeSourceEngine
import io.legado.app.eink.engine.SourceHandle
import io.legado.app.eink.engine.SearchResultHandle
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadBook
import io.legado.app.model.webBook.WebBook
import kotlin.coroutines.cancellation.CancellationException

/** 书源句柄（包装 BookSource）。 */
internal class SourceHandleImpl(val source: BookSource) : SourceHandle {
    override val url: String get() = source.bookSourceUrl
}

/** 搜索结果句柄（包装 SearchBook）。 */
internal class SearchResultHandleImpl(val searchBook: SearchBook) : SearchResultHandle

/**
 * 换源端口实现。
 *
 * 本上游差异：searchBookAwait filter 为三参 (name, author, kind: String?)
 * （kind 不参与判定）；migrateTo 需显式传 replaceEnableDefault 与
 * chineseConverterType（对齐 View 版 ChangeBookSourceDialog）；
 * getChapterListAwait 返回 Result。
 */
internal object ChangeSourceEngineImpl : ChangeSourceEngine {

    override suspend fun currentReadingBook(
        bookUrl: String,
    ): Pair<BookHandle, ChangeSourceBookUiModel>? {
        val book = ReadBook.book?.takeIf { it.bookUrl == bookUrl }
            ?: appDb.bookDao.getBook(bookUrl)
            ?: return null
        return BookHandleImpl(book) to ChangeSourceBookUiModel(
            bookUrl = book.bookUrl,
            name = book.name,
            author = book.author,
            origin = book.origin,
        )
    }

    override fun enabledSources(): List<SourceHandle> =
        appDb.bookSourceDao.allEnabledPart
            .mapNotNull { it.getBookSource() }
            .filter { !it.bookSourceUrl.isBlank() }
            .map { SourceHandleImpl(it) }

    override suspend fun searchSourceBook(
        source: SourceHandle,
        name: String,
        author: String,
        checkAuthor: Boolean,
    ): List<ChangeSourceResultUiModel> {
        val bookSource = (source as SourceHandleImpl).source
        val strippedAuthor = author.replace(AppPattern.authorRegex, "")
        return WebBook.searchBookAwait(
            bookSource,
            name,
            filter = { fName, fAuthor, _ ->
                fName == name && (!checkAuthor || fAuthor.contains(strippedAuthor))
            }
        ).map { searchBook ->
            searchBook.releaseHtmlData()
            ChangeSourceResultUiModel(
                handle = SearchResultHandleImpl(searchBook),
                bookUrl = searchBook.bookUrl,
                name = searchBook.name,
                author = searchBook.author,
                origin = searchBook.origin,
                originName = searchBook.originName,
                primary = searchBook.primaryStr(),
            )
        }
    }

    override suspend fun changeBookSource(
        bookHandle: BookHandle,
        result: ChangeSourceResultUiModel,
    ): Result<BookHandle> {
        val oldBook = (bookHandle as BookHandleImpl).book
        val searchBook = (result.handle as SearchResultHandleImpl).searchBook
        return try {
            val source = appDb.bookSourceDao.getBookSource(searchBook.origin)
                ?: throw IllegalStateException("书源不存在")
            val newBook = searchBook.toBook()
            if (newBook.tocUrl.isEmpty()) {
                WebBook.getBookInfoAwait(source, newBook)
            }
            val toc = WebBook.getChapterListAwait(source, newBook).getOrThrow()

            oldBook.migrateTo(
                newBook,
                toc,
                AppConfig.replaceEnableDefault,
                AppConfig.chineseConverterType,
            )
            newBook.removeType(BookType.updateError)
            oldBook.delete()
            appDb.bookDao.insert(newBook)
            appDb.bookChapterDao.insert(*toc.toTypedArray())

            // 重载引擎会话；阅读页返回时会采用引擎当前书籍
            ReadBook.resetData(newBook)
            ReadBook.loadContent(resetPageOffset = true)
            Result.success(BookHandleImpl(newBook))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppLog.put("换源失败\n${e.localizedMessage}", e)
            Result.failure(e)
        }
    }
}
