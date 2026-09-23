package io.legado.app.eink.bridge

import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.domain.gateway.ChangeSourceSettingsGateway
import io.legado.app.domain.usecase.ChangeBookSourceUseCase
import io.legado.app.domain.usecase.WebDavBackupUseCase
import io.legado.app.eink.contract.BookHandle
import io.legado.app.eink.contract.ChangeSourceBookUiModel
import io.legado.app.eink.contract.ChangeSourceEngine
import io.legado.app.eink.contract.ChangeSourceResultUiModel
import io.legado.app.eink.contract.SearchResultHandle
import io.legado.app.eink.contract.SourceHandle
import io.legado.app.help.coroutine.Coroutine
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
 * （kind 不参与判定）；getChapterListAwait 返回 Result。
 *
 * 搜索缓存与完整模式换源 Sheet 同源同表（searchBooks）：逐源搜索结果
 * REPLACE 落库，cachedSourceBooks 读同表历史记录——两边的换源互为对方
 * 预热缓存。
 *
 * 换源落地复用宿主 Compose 换源同一条链（[ChangeBookSourceUseCase.changeTo]，
 * 迁移项取「换源选项」设置）：migrateInto 迁移（含 remark、进度索引钳制）+
 * 缓存目录搬移 + 事务化替换 + 阅读时长会话改挂，替代 View 版 migrateTo
 * 旧管线的对应缺口。
 */
internal object ChangeSourceEngineImpl : ChangeSourceEngine, KoinComponent {

    private val changeSourceSettingsGateway: ChangeSourceSettingsGateway by inject()

    private val changeBookSourceUseCase: ChangeBookSourceUseCase by inject()
    private val webDavBackupUseCase: WebDavBackupUseCase by inject()

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

    override suspend fun cachedSourceBooks(
        name: String,
        author: String,
        checkAuthor: Boolean,
    ): List<ChangeSourceResultUiModel> {
        // 宿主换源 Sheet initData 同语义：读该书名的历史搜索记录，
        // 只含当前启用书源（DAO 内联过滤）；不校验作者时作者不参与匹配
        val matchAuthor = if (checkAuthor) author.replace(AppPattern.authorRegex, "") else ""
        return appDb.searchBookDao.changeSourceByGroup(name, matchAuthor, "")
            .map { it.toUiModel() }
    }

    override suspend fun searchSourceBook(
        source: SourceHandle,
        name: String,
        author: String,
        checkAuthor: Boolean,
    ): List<ChangeSourceResultUiModel> {
        val bookSource = (source as SourceHandleImpl).source
        val strippedAuthor = author.replace(AppPattern.authorRegex, "")
        val searchBooks = WebBook.searchBookAwait(
            bookSource,
            name,
            filter = { fName, fAuthor, _ ->
                fName == name && (!checkAuthor || fAuthor.contains(strippedAuthor))
            }
        ).onEach { searchBook ->
            searchBook.releaseHtmlData()
        }
        // 逐源结果落库（REPLACE），作为下次进入换源页的历史缓存，
        // 也与完整模式换源 Sheet 共享同一份缓存数据
        if (searchBooks.isNotEmpty()) {
            appDb.searchBookDao.insert(searchBooks)
        }
        return searchBooks.map { it.toUiModel() }
    }

    private fun SearchBook.toUiModel(): ChangeSourceResultUiModel = ChangeSourceResultUiModel(
        handle = SearchResultHandleImpl(this),
        bookUrl = bookUrl,
        name = name,
        author = author,
        origin = origin,
        originName = originName,
        latestChapter = latestChapterTitle,
        deduplicationKey = primaryStr(),
    )

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

            changeBookSourceUseCase.changeTo(
                oldBook = oldBook,
                newBook = newBook,
                chapters = toc,
                options = changeSourceSettingsGateway.currentSettings.migrationOptions(),
            )

            // 重载引擎会话；阅读页返回时会采用引擎当前书籍
            ReadBook.resetData(newBook)
            ReadBook.loadContent(resetPageOffset = true)
            // 通知栈下方的详情等界面按新 bookUrl 跟随刷新
            _bookChanged.tryEmit(newBook.bookUrl)
            refreshCloudBackupAfterChange()
            Result.success(BookHandleImpl(newBook))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppLog.put("换源失败\n${e.localizedMessage}", e)
            Result.failure(e)
        }
    }

    /**
     * 换源即替换：旧源记录此刻已删。云端备份若仍停留在换源前，恢复合并
     * （按 bookUrl 对齐、只增不删）会把旧源记录当新书插回，书架出现同名
     * 多本。换源成功后立即走手动备份同链路刷新云端（不受 autoBack 每日
     * 一闸限制）。WebDAV 不可达（未配置/网络不可用）静默跳过；失败仅记
     * 日志，不影响换源结果与界面返回。
     */
    private fun refreshCloudBackupAfterChange() {
        Coroutine.async {
            runCatching { webDavBackupUseCase.getLatestBackup() }.getOrNull()
                ?: return@async
            webDavBackupUseCase.backup()
        }.onError {
            AppLog.put("换源后刷新云端备份失败\n${it.localizedMessage}", it)
        }
    }
}
