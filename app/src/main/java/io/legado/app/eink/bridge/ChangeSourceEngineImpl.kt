package io.legado.app.eink.bridge

import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.domain.gateway.OtherSettingsGateway
import io.legado.app.domain.gateway.ReadSettingsGateway
import io.legado.app.eink.contract.BookHandle
import io.legado.app.eink.contract.ChangeSourceBookUiModel
import io.legado.app.eink.contract.ChangeSourceEngine
import io.legado.app.eink.contract.ChangeSourceResultUiModel
import io.legado.app.eink.contract.SearchResultHandle
import io.legado.app.eink.contract.SourceHandle
import io.legado.app.help.book.removeType
import io.legado.app.model.ReadBook
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
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
internal object ChangeSourceEngineImpl : ChangeSourceEngine, KoinComponent {

    private val otherSettingsGateway: OtherSettingsGateway by inject()

    private val readSettingsGateway: ReadSettingsGateway by inject()
    private val _bookChanged = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val bookChanged: SharedFlow<String> = _bookChanged.asSharedFlow()

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
                latestChapter = searchBook.latestChapterTitle,
                deduplicationKey = searchBook.primaryStr(),
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
                otherSettingsGateway.currentSettings.replaceEnableDefault,
                readSettingsGateway.currentSettings.chineseConverterType,
            )
            newBook.removeType(BookType.updateError)
            oldBook.delete()
            appDb.bookDao.insert(newBook)
            appDb.bookChapterDao.insert(*toc.toTypedArray())

            // 重载引擎会话；阅读页返回时会采用引擎当前书籍
            ReadBook.resetData(newBook)
            ReadBook.loadContent(resetPageOffset = true)
            // 通知栈下方的详情等界面按新 bookUrl 跟随刷新
            _bookChanged.tryEmit(newBook.bookUrl)
            Result.success(BookHandleImpl(newBook))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppLog.put("换源失败\n${e.localizedMessage}", e)
            Result.failure(e)
        }
    }
}
