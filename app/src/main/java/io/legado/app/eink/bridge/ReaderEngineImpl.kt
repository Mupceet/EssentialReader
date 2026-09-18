package io.legado.app.eink.bridge

import android.graphics.Typeface
import io.legado.app.constant.AppConst
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.eink.contract.BookHandle
import io.legado.app.eink.contract.ReaderBookSnapshot
import io.legado.app.eink.contract.ReaderCloudProgress
import io.legado.app.eink.contract.ReaderEngine
import io.legado.app.eink.contract.ReaderEngineCallback
import io.legado.app.eink.contract.ReaderFontOption
import io.legado.app.eink.contract.ReaderFontSelection
import io.legado.app.eink.contract.ReaderHeaderFooterVisibility
import io.legado.app.eink.contract.ReaderPageSnapshot
import io.legado.app.eink.contract.ReaderPrepareResult
import io.legado.app.eink.contract.ReaderStyleCatalog
import io.legado.app.eink.contract.ReaderSyncTrigger
import io.legado.app.eink.contract.ReaderTextStyle
import io.legado.app.eink.contract.ReaderTipTypefaces
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
import io.legado.app.utils.dpToPx
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
 * 排版产物经 [ReaderPageSnapshotMapper] 映射为模块快照；云端进度同步
 * 编排经 [ReaderProgressSyncer] 落地。
 *
 * 本上游差异：
 *  - ReadBook.CallBack 为单轨全量接口（业务 + 渲染方法同轨，含
 *    LayoutProgressListener 的 onLayoutException/cancelSelect）——
 *    适配器实现一个接口即可，注册/注销单轨走；
 *  - 排版写路径为 ReadBookConfig/ReadTipConfig 直写 + save() +
 *    upStyle()（本宿主无只读化护栏与 ReadStyleGateway）；
 *  - 章节缓存走 CacheBook.start(context, book, start, end)（非 suspend）；
 *  - 标题字号为「正文 + 增量」模型（引擎 titlePaint = textSize+titleSize），
 *    契约 TITLE_SIZE 绝对值在映射期换算；正文字重仅 0/1/2 预设（引擎
 *    getPaints），契约自定义字重 100..900 就近量化；
 *  - 标题/页眉无独立字体与字重（引擎单字体），相关协商参数不进目录。
 */
internal object ReaderEngineImpl : ReaderEngine {

    init {
        // 引擎视口变化的重排闭环（完整模式 ReadBookActivity 的 UP_CONFIG
        // 观察同款）：宿主 upViewSize 对纯高度变化防抖 300ms，模块侧
        // updateViewSize 后的立即重排会以旧视口分页——UP_CONFIG[5]（视口
        // 尺寸已生效）到达时重载当前章，消除切状态栏/旋转后的旧分页错位
        // （正文越界进页脚带）。仅在 E-Ink 持有阅读回调时生效，完整模式
        // 由自身观察者处理，不双发。
        com.jeremyliao.liveeventbus.LiveEventBus
            .get(EventBus.UP_CONFIG, ArrayList::class.java)
            .observeForever { values ->
                if (ReadBook.callBack !is EngineCallBackAdapter) return@observeForever
                // 仅重排场景（已有排版产物；首次装载的 UP_CONFIG[5] 不重发，
                // 对齐完整模式 isInitFinish 守卫）
                if (ReadBook.curTextChapter == null) return@observeForever
                @Suppress("UNCHECKED_CAST")
                (values as? List<Int>)?.forEach { value ->
                    if (value == 5) {
                        ReadBook.loadContent(resetPageOffset = false)
                    }
                }
            }
    }

    private fun Book.snapshot() = ReaderBookSnapshotImpl(BookHandleImpl(this))

    private fun snapshotOf(book: Book?): ReaderBookSnapshot? = book?.snapshot()

    // ---- 注册与生命周期 ----

    /** 按回调实例缓存适配器（ReadBook 注销用恒等比较，必须同一实例）。 */
    private var cachedAdapter: EngineCallBackAdapter? = null

    private fun adapterFor(callback: ReaderEngineCallback): EngineCallBackAdapter =
        cachedAdapter?.takeIf { it.callback === callback }
            ?: EngineCallBackAdapter(callback).also { cachedAdapter = it }

    override fun register(callback: ReaderEngineCallback) {
        // 云端超前确认经同步器转发到当前注册回调（同步器内部转异步）
        ReaderProgressSyncer.onCloudProgressNewer = callback::onCloudProgressNewer
        ReadBook.register(adapterFor(callback))
    }

    override fun unregister(callback: ReaderEngineCallback) {
        val adapter = adapterFor(callback)
        if (ReadBook.callBack === adapter) {
            ReaderProgressSyncer.onCloudProgressNewer = null
            ReaderProgressSyncer.resetChapterJumped()
        }
        ReadBook.unregister(adapter)
    }

    override fun isRegistered(callback: ReaderEngineCallback): Boolean {
        val current = ReadBook.callBack
        return current is EngineCallBackAdapter && current.callback === callback
    }

