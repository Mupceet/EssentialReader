package io.legado.app.eink.bridge

import io.legado.app.constant.AppConst
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.eink.contract.BookHandle
import io.legado.app.eink.contract.ReaderBookSnapshot
import io.legado.app.eink.contract.ReaderEngine
import io.legado.app.eink.contract.ReaderEngineCallback
import io.legado.app.eink.contract.ReaderHeaderFooterVisibility
import io.legado.app.eink.contract.ReaderPageSnapshot
import io.legado.app.eink.contract.ReaderPrepareResult
import io.legado.app.eink.contract.ReaderTextStyle
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.addType
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalModified
import io.legado.app.help.book.isType
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ReadTipConfig
import io.legado.app.model.CacheBook
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.putPrefInt
import splitties.init.appCtx
import java.util.Date
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min

/** Book 快照实现。 */
internal class ReaderBookSnapshotImpl(override val handle: BookHandle) : ReaderBookSnapshot {
    val book: Book get() = (handle as BookHandleImpl).book
    override val bookUrl: String get() = book.bookUrl
    override val name: String get() = book.name
    override val author: String get() = book.author
    override val isLocal: Boolean get() = book.isLocal
    override val isInBookshelf: Boolean get() = !book.isType(BookType.notShelf)
}

/**
 * 阅读器端口实现：ReadBook 全局状态机 + ChapterProvider 排版引擎的
 * 纯转发（含宿主单轨回调 → 模块回调的适配与样式快照映射）。
 * 排版产物经 [ReaderPageSnapshotMapper] 映射为模块快照。
 *
 * 本上游差异：
 *  - ReadBook.CallBack 为单轨全量接口（业务 + 渲染方法同轨，含
 *    LayoutProgressListener 的 onLayoutException/cancelSelect）——
 *    适配器实现一个接口即可，注册/注销单轨走；
 *  - 排版写路径为 ReadBookConfig 直写 + save() + upStyle()（本宿主无
 *    只读化护栏与 ReadStyleGateway）；
 *  - 章节缓存走 CacheBook.start(context, book, start, end)（非 suspend）。
 */
internal object ReaderEngineImpl : ReaderEngine {

    private fun Book.snapshot() = ReaderBookSnapshotImpl(BookHandleImpl(this))

    private fun snapshotOf(book: Book?): ReaderBookSnapshot? = book?.snapshot()

    // ---- 注册与生命周期 ----

    /** 按回调实例缓存适配器（ReadBook 注销用恒等比较，必须同一实例）。 */
    private var cachedAdapter: EngineCallBackAdapter? = null

    private fun adapterFor(callback: ReaderEngineCallback): EngineCallBackAdapter =
        cachedAdapter?.takeIf { it.callback === callback }
            ?: EngineCallBackAdapter(callback).also { cachedAdapter = it }

    override fun register(callback: ReaderEngineCallback) {
        ReadBook.register(adapterFor(callback))
    }

    override fun unregister(callback: ReaderEngineCallback) {
        ReadBook.unregister(adapterFor(callback))
    }

    override fun isRegistered(callback: ReaderEngineCallback): Boolean {
        val current = ReadBook.callBack
        return current is EngineCallBackAdapter && current.callback === callback
    }

    override fun saveReadingProgress() {
        ReadBook.saveRead()
    }

    // ---- 会话只读状态 ----

    override val sessionBook: ReaderBookSnapshot?
        get() = snapshotOf(ReadBook.book)

    override val sessionBookUrl: String?
        get() = ReadBook.book?.bookUrl

    override val chapterSize: Int
        get() = ReadBook.chapterSize

    override val currentChapterIndex: Int
        get() = ReadBook.durChapterIndex

    override val currentPageIndex: Int
        get() = ReadBook.durPageIndex

    override val engineMessage: String?
        get() = ReadBook.msg

    override val hasLaidOutPages: Boolean
        get() = ReadBook.curTextChapter?.pages?.isNotEmpty() == true

