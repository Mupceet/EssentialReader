package io.legado.app.eink.reader

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.constant.AppConst
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import io.legado.app.eink.arch.UserMessage
import io.legado.app.R
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.addType
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalModified
import io.legado.app.help.book.isType
import io.legado.app.help.book.removeType
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ReadTipConfig
import io.legado.app.model.CacheBook
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import splitties.init.appCtx
import java.util.Date
import kotlin.math.min

/** 排版参数快照（渲染 + 设置面板双用途）。 */
data class ReaderTextStyle(
    val textSize: Int = 20,            // sp
    val letterSpacing: Float = 0.1f,   // em
    val indentChars: Int = 2,          // 缩进字符数
    val lineSpacing: Int = 12,         // x0.1 倍行高
    val paragraphSpacing: Int = 2,     // x0.1 行高
    val paddingLeft: Int = 16,         // 正文左边距 dp
    val paddingTop: Int = 6,           // 正文上边距 dp
    val paddingRight: Int = 16,        // 正文右边距 dp
    val paddingBottom: Int = 6,        // 正文下边距 dp
    val headerPaddingLeft: Int = 16,   // 页眉左边距 dp
    val headerPaddingTop: Int = 0,     // 页眉上边距 dp
    val headerPaddingRight: Int = 16,  // 页眉右边距 dp
    val headerPaddingBottom: Int = 0,  // 页眉下边距 dp
    val footerPaddingLeft: Int = 16,   // 页脚左边距 dp
    val footerPaddingTop: Int = 6,     // 页脚上边距 dp
    val footerPaddingRight: Int = 16,  // 页脚右边距 dp
    val footerPaddingBottom: Int = 6,  // 页脚下边距 dp
)

/**
 * 阅读器 UiState。
 *
 * 状态是离散的（规范 §49: page = 100，而不是 pageAnimationProgress = 0.73）。
 * [textPage] 为 View 版排版引擎产物（ChapterProvider 坐标系），由
 * [ReaderPageCanvas] 直接按行/列坐标绘制；[pageVersion] 变化驱动重绘。
 */
data class ReaderUiState(
    val bookName: String = "",
    val chapterTitle: String = "",
    val textPage: TextPage? = null,
    val pageVersion: Int = 0,
    val pageIndex: Int = 0,
    val pageCount: Int = 0,
    val chapterIndex: Int = 0,
    val chapterSize: Int = 0,
    val readProgress: String = "",
    val isLoading: Boolean = true,
    val error: String? = null,
    val controlsVisible: Boolean = false,
    val autoPlay: Boolean = false,
    val autoPlayIntervalSec: Int = DEFAULT_AUTO_INTERVAL_SEC,
    val isLocalBook: Boolean = false,
    val inBookshelf: Boolean = false,
    val keepScreenOn: Boolean = false,
    val textBold: Boolean = false,
    val style: ReaderTextStyle = ReaderTextStyle(),
    // 页眉/页脚信息（按 View 版 ReadTipConfig 规则渲染，不开放设置）
    val headerVisible: Boolean = false,
    val footerVisible: Boolean = true,
    val headerTime: String = "",
    val batteryPercent: Int = 100,
) {
    /** 页码显示文本，如 "3/15" */
    val pageIndicator: String
        get() = if (pageCount > 0) "${pageIndex + 1}/$pageCount" else ""

    /** 页数及进度（View 版 pageAndTotal 格式），如 "3/15  12.3%" */
    val pageAndTotal: String
        get() = buildString {
            if (pageIndicator.isNotEmpty()) append(pageIndicator)
            if (readProgress.isNotEmpty()) {
                if (isNotEmpty()) append("  ")
                append(readProgress)
            }
        }
}

/**
 * 阅读器 ViewModel。
 *
 * 桥接 [ReadBook] 全局状态机到 Compose StateFlow：
 * - 复用 View 版渲染引擎（ChapterProvider 排版 + TextPage 分页结果）；
 * - 排版参数直接写回 [ReadBookConfig]，与 View 版共用一套阅读配置；
 * - 通过实现 [ReadBook.CallBack] 接收引擎状态推送。
 */
