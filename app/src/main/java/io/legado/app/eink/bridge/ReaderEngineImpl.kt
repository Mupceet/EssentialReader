package io.legado.app.eink.bridge

import android.graphics.Typeface
import androidx.core.net.toUri
import io.legado.app.constant.AppConst
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.ReadSettingsRepository
import io.legado.app.domain.gateway.ReadStyleGateway
import io.legado.app.eink.contract.BookHandle
import io.legado.app.eink.contract.ReaderBookSnapshot
import io.legado.app.eink.contract.ReaderEngine
import io.legado.app.eink.contract.ReaderEngineCallback
import io.legado.app.eink.contract.ReaderFontOption
import io.legado.app.eink.contract.ReaderFontSelection
import io.legado.app.eink.contract.ReaderHeaderFooterVisibility
import io.legado.app.eink.contract.ReaderPageSnapshot
import io.legado.app.eink.contract.ReaderPrepareResult
import io.legado.app.eink.contract.ReaderStyleCatalog
import io.legado.app.eink.contract.ReaderTextStyle
import io.legado.app.eink.contract.ReaderTipTypefaces
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.addType
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalModified
import io.legado.app.help.book.isType
import io.legado.app.help.book.removeType
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.loadFontFiles
import io.legado.app.model.CacheBook
import io.legado.app.model.ReadBook
import io.legado.app.model.cache.CacheDownloadRequest
import io.legado.app.model.cache.ChapterSelection
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.feature.reader.legacy.LegacyReaderPageDecorationFactory
import io.legado.app.ui.config.readConfig.ReadConfig
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import splitties.init.appCtx
import java.util.Date
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
 * 阅读器端口实现：ReadBook 全局状态机 + 排版引擎的
 * 纯转发（含宿主双轨回调 → 模块回调的适配与样式快照映射）。
 * 排版产物由 [ReaderChapterPager] 消费章节输入排出，经
 * [ReaderPageSnapshotMapper] 映射为模块快照。
 *
 * 本上游差异：
 *  - ReadBook.CallBack 只剩 4 个业务方法，渲染回调拆在
 *    ReadBook.ReaderRenderCallback——适配器同时实现两个接口，
 *    注册/注销两轨都走；排版异常不再走 LayoutProgressListener，
 *    改由分页方经 onPagesError 上报；
 *  - durPageIndex 由分页快照派生（pager 分页后回填快照，本端口只读）；
 *  - 排版产物（ReaderPage）随上游重写移入渲染层，E-Ink 不宿主
 *    Compose 渲染层，分页由 ReaderChapterPager 承担；
 *  - 翻页走 moveToNextPage/moveToPrevPage；
 *  - ReadBookConfig 全面只读化，本仓架构护栏（:verifyConfigArchitecture）
 *    禁止直写 —— 写路径统一为 ReadStyleGateway.updateCurrentStyle
 *    （mutation 逐键提交，updateCurrentStyle 只改内存 + publishState，
 *    最后显式 save() 落盘）。
 */
internal object ReaderEngineImpl : ReaderEngine, KoinComponent {

    private val readStyleGateway: ReadStyleGateway by inject()
    private val readSettingsRepository: ReadSettingsRepository by inject()

    private val chapterPager = ReaderChapterPager(
        onPagesReady = ::notifyPagesReady,
        onPagesError = ::notifyPagesError,
    )

    private fun notifyPagesReady() {
        // 与 ReadBook 的 upContent 同型：页面对象可能未换（同页重排），靠
        // pageVersion 强制模块画布重绘
        cachedAdapter?.callback?.onContentUpdated(
            relativePosition = 0,
            resetPageOffset = false,
            success = null,
        )
    }

    private fun notifyPagesError(error: Throwable) {
        cachedAdapter?.callback?.onLayoutException(error)
    }

    private fun Book.snapshot() = ReaderBookSnapshotImpl(BookHandleImpl(this))