    override val currentChapterPageSize: Int
        get() = ReadBook.curTextChapter?.pageSize ?: 0

    override fun currentPage(): ReaderPageSnapshot? {
        val chapter = ReadBook.curTextChapter ?: return null
        return chapter.getPage(ReadBook.durPageIndex)?.let(ReaderPageSnapshotMapper::map)
    }

    // ---- 会话控制 ----

    override fun loadBook(book: BookHandle) {
        ReadBook.upData((book as BookHandleImpl).book)
    }

    override fun reloadBook(book: BookHandle) {
        ReadBook.resetData((book as BookHandleImpl).book)
    }

    override fun setInBookshelf(value: Boolean) {
        ReadBook.inBookshelf = value
    }

    override fun clearEngineMessage() {
        ReadBook.upMsg(null)
    }

    override fun loadContent(resetPageOffset: Boolean) {
        ReadBook.loadContent(resetPageOffset = resetPageOffset)
    }

    override fun loadContent(chapterIndex: Int, resetPageOffset: Boolean) {
        ReadBook.loadContent(chapterIndex, resetPageOffset = resetPageOffset)
    }

    override fun refreshToc() {
        ReadBook.upToc()
    }

    override suspend fun resolveBook(bookUrl: String): ReaderBookSnapshot? {
        val book = appDb.bookDao.getBook(bookUrl)
            ?: appDb.searchBookDao.getSearchBook(bookUrl)?.toBook()?.apply {
                addType(BookType.notShelf)
                save()
            }
        return snapshotOf(book)
    }

    override suspend fun prepareBookData(bookHandle: BookHandle): ReaderPrepareResult {
        val book = (bookHandle as BookHandleImpl).book
        if (!book.isLocal && book.tocUrl.isEmpty()) {
            val source = ReadBook.bookSource
                ?: return ReaderPrepareResult.NoSource
            try {
                WebBook.getBookInfoAwait(source, book, canReName = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return ReaderPrepareResult.BookInfoFailure(e)
            }
        }
        if (ReadBook.chapterSize == 0 || book.isLocalModified()) {
            return loadChapterListIntoDb(book)
        }
        return ReaderPrepareResult.Success
    }

    /** 目录入库（本地书走 LocalBook；网络书重定向时替换记录并迁移缓存）。 */
    private suspend fun loadChapterListIntoDb(book: Book): ReaderPrepareResult {
        if (book.isLocal) {
            return try {
                LocalBook.getChapterList(book).let { chapters ->
                    appDb.bookChapterDao.delByBook(book.bookUrl)
                    appDb.bookChapterDao.insert(*chapters.toTypedArray())
                    appDb.bookDao.update(book)
                    ReadBook.onChapterListUpdated(book)
                }
                ReaderPrepareResult.Success
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ReaderPrepareResult.TocFailure(e)
            }
        }
        val source = ReadBook.bookSource
            ?: return ReaderPrepareResult.NoSource
        val oldBook = book.copy()
        val chapters = try {
            WebBook.getChapterListAwait(source, book, true).getOrThrow()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return ReaderPrepareResult.TocFailure(e)
        }
        if (oldBook.bookUrl == book.bookUrl) {
            appDb.bookDao.update(book)
        } else {
            // 目录地址重定向，替换书架记录并迁移缓存目录
            appDb.bookDao.replace(oldBook, book)
            BookHelp.updateCacheFolder(oldBook, book)
        }
        appDb.bookChapterDao.delByBook(oldBook.bookUrl)
        appDb.bookChapterDao.insert(*chapters.toTypedArray())
        ReadBook.onChapterListUpdated(book)
        return ReaderPrepareResult.Success
    }

    // ---- 翻页 ----

    override fun nextPage(): Boolean =
        ReadBook.moveToNextPage() || ReadBook.moveToNextChapter(upContent = true)

    override fun prevPage(): Boolean =
        ReadBook.moveToPrevPage() || ReadBook.moveToPrevChapter(upContent = true, toLast = true)

    override fun skipToPage(pageIndex: Int) {
        ReadBook.skipToPage(pageIndex)
    }

    override fun nextChapter(): Boolean =
        ReadBook.moveToNextChapter(upContent = true)

    override fun prevChapter(): Boolean =
        ReadBook.moveToPrevChapter(upContent = true, toLast = false)

    override val autoReadIntervalSec: Int
        get() = ReadBookConfig.autoReadSpeed

    override suspend fun setAutoReadIntervalSec(value: Int) {
        ReadBookConfig.autoReadSpeed = value
        appCtx.putPrefInt(PreferKey.autoReadSpeed, value)
    }

    // ---- 章节操作 ----

    override suspend fun refreshCurrentChapter() {
        val book = ReadBook.book ?: return
        appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)?.let { chapter ->
            BookHelp.delContent(book, chapter)
        }
        ReadBook.loadContent(ReadBook.durChapterIndex, resetPageOffset = false)
    }