class ReaderViewModel(application: Application) : AndroidViewModel(application), ReadBook.CallBack {

    companion object {
        private const val PREF_KEEP_SCREEN_ON = "einkReaderKeepScreenOn"
        private const val PREF_AUTO_INTERVAL = "einkReaderAutoIntervalSec"
    }

    private val _uiState = MutableStateFlow(
        ReaderUiState(
            keepScreenOn = appCtx.getPrefBoolean(PREF_KEEP_SCREEN_ON),
            autoPlayIntervalSec = appCtx.getPrefInt(PREF_AUTO_INTERVAL, DEFAULT_AUTO_INTERVAL_SEC)
                .coerceIn(MIN_AUTO_INTERVAL_SEC, MAX_AUTO_INTERVAL_SEC),
            textBold = ReadBookConfig.textBold == 1,
            style = ReadBookConfig.snapshotStyle(),
        )
    )
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private val _messages = MutableSharedFlow<UserMessage>()
    val messages: SharedFlow<UserMessage> = _messages.asSharedFlow()

    /** 引擎当前会话已加载的书籍（换源后与路由参数不同，用于识别并采用新书） */
    private var loadedBookUrl: String? = null
    private var attachJob: Job? = null
    private var autoPlayJob: Job? = null

    /** 阅读区尺寸就绪信号（首帧 onSizeChanged 完成） */
    private val viewSizeReady = CompletableDeferred<Unit>()
    private var lastViewWidth = 0
    private var lastViewHeight = 0
    private var relayoutJob: Job? = null

    // ==================== 生命周期与加载 ====================

    /**
     * 绑定阅读会话。全屏首次进入、以及从目录/换源界面返回时都会调用，
     * 需保证幂等：同一书籍仅刷新状态，不重复重建引擎会话。
     */
    fun attach(bookUrl: String) {
        if (attachJob?.isActive == true) return
        attachJob = viewModelScope.launch(Dispatchers.IO) {
            ReadBook.register(this@ReaderViewModel)

            // 首次排版前必须拿到阅读区真实尺寸：ChapterProvider 宽高为 0 时
            // visibleWidth 为负数，StaticLayout 会直接抛异常，导致 upContent
            // 永远不会回调（界面停留在加载中）
            withTimeoutOrNull(VIEW_SIZE_TIMEOUT_MS) { viewSizeReady.await() }

            // 换源完成后引擎持有新书（bookUrl 与路由参数不同），优先采用；
            // 未加书架的搜索书（详情页直接阅读）转 Book 入库（notShelf），
            // 与 View 版"未加书架直接阅读"行为一致
            val book = ReadBook.book
                ?.takeIf { loadedBookUrl != null && it.bookUrl != loadedBookUrl }
                ?: appDb.bookDao.getBook(bookUrl)
                ?: appDb.searchBookDao.getSearchBook(bookUrl)?.toBook()?.apply {
                    addType(BookType.notShelf)
                    save()
                }
            if (book == null) {
                _uiState.update {
                    it.copy(isLoading = false, error = "书籍不存在")
                }
                return@launch
            }

            if (ReadBook.book?.bookUrl == book.bookUrl) {
                ReadBook.upData(book)
                // 已有排版页面（含流式排版中）直接刷新展示
                if (ReadBook.curTextChapter?.pages?.isNotEmpty() == true) {
                    loadedBookUrl = book.bookUrl
                    syncBookState(book)
                    upContent()
                    return@launch
                }
                // 章节跳转（目录选章等）：清空旧页面显示加载中，
                // 内容未缓存时由下载完成的 upContent 回调刷新
                _uiState.update { it.copy(isLoading = true, textPage = null) }
            } else {
                ReadBook.resetData(book)
                _uiState.update { it.copy(isLoading = true, textPage = null) }
            }
            loadedBookUrl = book.bookUrl
            syncBookState(book)

            // 前置数据初始化：E-Ink 经详情页加入书架的书没有目录记录，
            // 目录缺失时引擎的 loadContent 会静默失败（getChapter 返回 null）
            if (!initBookData(book)) {
                return@launch
            }
            // 拉目录可能重定向 bookUrl，以引擎最终持有的书为准
            loadedBookUrl = ReadBook.book?.bookUrl ?: book.bookUrl

            ReadBook.upMsg(null)
            ReadBook.loadContent(resetPageOffset = true)
        }
    }