    override fun saveReadingProgress() {
        ReadBook.saveRead()
    }

    // ---- 云端进度同步（ReaderProgressSyncer 编排） ----

    override fun syncCloudProgress(trigger: ReaderSyncTrigger) =
        ReaderProgressSyncer.sync(trigger)

    override fun applyCloudProgress(progress: ReaderCloudProgress) =
        ReaderProgressSyncer.applyCloudProgress(progress)

    override fun coverCloudProgress() =
        ReaderProgressSyncer.coverCloudProgress()

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

    override fun jumpToPosition(chapterIndex: Int, chapterPos: Int): Boolean {
        if (ReadBook.book == null || ReadBook.chapterSize <= 0) return false
        // 目录跳章标记：抑制随后 BookEntered 的进书同步（宿主 chapterChanged 同位）
        ReaderProgressSyncer.markChapterJumped()
        ReadBook.openChapter(
            chapterIndex.coerceIn(0, ReadBook.chapterSize - 1),
            durChapterPos = chapterPos,
        )
        return true
    }

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

    /**
     * 排版视口跨会话保留（ChapterProvider 为进程级单例，静态尺寸在
     * 阅读页重进时不重置），阅读页可跳过首帧尺寸等待。
     */
    override val isViewportReady: Boolean
        get() = ChapterProvider.viewWidth > 0

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
        // ---- 协商扩展参数：null = 不跨桥写（保持宿主值） ----
        style.bodyFont?.let(::applyBodyFont)
        style.bodyWeight?.let { ReadBookConfig.textBold = quantizeWeight(it) }
        // 标题字重不映射：引擎 textBold 单键同时决定标题/正文画笔（无独立
        // 标题字重键），联合映射会在冲突组合下让正文档失真——保持正文
        // 单键语义，标题字重行为完整模式同款副作用
        style.titleMode?.let { ReadBookConfig.titleMode = it }
        // 引擎标题字号 = textSize + titleSize（增量模型）：契约绝对值换算
        style.titleSize?.let { ReadBookConfig.titleSize = it - style.textSize }
        style.titleTopSpacing?.let { ReadBookConfig.titleTopSpacing = it }
        style.titleBottomSpacing?.let { ReadBookConfig.titleBottomSpacing = it }
        style.headerMode?.let { ReadTipConfig.headerMode = it }
        style.footerVisible?.let { ReadTipConfig.footerMode = if (it) 0 else 1 }
        style.headerDivider?.let { ReadBookConfig.showHeaderLine = it }
        style.footerDivider?.let { ReadBookConfig.showFooterLine = it }
        // 页眉/页脚字号不映射：宿主引擎无对应键，渲染为锁死档（引擎预留
        // 带推导 ≈ 12sp 对齐完整模式；可调档待模块设置行按目录守卫后落地）
        ReadBookConfig.save()
        // tip 内边距可能刚被写入：带宽随之同步（行高恒定，可用高度不变）
        syncTipReserves()
        ChapterProvider.upStyle()
    }

    /**
     * 契约自定义字重 100..900 → 引擎三预设（getPaints 的 textBold 域）：
     * 按与 {300 细, 400 常规, 900 粗} 的最近邻量化（边界 350/650）。
     */
    private fun quantizeWeight(value: Int): Int = when (value) {
        0, 1, 2 -> value
        else -> when {
            value >= 650 -> 1
            value <= 350 -> 2
            else -> 0
        }
    }

    override fun styleCatalog(): ReaderStyleCatalog = HostStyleCatalog.create()

    override suspend fun availableFonts(): List<ReaderFontOption> =
        ReaderFontFolder.listFonts()

    override suspend fun setFontFolder(uri: String) {
        ReaderFontFolder.setFolder(uri)
    }

    override fun currentStyle(): ReaderTextStyle = ReadBookConfig.snapshotStyle()

    override fun relayout() {
        ReadBook.clearTextChapter()
        val index = ReadBook.durChapterIndex
        ReadBook.removeLoading(index - 1)
        ReadBook.removeLoading(index)
        ReadBook.removeLoading(index + 1)
        ReadBook.loadContent(resetPageOffset = false)
    }

    // ---- 图片动作 ----

    // dispatchImageAction 保持默认无操作：本宿主引擎图片列无 click 选项
    // 解析（ImageColumn 仅地址），无段评查看的 JS 桥；槽位 action 恒 null，
    // 模块侧不参与点按命中——与完整模式「点击图片方式 = 禁用」同现象。

    // ---- 触控与页眉页脚 ----

    override val pageTouchSlop: Int
        get() = AppConfig.pageTouchSlop

    override fun headerFooterVisibility(): ReaderHeaderFooterVisibility =
        tipVisibility().also(::syncTipReserves)

    /** 页眉/页脚可见性（宿主 PageView.upTipStyle 同构：默认档页眉随「隐藏状态栏」接管/让位）。 */
    private fun tipVisibility() = ReaderHeaderFooterVisibility(
        headerVisible = when (ReadTipConfig.headerMode) {
            1 -> true
            2 -> false
            else -> ReadBookConfig.hideStatusBar
        },
        footerVisible = ReadTipConfig.footerMode != 1,
    )

    /**
     * E-Ink tip 带预留同步：带随可见性动态归零（完整模式 tip 为 View 层带，
     * 隐藏即让位正文；E-Ink 并入引擎边距实现同语义——正文起点 = 带底 +
     * 正文配置边距，带底即 extent 端口值，两侧严格一致）。
     *
     * 带宽 = tip 内边距 + 行高（页脚另加模块 2dp 进度条）——模块在带内
     * 扣除同样的内边距后可用高度恒为行高值，推导字号恒 ≈12sp（完整模式
     * tip 文字同源），不受用户 tip 内边距配置影响。
     *
     * 同步点：headerFooterVisibility（VM init/翻页/开关切换）、applyStyle
     * （tip 内边距写入后）、EInkBridge.install。upLayout 幂等（视口未
     * 就绪时安全跳过，后续 updateViewSize 会带上预留重算）。
     */
    internal fun syncTipReserves(visibility: ReaderHeaderFooterVisibility = tipVisibility()) {
        val config = ReadBookConfig.durConfig
        ChapterProvider.einkHeaderReserveDp = if (visibility.headerVisible) {
            config.headerPaddingTop + EInkBridge.TIP_ROW_DP + config.headerPaddingBottom
        } else {
            0
        }
        ChapterProvider.einkFooterReserveDp = if (visibility.footerVisible) {
            EInkBridge.TIP_PROGRESS_BAR_DP +
                config.footerPaddingTop + EInkBridge.TIP_ROW_DP + config.footerPaddingBottom
        } else {
            0
        }
        ChapterProvider.upLayout()
    }

    /**
     * 页眉/页脚装饰预留高度（px，排版坐标系）：与引擎布局消费的带预留同值
     * （[syncTipReserves]）——模块页眉页脚按此高度定高叠加在画布顶部/底部，
     * 带底即正文排版基线（正文配置边距在带下另算，与完整模式「View 层带 +
     * 内容区内边距」的叠加几何一致）；带隐藏时为 0，正文随之上移。
     */
    override val headerDecorationExtentPx: Float
        get() = ChapterProvider.einkHeaderReserveDp.dpToPx().toFloat()

    override val footerDecorationExtentPx: Float
        get() = ChapterProvider.einkFooterReserveDp.dpToPx().toFloat()

    /**
     * 页眉/页脚有效字体：本宿主无独立页眉/页脚字体键——按契约
     * 「跟随正文」链解析即引擎正文画笔当前字体（含 textFont 文件字体与
     * systemTypefaces 系统预设的解析结果）。
     */
    override fun headerFooterTypefaces(): ReaderTipTypefaces {
        val typeface: Typeface? = ChapterProvider.contentPaint.typeface
        return ReaderTipTypefaces(header = typeface, footer = typeface)
    }

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