    private fun snapshotOf(book: Book?): ReaderBookSnapshot? = book?.snapshot()

    // ---- 注册与生命周期 ----

    /** 按回调实例缓存适配器（ReadBook 注销两轨均用恒等比较，必须同一实例）。 */
    private var cachedAdapter: EngineCallBackAdapter? = null

    private fun adapterFor(callback: ReaderEngineCallback): EngineCallBackAdapter =
        cachedAdapter?.takeIf { it.callback === callback }
            ?: EngineCallBackAdapter(callback).also { cachedAdapter = it }

    override fun register(callback: ReaderEngineCallback) {
        val adapter = adapterFor(callback)
        ReadBook.register(adapter)
        ReadBook.registerRender(adapter)
    }

    override fun unregister(callback: ReaderEngineCallback) {
        val adapter = adapterFor(callback)
        ReadBook.unregisterRender(adapter)
        ReadBook.unregister(adapter)
        // 不清分页缓存：目录等界面离开阅读页会销毁 ViewModel 触发 unregister，
        // 返回时要靠热缓存即时恢复渲染（清了就会出现「加载中」再重排）；
        // 只取消在途分页，防止迟到的 commit 通知已销毁的回调
        chapterPager.cancelPending()
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
        get() = chapterPager.hasPages

    override val currentChapterPageSize: Int
        get() = chapterPager.currentChapterPageSize

    override fun currentPage(): ReaderPageSnapshot? {
        return chapterPager.currentPageSnapshot()
    }

    // ---- 会话控制 ----

    override fun loadBook(book: BookHandle) {
        val b = (book as BookHandleImpl).book
        // 模块从目录/换源返回时对同一本书也会重走 loadBook：同书不清分页
        // 缓存，返回阅读页直接用热缓存渲染；换书必须清，防止旧书页残留
        if (ReadBook.book?.bookUrl != b.bookUrl) {
            chapterPager.clear()
        }
        ReadBook.upData(b)
    }

    override fun reloadBook(book: BookHandle) {
        val b = (book as BookHandleImpl).book
        if (ReadBook.book?.bookUrl != b.bookUrl) {
            chapterPager.clear()
        }
        ReadBook.resetData(b)
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
        readSettingsRepository.setAutoReadSpeed(value)
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
        // 本上游 CacheBook.start(book, start, end) 为 suspend，等价改为
        // 直接构造请求走非 suspend 重载（与宿主实现一致）
        CacheBook.start(
            appCtx,
            CacheDownloadRequest(
                bookUrl = book.bookUrl,
                selection = ChapterSelection.Range(ReadBook.durChapterIndex, end),
            ),
            isLocal = book.isLocal,
        )
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
        chapterPager.updateViewport(width, height)
    }

    override fun applyStyle(style: ReaderTextStyle) {
        val mutations = buildStyleMutations(
            style = style,
            currentBodyFontPath = ReadBookConfig.durConfig.textFont,
        )
        mutations.forEach(readStyleGateway::updateCurrentStyle)
        // 正文系统预设写入全局 systemTypefaces（ReadSettings，非样式键）；
        // 同值重复写无害，DataStore 异步落盘。必须 UNDISPATCHED（与
        // ReadStyleDelegate.selectSystemTypeface 同款）：onStyleChanged 的
        // 分页工厂同步读 systemTypefaces，内存值须在其前就位
        // （setSystemTypefaces 的 putInt 内存层同步生效，磁盘异步不受影响）
        when (style.bodyFont) {
            ReaderFontSelection.Sans -> styleScope.launch(start = CoroutineStart.UNDISPATCHED) { readSettingsRepository.setSystemTypefaces(0) }
            ReaderFontSelection.Serif -> styleScope.launch(start = CoroutineStart.UNDISPATCHED) { readSettingsRepository.setSystemTypefaces(1) }
            ReaderFontSelection.Mono -> styleScope.launch(start = CoroutineStart.UNDISPATCHED) { readSettingsRepository.setSystemTypefaces(2) }
            else -> Unit
        }
        readStyleGateway.save()
        chapterPager.onStyleChanged()
    }

    override fun currentStyle(): ReaderTextStyle = ReadBookConfig.snapshotStyle()

    /** 从阅读配置读取排版参数快照。页眉模式读宿主 0 档（随状态栏）时
     *  保持 null（不管理）；footer 仅显/隐两态，0 档读回 true。 */
    private fun ReadBookConfig.snapshotStyle(): ReaderTextStyle = ReaderTextStyle(
        textSize = textSize,
        letterSpacing = letterSpacing,
        indentChars = paragraphIndent.count { it == INDENT_CHAR[0] },
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
        bodyWeight = durConfig.textBold,
        titleWeight = durConfig.titleBold,
        titleMode = titleMode,
        titleSize = durConfig.titleSize,
        titleTopSpacing = durConfig.titleTopSpacing,
        titleBottomSpacing = durConfig.titleBottomSpacing,
        titleLineSpacing = durConfig.titleLineSpacingExtra,
        bodyFont = bodyFontSelection(durConfig.textFont),
        titleFont = followBodyIfSame(durConfig.titleFont, durConfig.textFont),
        headerFont = followBodyIfSame(durConfig.headerFont, durConfig.textFont),
        headerSize = durConfig.headerFontSize,
        footerSize = durConfig.footerFontSize,
        headerDivider = durConfig.showHeaderLine,
        footerDivider = durConfig.showFooterLine,
        headerMode = when (durConfig.headerMode) {
            1 -> 1
            2 -> 2
            else -> null
        },
        footerVisible = when (durConfig.footerMode) {
            1 -> false
            else -> true
        },
    )

    /** 正文正文字体取值：有文件用文件，否则按全局 systemTypefaces 映射系统预设。 */
    private fun bodyFontSelection(path: String): ReaderFontSelection = if (path.isNotBlank()) {
        ReaderFontSelection.File(path)
    } else {
        when (readSettingsRepository.currentSettings.systemTypefaces) {
            1 -> ReaderFontSelection.Serif
            2 -> ReaderFontSelection.Mono
            else -> ReaderFontSelection.Sans
        }
    }

    /** 与正文路径相同（含同为空）读回 FollowBody：跟随语义不被首次往返
     * 展开成的显式路径打散；完整模式单独设的相同字体文件同样收拢为
     * 跟随（字体一致，无行为差异）。 */
    private fun followBodyIfSame(path: String, bodyPath: String): ReaderFontSelection =
        if (path.isBlank() || path == bodyPath) {
            ReaderFontSelection.FollowBody
        } else {
            ReaderFontSelection.File(path)
        }

    override fun relayout() {
        // 不清分页缓存：重排是否发生由缓存键决定（内容 hash/排版样式/视口任一
        // 变化才会重排）。模块在重入阅读页和尺寸回调处都会调 relayout，清了
        // 缓存就会把无变化的重排变成「清屏→全量重排」的抖动；样式变更已由
        // applyStyle 的 onStyleChanged 走键失效，无需在此强清
        ReadBook.clearTextChapter()
        val index = ReadBook.durChapterIndex
        ReadBook.removeLoading(index - 1)
        ReadBook.removeLoading(index)
        ReadBook.removeLoading(index + 1)
        ReadBook.loadContent(resetPageOffset = false)
    }

    // ---- 触控与页眉页脚 ----

    override val pageTouchSlop: Int
        get() = ReadConfig.pageTouchSlop

    override fun headerFooterVisibility(): ReaderHeaderFooterVisibility =
        ReaderHeaderFooterVisibility(
            headerVisible = when (ReadBookConfig.headerMode) {
                1 -> true
                2 -> false
                else -> ReadBookConfig.hideStatusBar
            },
            footerVisible = ReadBookConfig.footerMode != 1,
        )

    override val headerDecorationExtentPx: Float
        get() = LegacyReaderPageDecorationFactory.headerExtentPx()

    override val footerDecorationExtentPx: Float
        get() = LegacyReaderPageDecorationFactory.footerExtentPx()

    override fun headerFooterTypefaces(): ReaderTipTypefaces {
        val config = ReadBookConfig.config
        val systemTypefaces = readSettingsRepository.currentSettings.systemTypefaces
        val header = resolveHeaderTipFont(config.headerFont, config.textFont, systemTypefaces)
        val footer = resolveFooterTipFont(
            footerFont = config.footerFont,
            applyHeaderStyle = config.applyHeaderStyle,
            headerResolved = header,
            textFont = config.textFont,
            systemTypefaces = systemTypefaces,
        )
        fun load(font: TipFont?): Typeface? = when (font) {
            is TipFont.File -> loadTipTypeface(font.path)
            is TipFont.Preset -> presetTypeface(font.index)
            null -> null
        }
        return ReaderTipTypefaces(header = load(header), footer = load(footer))
    }

    override fun styleCatalog(): ReaderStyleCatalog = HostStyleCatalog.create()

    override suspend fun availableFonts(): List<ReaderFontOption> {
        val folder = readSettingsRepository.currentSettings.fontFolder
            .takeIf { it.isNotEmpty() }
            ?.toUri()
        return loadFontFiles(appCtx, folder).map { ReaderFontOption(name = it.name, path = it.uri.toString()) }
    }

    override suspend fun setFontFolder(uri: String) {
        readSettingsRepository.setFontFolder(uri)
    }

    override fun formatTimeNow(): String =
        AppConst.timeFormat.format(Date()).toString()

    /** 排版副作用作用域：applyStyle 内的异步全局设置写入（systemTypefaces）。 */
    private val styleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 宿主双轨回调适配（每回调实例缓存一份，恒等比较安全）：
     * 业务轨 ReadBook.CallBack（4 方法）+ 渲染轨 ReaderRenderCallback
     * （含 LayoutProgressListener 的 onLayoutException）。
     */
    private class EngineCallBackAdapter(val callback: ReaderEngineCallback) :
        ReadBook.CallBack, ReadBook.ReaderRenderCallback {

        // ---- ReadBook.CallBack（业务轨）----

        override fun upMenuView() = callback.onRequestShowMenu()

        override fun loadChapterList(book: Book) {
            callback.onLoadChapterList(
                ReaderBookSnapshotImpl(BookHandleImpl(book))
            )
        }

        override fun notifyBookChanged() = callback.onNotifyBookChanged()

        override fun sureNewProgress(progress: io.legado.app.data.entities.BookProgress) {}

        // ---- ReaderRenderCallback（渲染轨）----

        override fun upContent(
            relativePosition: Int,
            resetPageOffset: Boolean,
            success: (() -> Unit)?,
        ) {
            // 跨章翻页只平移输入窗口不触发回调：每次渲染前先对账，
            // 新章输入已在窗口时立即补分页（键未变时为空操作）
            chapterPager.syncWithWindow()
            callback.onContentUpdated(relativePosition, resetPageOffset, success)
        }

        override suspend fun upContentAwait(
            relativePosition: Int,
            resetPageOffset: Boolean,
            success: (() -> Unit)?,
        ) {
            chapterPager.syncWithWindow()
            callback.onContentUpdated(relativePosition, resetPageOffset, success)
        }

        override fun pageChanged() {
            chapterPager.syncWithWindow()
            callback.onPageChanged()
        }

        override fun contentLoadFinish() {
            chapterPager.syncWithWindow()
            callback.onContentLoadFinish()
        }

        override fun upPageAnim(upRecorder: Boolean) {}

        override fun cancelSelect() {}

        // ---- 章节输入就绪（分页协调器的排版时机） ----

        override fun readerChapterInputChanged() {
            chapterPager.onChapterInputChanged()
        }
    }
}