    /**
     * 补齐阅读前置数据（复刻 View 版 ReadBookViewModel.initBook 的关键步骤）：
     * 1. 网络书缺 tocUrl → 拉详情；
     * 2. 目录缺失/本地书变更 → 重新拉取目录并入库。
     *
     * @return false 表示失败（已把错误写入 UiState）
     */
    private suspend fun initBookData(book: Book): Boolean {
        if (!book.isLocal && book.tocUrl.isEmpty()) {
            val source = ReadBook.bookSource ?: run {
                _uiState.update { it.copy(isLoading = false, error = "没有书源") }
                return false
            }
            kotlin.runCatching {
                WebBook.getBookInfoAwait(source, book, canReName = false)
            }.onFailure { e ->
                _uiState.update { state ->
                    state.copy(isLoading = false, error = "详情页出错：${e.localizedMessage}")
                }
                return false
            }
        }
        if (ReadBook.chapterSize == 0 || book.isLocalModified()) {
            if (!loadChapterListIntoDb(book)) {
                return false
            }
        }
        return true
    }

    /**
     * 加载目录入库（复刻 View 版 ReadBookViewModel.loadChapterListAwait，
     * 命名区分于 [ReadBook.CallBack.loadChapterList] 回调）。
     */
    private suspend fun loadChapterListIntoDb(book: Book): Boolean {
        if (book.isLocal) {
            val result = kotlin.runCatching {
                LocalBook.getChapterList(book).let { chapters ->
                    appDb.bookChapterDao.delByBook(book.bookUrl)
                    appDb.bookChapterDao.insert(*chapters.toTypedArray())
                    appDb.bookDao.update(book)
                    ReadBook.onChapterListUpdated(book)
                }
            }
            if (result.isFailure) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "目录加载失败：${result.exceptionOrNull()?.localizedMessage}"
                    )
                }
                return false
            }
            return true
        }
        val source = ReadBook.bookSource ?: run {
            _uiState.update { it.copy(isLoading = false, error = "没有书源") }
            return false
        }
        val oldBook = book.copy()
        val chapters = WebBook.getChapterListAwait(source, book, true).getOrElse { e ->
            _uiState.update {
                it.copy(isLoading = false, error = "目录加载失败：${e.localizedMessage}")
            }
            return false
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
        return true
    }

    /**
     * 阅读区域尺寸回调（首帧布局/旋转）。
     * 唤醒 attach 的尺寸等待；若引擎已加载内容则清空章节缓存重新排版
     * （覆盖超时后仍以 0 尺寸加载失败的自愈场景）。
     */
    fun updateViewSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width == lastViewWidth && height == lastViewHeight) return
        lastViewWidth = width
        lastViewHeight = height
        viewModelScope.launch(Dispatchers.IO) {
            ChapterProvider.upViewSize(width, height)
            viewSizeReady.complete(Unit)
            if (loadedBookUrl != null) {
                relayoutEngine()
            }
        }
    }

    private fun syncBookState(book: Book) {
        val inBookshelf = !book.isType(BookType.notShelf)
        ReadBook.inBookshelf = inBookshelf
        _uiState.update {
            it.copy(
                bookName = book.name,
                isLocalBook = book.isLocal,
                inBookshelf = inBookshelf,
            )
        }
        updateTipInfo()
    }

    /**
     * 刷新页眉/页脚信息（复刻 View 版 PageView.upTipStyle 的可见性与默认内容，
     * 配置读取 ReadTipConfig，不开放设置）：
     * - 页眉：headerMode 1 强制显示 / 2 强制隐藏 / 默认状态栏显示时隐藏，
     *   内容为 时间（左）+ 电量%（右）
     * - 页脚：默认显示（footerMode 1 隐藏），内容为 章节标题（左）+ 页数及进度（右）
     *
     * 时间/电量随页面状态更新刷新（翻页时刻），不做周期性重组。
     */
    private fun updateTipInfo() {
        val app = getApplication<Application>()
        val batteryIntent = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val battery = if (level >= 0 && scale > 0) level * 100 / scale else 100
        _uiState.update {
            it.copy(
                headerVisible = when (ReadTipConfig.headerMode) {
                    1 -> true
                    2 -> false
                    else -> ReadBookConfig.hideStatusBar
                },
                footerVisible = ReadTipConfig.footerMode != 1,
                headerTime = AppConst.timeFormat.format(Date()).toString(),
                batteryPercent = battery,
            )
        }
    }

    // ==================== 翻页 ====================

    fun nextPage(): Boolean {
        return ReadBook.moveToNextPage() || ReadBook.moveToNextChapter(upContent = true)
    }

    fun prevPage(): Boolean {
        return ReadBook.moveToPrevPage() || ReadBook.moveToPrevChapter(upContent = true, toLast = true)
    }

    // ==================== 操作条 ====================

    fun toggleControls() {
        _uiState.update { it.copy(controlsVisible = !it.controlsVisible) }
    }

    fun hideControls() {
        _uiState.update { if (it.controlsVisible) it.copy(controlsVisible = false) else it }
    }

    /** 自动翻页：固定间隔翻下一页，翻到书尾自动停止。 */
    fun toggleAutoPlay() {
        if (_uiState.value.autoPlay) {
            stopAutoPlay()
            return
        }
        _uiState.update { it.copy(autoPlay = true) }
        autoPlayJob = viewModelScope.launch {
            while (isActive) {
                delay(_uiState.value.autoPlayIntervalSec * 1000L)
                if (!nextPage()) {
                    stopAutoPlay()
                    _messages.emit(UserMessage.from(R.string.eink_reader_auto_page_end))
                    break
                }
            }
        }
    }

    private fun stopAutoPlay() {
        autoPlayJob?.cancel()
        autoPlayJob = null
        _uiState.update { it.copy(autoPlay = false) }
    }

    fun adjustAutoPlayInterval(deltaSec: Int) {
        _uiState.update {
            val value = (it.autoPlayIntervalSec + deltaSec)
                .coerceIn(MIN_AUTO_INTERVAL_SEC, MAX_AUTO_INTERVAL_SEC)
            appCtx.putPrefInt(PREF_AUTO_INTERVAL, value)
            it.copy(autoPlayIntervalSec = value)
        }
    }

    // ==================== 顶部操作条动作 ====================

    /** 刷新当前章节：清除缓存后重新加载。 */
    fun refreshChapter() {
        viewModelScope.launch(Dispatchers.IO) {
            val book = ReadBook.book ?: return@launch
            appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)?.let { chapter ->
                BookHelp.delContent(book, chapter)
            }
            ReadBook.loadContent(ReadBook.durChapterIndex, resetPageOffset = false)
        }
    }

    /**
     * 缓存章节（当前章起往后）。
     * @param count 向后缓存的章节数；[CACHE_ALL] 表示全本
     */
    fun cacheChapters(count: Int) {
        val book = ReadBook.book ?: return
        if (book.isLocal) {
            viewModelScope.launch { _messages.emit(UserMessage.from(R.string.eink_reader_local_no_cache)) }
            return
        }
        val end = if (count == CACHE_ALL) {
            book.totalChapterNum - 1
        } else {
            min(ReadBook.durChapterIndex + count, book.totalChapterNum - 1)
        }
        CacheBook.start(getApplication(), book, ReadBook.durChapterIndex, end)
        viewModelScope.launch { _messages.emit(UserMessage.from(R.string.eink_reader_cache_started)) }
    }

    /** 添加/移出书架。 */
    fun toggleBookshelf() {
        val book = ReadBook.book ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                if (book.isType(BookType.notShelf)) {
                    book.removeType(BookType.notShelf)
                    if (book.order == 0) {
                        book.order = appDb.bookDao.minOrder - 1
                    }
                    ReadBook.inBookshelf = true
                } else {
                    book.addType(BookType.notShelf)
                    ReadBook.inBookshelf = false
                }
                book.save()
            }.onSuccess {
                _uiState.update { it.copy(inBookshelf = ReadBook.inBookshelf) }
                _messages.emit(
                    UserMessage.from(
                        if (ReadBook.inBookshelf) {
                            R.string.eink_added_to_bookshelf
                        } else {
                            R.string.eink_removed_from_bookshelf
                        }
                    )
                )
            }.onFailure {
                _messages.emit(UserMessage.from(R.string.eink_operation_failed))
            }
        }
    }

    // ==================== 排版参数 ====================

    fun adjustTextSize(delta: Int) = applyLayoutStyle {
        ReadBookConfig.textSize = (ReadBookConfig.textSize + delta).coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)
    }

    fun adjustLetterSpacing(delta: Float) = applyLayoutStyle {
        ReadBookConfig.letterSpacing =
            (ReadBookConfig.letterSpacing + delta).coerceIn(0f, 0.5f)
    }

    fun adjustLineSpacing(delta: Int) = applyLayoutStyle {
        ReadBookConfig.lineSpacingExtra =
            (ReadBookConfig.lineSpacingExtra + delta).coerceIn(0, 30)
    }

    fun adjustParagraphSpacing(delta: Int) = applyLayoutStyle {
        ReadBookConfig.paragraphSpacing =
            (ReadBookConfig.paragraphSpacing + delta).coerceIn(0, 10)
    }

    fun adjustIndent(delta: Int) = applyLayoutStyle {
        val current = ReadBookConfig.paragraphIndent.count { it == ChapterProvider.indentChar[0] }
        val value = (current + delta).coerceIn(MIN_INDENT_CHARS, MAX_INDENT_CHARS)
        ReadBookConfig.paragraphIndent = if (value <= 0) "" else ChapterProvider.indentChar.repeat(value)
    }

    fun adjustPaddingLeft(delta: Int) = applyLayoutStyle {
        ReadBookConfig.paddingLeft = (ReadBookConfig.paddingLeft + delta).coerceIn(0, 64)
    }

    fun adjustPaddingTop(delta: Int) = applyLayoutStyle {
        ReadBookConfig.paddingTop = (ReadBookConfig.paddingTop + delta).coerceIn(0, 48)
    }

    fun adjustPaddingRight(delta: Int) = applyLayoutStyle {
        ReadBookConfig.paddingRight = (ReadBookConfig.paddingRight + delta).coerceIn(0, 64)
    }

    fun adjustPaddingBottom(delta: Int) = applyLayoutStyle {
        ReadBookConfig.paddingBottom = (ReadBookConfig.paddingBottom + delta).coerceIn(0, 48)
    }

    // ---- 页眉 / 页脚边距：写回 ReadBookConfig（与 View 版共用），
    // 不影响 ChapterProvider 分页，仅刷新快照即时生效。
    // 页眉/页脚字号与 View 版对齐，不做单独设置。 ----

    fun adjustHeaderPaddingLeft(delta: Int) = applyStyleOnly {
        ReadBookConfig.durConfig.headerPaddingLeft =
            (ReadBookConfig.durConfig.headerPaddingLeft + delta).coerceIn(0, 48)
    }

    fun adjustHeaderPaddingTop(delta: Int) = applyStyleOnly {
        ReadBookConfig.durConfig.headerPaddingTop =
            (ReadBookConfig.durConfig.headerPaddingTop + delta).coerceIn(0, 48)
    }

    fun adjustHeaderPaddingRight(delta: Int) = applyStyleOnly {
        ReadBookConfig.durConfig.headerPaddingRight =
            (ReadBookConfig.durConfig.headerPaddingRight + delta).coerceIn(0, 48)
    }

    fun adjustHeaderPaddingBottom(delta: Int) = applyStyleOnly {
        ReadBookConfig.durConfig.headerPaddingBottom =
            (ReadBookConfig.durConfig.headerPaddingBottom + delta).coerceIn(0, 48)
    }

    fun adjustFooterPaddingLeft(delta: Int) = applyStyleOnly {
        ReadBookConfig.durConfig.footerPaddingLeft =
            (ReadBookConfig.durConfig.footerPaddingLeft + delta).coerceIn(0, 48)
    }

    fun adjustFooterPaddingTop(delta: Int) = applyStyleOnly {
        ReadBookConfig.durConfig.footerPaddingTop =
            (ReadBookConfig.durConfig.footerPaddingTop + delta).coerceIn(0, 48)
    }

    fun adjustFooterPaddingRight(delta: Int) = applyStyleOnly {
        ReadBookConfig.durConfig.footerPaddingRight =
            (ReadBookConfig.durConfig.footerPaddingRight + delta).coerceIn(0, 48)
    }

    fun adjustFooterPaddingBottom(delta: Int) = applyStyleOnly {
        ReadBookConfig.durConfig.footerPaddingBottom =
            (ReadBookConfig.durConfig.footerPaddingBottom + delta).coerceIn(0, 48)
    }

    fun toggleTextBold() = applyLayoutStyle {
        ReadBookConfig.textBold = if (ReadBookConfig.textBold == 1) 0 else 1
    }

    fun toggleKeepScreenOn() {
        _uiState.update {
            appCtx.putPrefBoolean(PREF_KEEP_SCREEN_ON, !it.keepScreenOn)
            it.copy(keepScreenOn = !it.keepScreenOn)
        }
    }

    /**
     * 应用影响排版的参数：写回配置 → 更新画笔 → 防抖合并后重新排版
     * （保留 durChapterPos，重新排版后定位到包含该位置的页面；
     * 面板打开期间正文保持旧页渲染，新页面就绪后直接替换，实时预览）。
     */
    private fun applyLayoutStyle(change: () -> Unit) {
        applyStyleOnly(change)
        scheduleRelayout()
    }

    /**
     * 防抖合并快速连续调参（步进器连点），避免中间态排版浪费。
     */
    private fun scheduleRelayout() {
        relayoutJob?.cancel()
        relayoutJob = viewModelScope.launch(Dispatchers.IO) {
            delay(RELAYOUT_DEBOUNCE_MS)
            relayoutEngine()
        }
    }

    /**
     * 清空章节缓存并重新排版。强制清理加载标记：上一次调整的加载若
     * 仍在内容读取阶段（标记未释放），本次 loadContent 会被 addLoading 吞掉。
     */
    private suspend fun relayoutEngine() {
        if (loadedBookUrl == null) return
        ReadBook.clearTextChapter()
        val index = ReadBook.durChapterIndex
        ReadBook.removeLoading(index - 1)
        ReadBook.removeLoading(index)
        ReadBook.removeLoading(index + 1)
        ReadBook.loadContent(resetPageOffset = false)
    }

    /** 应用不影响分页的参数：仅写配置、刷新画笔与快照。 */
    private fun applyStyleOnly(change: () -> Unit) {
        change()
        ReadBookConfig.save()
        ChapterProvider.upStyle()
        _uiState.update {
            it.copy(
                style = ReadBookConfig.snapshotStyle(),
                textBold = ReadBookConfig.textBold == 1,
            )
        }
    }

    // ==================== ReadBook.CallBack 实现 ====================

    override fun upMenuView() {
        val book = ReadBook.book ?: return
        _uiState.update {
            it.copy(
                bookName = book.name,
                chapterIndex = ReadBook.durChapterIndex,
                chapterSize = ReadBook.chapterSize,
            )
        }
    }

    override fun loadChapterList(book: Book) {
        _uiState.update {
            it.copy(
                bookName = book.name,
                chapterSize = ReadBook.chapterSize,
            )
        }
    }

    override fun upContent(
        relativePosition: Int,
        resetPageOffset: Boolean,
        success: (() -> Unit)?
    ) {
        if (relativePosition != 0) {
            // ±1 预载章节回调：View 版只刷新离屏预载页，不更新当前显示
            success?.invoke()
            return
        }
        val chapter = ReadBook.curTextChapter
        if (chapter == null || chapter.pages.isEmpty()) {
            // 章节尚未挂载或尚未排出任何页面
            if (ReadBook.msg != null) {
                _uiState.update { it.copy(isLoading = false, error = ReadBook.msg) }
            }
            success?.invoke()
            return
        }
        val pageIndex = ReadBook.durPageIndex
        if (pageIndex < 0) {
            // 流式排版尚未到达阅读位置：保持现状（加载中/旧页），
            // 等待包含 durChapterPos 的页面排出，禁止回退第 0 页（章节首页闪现）
            success?.invoke()
            return
        }
        val page = chapter.getPage(pageIndex)
        if (page != null) {
            _uiState.update {
                it.copy(
                    textPage = page,
                    pageVersion = it.pageVersion + 1,
                    chapterTitle = page.title,
                    pageIndex = pageIndex,
                    pageCount = chapter.pageSize,
                    chapterIndex = ReadBook.durChapterIndex,
                    chapterSize = ReadBook.chapterSize,
                    readProgress = page.readProgress,
                    isLoading = false,
                    error = null,
                )
            }
            updateTipInfo()
        }
        success?.invoke()
    }

    override suspend fun upContentAwait(
        relativePosition: Int,
        resetPageOffset: Boolean,
        success: (() -> Unit)?
    ) {
        upContent(relativePosition, resetPageOffset, success)
    }

    override fun pageChanged() {
        upContent()
    }

    override fun contentLoadFinish() {
        upContent()
    }

    override fun upPageAnim(upRecorder: Boolean) {}

    /** 排版异常（如内容为空、测量失败）：显示错误而不是停留在加载中。 */
    override fun onLayoutException(e: Throwable) {
        _uiState.update {
            it.copy(
                isLoading = false,
                error = "排版失败：${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    override fun notifyBookChanged() {
        ReadBook.book?.let { book ->
            loadedBookUrl = book.bookUrl
            syncBookState(book)
            upContent()
        }
    }

    override fun sureNewProgress(progress: BookProgress) {}

    override fun cancelSelect() {}

    override fun onCleared() {
        super.onCleared()
        stopAutoPlay()
        if (ReadBook.callBack === this) {
            // 落库阅读进度（更新 durChapterTime，书架按最后阅读排序据此置顶）
            ReadBook.saveRead()
            ReadBook.unregister(this)
        }
    }

}

/** 缓存全部剩余章节的标记值。 */
const val CACHE_ALL = -1

/** 缩进字符数可调区间。 */
internal const val MIN_INDENT_CHARS = 0
internal const val MAX_INDENT_CHARS = 4

/** 等待阅读区尺寸就绪的超时（毫秒）。 */
internal const val VIEW_SIZE_TIMEOUT_MS = 2000L

/** 快速连续调参的重排合并窗口（毫秒）。 */
internal const val RELAYOUT_DEBOUNCE_MS = 200L

/** 自动翻页默认/可调区间（秒）。 */
internal const val DEFAULT_AUTO_INTERVAL_SEC = 20
internal const val MIN_AUTO_INTERVAL_SEC = 5
internal const val MAX_AUTO_INTERVAL_SEC = 120

private const val MIN_TEXT_SIZE = 8
private const val MAX_TEXT_SIZE = 40

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