/** 从阅读配置读取排版参数快照（含协商扩展参数读回）。 */
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
    bodyFont = currentBodyFont(),
    bodyWeight = textBold,
    titleMode = titleMode,
    // 引擎增量模型读回为绝对值
    titleSize = textSize + titleSize,
    titleTopSpacing = titleTopSpacing,
    titleBottomSpacing = titleBottomSpacing,
    // 宿主 0 档（随状态栏）读回 null：不管理
    headerMode = ReadTipConfig.headerMode.takeIf { it != 0 },
    footerVisible = ReadTipConfig.footerMode != 1,
    headerDivider = showHeaderLine,
    footerDivider = showFooterLine,
)

/**
 * 正文字体：文件路径直写 textFont；系统预设清空 textFont 并写宿主
 * systemTypefaces 键（0 无衬线 / 1 衬线 / 2 等宽，与完整模式
 * FontSelectDialog 的系统字体菜单同键）。
 */
private fun applyBodyFont(selection: ReaderFontSelection) {
    when (selection) {
        is ReaderFontSelection.File -> ReadBookConfig.textFont = selection.path
        ReaderFontSelection.Sans -> {
            ReadBookConfig.textFont = ""
            AppConfig.systemTypefaces = 0
        }

        ReaderFontSelection.Serif -> {
            ReadBookConfig.textFont = ""
            AppConfig.systemTypefaces = 1
        }

        ReaderFontSelection.Mono -> {
            ReadBookConfig.textFont = ""
            AppConfig.systemTypefaces = 2
        }

        // FollowBody 仅对标题/页眉合法，正文收到时不写
        ReaderFontSelection.FollowBody -> Unit
    }
}

private fun currentBodyFont(): ReaderFontSelection {
    val path = ReadBookConfig.textFont
    if (path.isNotEmpty()) return ReaderFontSelection.File(path)
    return when (AppConfig.systemTypefaces) {
        1 -> ReaderFontSelection.Serif
        2 -> ReaderFontSelection.Mono
        else -> ReaderFontSelection.Sans
    }
}