    override fun startCache(count: Int, cacheAll: Boolean): Boolean? {
        val book = ReadBook.book ?: return null
        if (book.isLocal) return false
        val end = if (cacheAll) {
            book.totalChapterNum - 1
        } else {
            min(ReadBook.durChapterIndex + count, book.totalChapterNum - 1)
        }
        CacheBook.start(appCtx, book, ReadBook.durChapterIndex, end)
        return true
    }

    override suspend fun addSessionBookToShelf(): Boolean? {
        val book = ReadBook.book ?: return null
        return try {
            if (book.isType(BookType.notShelf)) {
                book.removeType(BookType.notShelf)
                if (book.order == 0) {
                    book.order = appDb.bookDao.minOrder - 1
                }
                ReadBook.inBookshelf = true
                book.save()
                true
            } else {
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
    }

    override suspend fun removeSessionBookFromShelf(): Boolean? {
        val book = ReadBook.book ?: return null
        return try {
            if (!book.isType(BookType.notShelf)) {
                book.addType(BookType.notShelf)
                ReadBook.inBookshelf = false
                book.save()
                true
            } else {
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
    }

    // ---- 排版 ----

    override fun updateViewSize(width: Int, height: Int) {
        ChapterProvider.upViewSize(width, height)
    }

    override fun applyStyle(style: ReaderTextStyle) {
        ReadBookConfig.textSize = style.textSize
        // 标题跟随正文字号：本仓 titleSize 为完整模式排版的绝对字号，
        // E-Ink 语义是标题与正文一致——应用排版时覆盖残留绝对值
        ReadBookConfig.titleSize = style.textSize
        ReadBookConfig.letterSpacing = style.letterSpacing
        ReadBookConfig.paragraphIndent =
            if (style.indentChars <= 0) "" else ChapterProvider.indentChar.repeat(style.indentChars)
        ReadBookConfig.lineSpacingExtra = style.lineSpacing
        ReadBookConfig.paragraphSpacing = style.paragraphSpacing
        ReadBookConfig.paddingLeft = style.paddingLeft
        ReadBookConfig.paddingTop = style.paddingTop
        ReadBookConfig.paddingRight = style.paddingRight
        ReadBookConfig.paddingBottom = style.paddingBottom
        ReadBookConfig.durConfig.headerPaddingLeft = style.headerPaddingLeft
        ReadBookConfig.durConfig.headerPaddingTop = style.headerPaddingTop
        ReadBookConfig.durConfig.headerPaddingRight = style.headerPaddingRight
        ReadBookConfig.durConfig.headerPaddingBottom = style.headerPaddingBottom
        ReadBookConfig.durConfig.footerPaddingLeft = style.footerPaddingLeft
        ReadBookConfig.durConfig.footerPaddingTop = style.footerPaddingTop
        ReadBookConfig.durConfig.footerPaddingRight = style.footerPaddingRight
        ReadBookConfig.durConfig.footerPaddingBottom = style.footerPaddingBottom
        ReadBookConfig.save()
        ChapterProvider.upStyle()
    }

    override fun setTextBold(enabled: Boolean) {
        ReadBookConfig.textBold = if (enabled) 1 else 0
        ReadBookConfig.save()
        ChapterProvider.upStyle()
    }

    override val textBold: Boolean
        get() = ReadBookConfig.textBold == 1

    override fun currentStyle(): ReaderTextStyle = ReadBookConfig.snapshotStyle()

    override fun relayout() {
        ReadBook.clearTextChapter()
        val index = ReadBook.durChapterIndex
        ReadBook.removeLoading(index - 1)
        ReadBook.removeLoading(index)
        ReadBook.removeLoading(index + 1)
        ReadBook.loadContent(resetPageOffset = false)
    }

    // ---- 触控与页眉页脚 ----

    override val pageTouchSlop: Int
        get() = AppConfig.pageTouchSlop

    override fun headerFooterVisibility(): ReaderHeaderFooterVisibility =
        ReaderHeaderFooterVisibility(
            headerVisible = when (ReadTipConfig.headerMode) {
                1 -> true
                2 -> false
                else -> ReadBookConfig.hideStatusBar
            },
            footerVisible = ReadTipConfig.footerMode != 1,
        )

    override fun formatTimeNow(): String =
        AppConst.timeFormat.format(Date()).toString()

    /**
     * 宿主单轨回调适配（每回调实例缓存一份，恒等比较安全）：
     * ReadBook.CallBack 全量接口（业务 + 渲染方法同轨）。
     */
    private class EngineCallBackAdapter(val callback: ReaderEngineCallback) : ReadBook.CallBack {

        override fun upMenuView() = callback.onRequestShowMenu()

        override fun loadChapterList(book: Book) {
            callback.onLoadChapterList(
                ReaderBookSnapshotImpl(BookHandleImpl(book))
            )
        }

        override fun upContent(
            relativePosition: Int,
            resetPageOffset: Boolean,
            success: (() -> Unit)?,
        ) = callback.onContentUpdated(relativePosition, resetPageOffset, success)

        override suspend fun upContentAwait(
            relativePosition: Int,
            resetPageOffset: Boolean,
            success: (() -> Unit)?,
        ) = callback.onContentUpdated(relativePosition, resetPageOffset, success)

        override fun pageChanged() = callback.onPageChanged()

        override fun contentLoadFinish() = callback.onContentLoadFinish()

        override fun upPageAnim(upRecorder: Boolean) {}

        override fun notifyBookChanged() = callback.onNotifyBookChanged()

        override fun sureNewProgress(progress: io.legado.app.data.entities.BookProgress) {}

        override fun onLayoutException(e: Throwable) = callback.onLayoutException(e)

        override fun cancelSelect() {}
    }
}

/** 从阅读配置读取排版参数快照。 */
private fun ReadBookConfig.snapshotStyle(): ReaderTextStyle = ReaderTextStyle(
    textSize = textSize,
    letterSpacing = letterSpacing,
    indentChars = paragraphIndent.count { it == ChapterProvider.indentChar[0] },
    lineSpacing = lineSpacingExtra,
    paragraphSpacing = paragraphSpacing,
    paddingLeft = paddingLeft,
    paddingTop = paddingTop,
    paddingRight = paddingRight,
    paddingBottom = paddingBottom,
    headerPaddingLeft = durConfig.headerPaddingLeft,
    headerPaddingTop = durConfig.headerPaddingTop,
    headerPaddingRight = durConfig.headerPaddingRight,
    headerPaddingBottom = durConfig.headerPaddingBottom,
    footerPaddingLeft = durConfig.footerPaddingLeft,
    footerPaddingTop = durConfig.footerPaddingTop,
    footerPaddingRight = durConfig.footerPaddingRight,
    footerPaddingBottom = durConfig.footerPaddingBottom,
)
