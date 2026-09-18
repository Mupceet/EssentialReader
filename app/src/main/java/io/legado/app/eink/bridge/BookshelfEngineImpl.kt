package io.legado.app.eink.bridge

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.AppConfig
import io.legado.app.eink.contract.BookshelfEngine
import io.legado.app.eink.contract.BookshelfItemUiModel
import io.legado.app.eink.contract.BookshelfStyle
import io.legado.app.eink.contract.BookshelfTocRefreshResult
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.addType
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isUpError
import io.legado.app.help.book.removeType
import io.legado.app.help.book.sync
import io.legado.app.model.CacheBook
import io.legado.app.model.ReadBook
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import splitties.init.appCtx
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min

/** 书架显示样式的 E-Ink 自有偏好键（落 eink_preferences 专属文件）。 */
private const val KEY_STYLE_HIGHLIGHT_NEW = "einkBookshelfHighlightNew"
private const val KEY_STYLE_SHOW_LATEST = "einkBookshelfShowLatestChapter"
private const val KEY_STYLE_GRID_LAYOUT = "einkBookshelfGridLayout"
private const val KEY_STYLE_GRID_COVER_WIDTH = "einkBookshelfGridCoverWidth"
private const val KEY_STYLE_TITLE_MAX_LINES = "einkBookshelfTitleMaxLines"

/** [Book] → [BookshelfItemUiModel]：条目渲染字段的唯一抽取点（分组端口共用）。 */
internal fun Book.toBookshelfItemUiModel() = BookshelfItemUiModel(
    bookUrl = bookUrl,
    name = name,
    author = author,
    displayAuthor = getRealAuthor(),
    coverUrl = getDisplayCover(),
    origin = origin,
    currentChapterTitle = durChapterTitle,
    latestChapterTitle = latestChapterTitle,
    unreadCount = getUnreadChapterNum(),
    hasNewChapter = lastCheckCount > 0,
)

/**
 * 书架端口实现：转发 DAO/引擎调用 + Book → UiModel 映射 + 单本书目录
 * 刷新管线（复刻 View 版 BookshelfViewModel 的目录刷新，E-Ink VM 保留
 * 并发编排与 updating 标记）。
 *
 * 本上游差异：getChapterListAwait 返回 Result（getOrThrow 解包），
 * 预缓存入队走 CacheBook.getOrCreate(...).addDownload(...)。
 */
internal object BookshelfEngineImpl : BookshelfEngine {

    // ---- 显示样式（本宿主差异：六个键中仅 showUnread 有宿主键可转发，
    // 其余五键为 E-Ink 自有偏好；宿主无 SP 流，跨模式实时联动不承接，
    // setStyle 双写状态流与存储即满足「实时档」契约） ----

    private val styleState = MutableStateFlow(readStyleSnapshot())

    private fun readStyleSnapshot() = BookshelfStyle(
        showUnreadBadge = AppConfig.showUnread,
        highlightNewChapter = einkPrefBoolean(KEY_STYLE_HIGHLIGHT_NEW, true),
        showLatestChapter = einkPrefBoolean(KEY_STYLE_SHOW_LATEST, true),
        isGridLayout = einkPrefBoolean(KEY_STYLE_GRID_LAYOUT, true),
        gridCoverWidth = einkPrefInt(KEY_STYLE_GRID_COVER_WIDTH, 120).coerceAtLeast(1),
        titleMaxLines = einkPrefInt(KEY_STYLE_TITLE_MAX_LINES, 2).coerceIn(1, 5),
    )

    override val style: Flow<BookshelfStyle> = styleState

    override suspend fun setStyle(style: BookshelfStyle) {
        styleState.value = style
        // 未读角标转发宿主键（完整模式共享，setter 自带落盘）；
        // 其余五键落 E-Ink 专属 prefs
        AppConfig.showUnread = style.showUnreadBadge
        einkPrefPutBoolean(KEY_STYLE_HIGHLIGHT_NEW, style.highlightNewChapter)
        einkPrefPutBoolean(KEY_STYLE_SHOW_LATEST, style.showLatestChapter)
        einkPrefPutBoolean(KEY_STYLE_GRID_LAYOUT, style.isGridLayout)
        einkPrefPutInt(KEY_STYLE_GRID_COVER_WIDTH, style.gridCoverWidth)
        einkPrefPutInt(KEY_STYLE_TITLE_MAX_LINES, style.titleMaxLines)
    }

    override fun observeShelf(): Flow<List<BookshelfItemUiModel>> =
        appDb.bookDao.flowByGroup(BookGroup.IdAll)
            .map { books -> books.map { it.toBookshelfItemUiModel() } }

    override fun lastReadBookUrl(): String? = appDb.bookDao.lastReadBook?.bookUrl

    override suspend fun deleteBooksNotInBookshelf() {
        appDb.bookDao.deleteNotShelfBook()
    }

    override suspend fun updatableBooks(groupId: Long): List<BookshelfItemUiModel> =
        appDb.bookDao.flowByGroup(groupId).first()
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
