package io.legado.app.eink.bridge

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.eink.contract.BookDetailEngine
import io.legado.app.eink.contract.BookDetailPrefetchResult
import io.legado.app.eink.contract.BookDetailUiModel
import io.legado.app.eink.contract.BookHandle
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
 * 详情页书籍解析查找链（bookUrl 主键优先，规格见契约 findBook KDoc）。
 * 同名同作者多条书籍记录（不同书源同书、换源残留——合法状态）下，
 * name+author 单行查询固定命中 DAO 首行、与用户点选的书架条目无关，
 * 故 bookUrl 非空时必须先精确解析；name+author 仅兜底「无 url 身份」
 * 的纯导航参数场景。四个查找步骤惰性求值：前一步命中即短路。
 */
internal fun findBookCandidate(
    bookUrl: String,
    byUrl: () -> Book?,
    byUrlSearch: () -> Book?,
    byNameAuthor: () -> Book?,
    bySearchNameAuthor: () -> Book?,
): Book? {
    if (bookUrl.isNotBlank()) {
        byUrl()?.let { return it }
        byUrlSearch()?.let { return it }
    }
    return byNameAuthor() ?: bySearchNameAuthor()
}

/**
 * 详情页展示书源名解析（优先级锚定）：
 * 1. 书籍记录自带的 [Book.originName]（换源/入库时随源写入）；
 * 2. 书源表按 origin 现查的 bookSourceName（旧记录未回填 originName 的兜底）；
 * 3. origin 原值（书源 URL，仅最后手段）——本地书（loc_book）不展示，
 *    返回 null，模块侧跳过该行。
 */
internal fun resolveDisplaySource(
    originName: String,
    origin: String,
    lookedUpSourceName: String?,
): String? = when {
    originName.isNotBlank() -> originName
    !lookedUpSourceName.isNullOrBlank() -> lookedUpSourceName
    // 本地书 origin 可能带路径后缀（loc_book/...），按前缀判别（同
    // BookDao 本地/网络源的判别口径），网络源 URL 不受影响
    origin.isNotBlank() && !origin.startsWith(BookType.localTag) -> origin
    else -> null
}

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
        displaySource = resolveDisplaySource(
            originName = originName,
            origin = origin,
            // originName 已有时不查库：预取管线每次回包都会重映射
            lookedUpSourceName = if (originName.isBlank()) {
                appDb.bookSourceDao.getBookSource(origin)?.bookSourceName
            } else {
                null
            },
        ),
        latestChapterTitle = latestChapterTitle,
        currentChapterTitle = durChapterTitle,
        origin = origin,
    )

    override suspend fun findBook(
        name: String,
        author: String,
        bookUrl: String,
    ): Pair<BookHandle, BookDetailUiModel>? {
        val book = findBookCandidate(
            bookUrl = bookUrl,
            byUrl = { appDb.bookDao.getBook(bookUrl) },
            byUrlSearch = { appDb.searchBookDao.getSearchBook(bookUrl)?.toBook() },
            byNameAuthor = { appDb.bookDao.getBook(name, author) },
            bySearchNameAuthor = {
                appDb.searchBookDao.getFirstByNameAuthor(name, author)?.toBook()
            },
        ) ?: return null
        return BookHandleImpl(book) to book.toUiModel()
    }

    override suspend fun loadBookDetail(bookUrl: String): BookDetailUiModel? =
        appDb.bookDao.getBook(bookUrl)?.toUiModel()

    override suspend fun isBookInBookshelf(bookUrl: String): Boolean =
        appDb.bookDao.getBook(bookUrl)?.let { !it.isNotShelf } ?: false

    override suspend fun prefetchChapters(
        handle: BookHandle,
        inShelf: Boolean
    ): BookDetailPrefetchResult {
        val book = (handle as BookHandleImpl).book
        if (book.isLocal) return BookDetailPrefetchResult.Skipped
        if (appDb.bookChapterDao.getChapterList(book.bookUrl).isNotEmpty()) {
            return BookDetailPrefetchResult.Skipped
        }
        val source = appDb.bookSourceDao.getBookSource(book.origin)
            ?: return BookDetailPrefetchResult.Skipped
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
            return BookDetailPrefetchResult.Skipped
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
        return BookDetailPrefetchResult.Updated(BookHandleImpl(book), book.toUiModel())
    }

    override suspend fun addToBookshelf(handle: BookHandle): Boolean {
        val book = (handle as BookHandleImpl).book
        return try {
            book.removeType(BookType.notShelf)
            if (book.order == 0) {
                book.order = appDb.bookDao.minOrder - 1
            }
            // 同名进度合并取 DAO 首行：同名同作者多条记录（合法状态）下
            // 「与哪本合并」无既有消解规则，对齐 View 版 saveBook 同款语义
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
