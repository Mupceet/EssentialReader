package io.legado.app.eink.bridge

import io.legado.app.constant.AppConst
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.eink.engine.BookHandle
import io.legado.app.eink.engine.BookPrepResult
import io.legado.app.eink.engine.EinkPageContent
import io.legado.app.eink.engine.ReaderBookSnapshot
import io.legado.app.eink.engine.ReaderEngine
import io.legado.app.eink.engine.ReaderEngineCallback
import io.legado.app.eink.engine.ReaderTipSpec
import io.legado.app.eink.reader.ReaderTextStyle
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
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import splitties.init.appCtx
import java.util.Date
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min

/** TextPage 的不透明包装（模块只读展示字段；画布槽位还原绘制）。 */
internal class TextPageContent(val textPage: TextPage) : EinkPageContent {
    override val title: String get() = textPage.title
    override val readProgress: String get() = textPage.readProgress
}

/** Book 快照实现。 */
internal class ReaderBookSnapshotImpl(override val handle: BookHandle) : ReaderBookSnapshot {
    val book: Book get() = (handle as BookHandleImpl).book
    override val bookUrl: String get() = book.bookUrl
    override val name: String get() = book.name
    override val author: String get() = book.author
    override val isLocal: Boolean get() = book.isLocal
    override val isNotShelf: Boolean get() = book.isType(BookType.notShelf)
}

/**
 * 阅读器端口实现：ReadBook 全局状态机 + ChapterProvider 排版引擎的
 * 纯转发（含 ReadBook.CallBack → 模块回调的适配与样式快照映射）。
 */
internal object ReaderEngineImpl : ReaderEngine {

    private fun Book.snapshot() = ReaderBookSnapshotImpl(BookHandleImpl(this))

    private fun snapshotOf(book: Book?): ReaderBookSnapshot? = book?.snapshot()

    // ---- 注册与生命周期 ----

    /** 按回调实例缓存适配器（ReadBook.unregister 用恒等比较，必须同一实例）。 */
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

    override fun saveRead() {
        ReadBook.saveRead()
    }

    // ---- 会话只读状态 ----

    override val sessionBook: ReaderBookSnapshot?
        get() = snapshotOf(ReadBook.book)

    override val sessionBookUrl: String?
        get() = ReadBook.book?.bookUrl

    override val chapterSize: Int
        get() = ReadBook.chapterSize

    override val durChapterIndex: Int
        get() = ReadBook.durChapterIndex

    override val durPageIndex: Int
        get() = ReadBook.durPageIndex

    override val engineMessage: String?
        get() = ReadBook.msg

    override val hasLaidOutPages: Boolean
        get() = ReadBook.curTextChapter?.pages?.isNotEmpty() == true

    override val currentChapterPageSize: Int
        get() = ReadBook.curTextChapter?.pageSize ?: 0

    override fun currentPage(): EinkPageContent? {
        val chapter = ReadBook.curTextChapter ?: return null
        return chapter.getPage(ReadBook.durPageIndex)?.let(::TextPageContent)
    }

    // ---- 会话控制 ----

    override fun upData(book: BookHandle) {
        ReadBook.upData((book as BookHandleImpl).book)
    }

    override fun resetData(book: BookHandle) {
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

    override fun upToc() {
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

    override suspend fun prepareBookData(bookHandle: BookHandle): BookPrepResult {
        val book = (bookHandle as BookHandleImpl).book
        if (!book.isLocal && book.tocUrl.isEmpty()) {
            val source = ReadBook.bookSource
                ?: return BookPrepResult.NoSource
            try {
                WebBook.getBookInfoAwait(source, book, canReName = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return BookPrepResult.InfoFailure(e)
            }
        }
        if (ReadBook.chapterSize == 0 || book.isLocalModified()) {
            return loadChapterListIntoDb(book)
        }
        return BookPrepResult.Success
    }

    /** 目录入库（本地书走 LocalBook；网络书重定向时替换记录并迁移缓存）。 */
    private suspend fun loadChapterListIntoDb(book: Book): BookPrepResult {
        if (book.isLocal) {
            return try {
                LocalBook.getChapterList(book).let { chapters ->
                    appDb.bookChapterDao.delByBook(book.bookUrl)
                    appDb.bookChapterDao.insert(*chapters.toTypedArray())
                    appDb.bookDao.update(book)
                    ReadBook.onChapterListUpdated(book)
                }
                BookPrepResult.Success
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                BookPrepResult.TocFailure(e)
            }
        }
        val source = ReadBook.bookSource
            ?: return BookPrepResult.NoSource
        val oldBook = book.copy()
        val chapters = try {
            WebBook.getChapterListAwait(source, book, true).getOrThrow()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return BookPrepResult.TocFailure(e)
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
        return BookPrepResult.Success
    }

    // ---- 翻页 ----

    override fun nextPage(): Boolean =
        ReadBook.moveToNextPage() || ReadBook.moveToNextChapter(upContent = true)

    override fun prevPage(): Boolean =
        ReadBook.moveToPrevPage() || ReadBook.moveToPrevChapter(upContent = true, toLast = true)

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

    // ---- 排版 ----

    override fun updateViewSize(width: Int, height: Int) {
        ChapterProvider.upViewSize(width, height)
    }

    override fun applyStyle(style: ReaderTextStyle) {
        ReadBookConfig.textSize = style.textSize
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

    override fun readTipVisibility(): ReaderTipSpec = ReaderTipSpec(
        headerVisible = when (ReadTipConfig.headerMode) {
            1 -> true
            2 -> false
            else -> ReadBookConfig.hideStatusBar
        },
        footerVisible = ReadTipConfig.footerMode != 1,
    )

    override fun formatTimeNow(): String =
        AppConst.timeFormat.format(Date()).toString()

    /** ReadBook.CallBack 适配（每回调实例缓存一份，恒等比较安全）。 */
    private class EngineCallBackAdapter(val callback: ReaderEngineCallback) : ReadBook.CallBack {

        override fun upMenuView() = callback.onUpMenuView()

        override fun loadChapterList(book: Book) {
            callback.onLoadChapterList(book.snapshot())
        }

        override fun upContent(
            relativePosition: Int,
            resetPageOffset: Boolean,
            success: (() -> Unit)?
        ) = callback.onUpContent(relativePosition, resetPageOffset, success)

        override suspend fun upContentAwait(
            relativePosition: Int,
            resetPageOffset: Boolean,
            success: (() -> Unit)?
        ) = callback.onUpContent(relativePosition, resetPageOffset, success)

        override fun pageChanged() = callback.onPageChanged()

        override fun contentLoadFinish() = callback.onContentLoadFinish()

        override fun upPageAnim(upRecorder: Boolean) {}

        override fun onLayoutException(e: Throwable) = callback.onLayoutException(e)

        override fun notifyBookChanged() = callback.onNotifyBookChanged()

        override fun sureNewProgress(progress: io.legado.app.data.entities.BookProgress) {}

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
