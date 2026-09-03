package io.legado.app.eink.feature.reader

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.eink.R
import io.legado.app.eink.arch.UserMessage
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.contract.EInkSettings
import io.legado.app.eink.contract.ReaderBookSnapshot
import io.legado.app.eink.contract.ReaderEngineCallback
import io.legado.app.eink.contract.ReaderPageSnapshot
import io.legado.app.eink.contract.ReaderPrepResult
import io.legado.app.eink.contract.ReaderTextStyle
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


/**
 * 阅读器 UiState。
 *
 * 状态是离散的（规范 §49: page = 100，而不是 pageAnimationProgress = 0.73）。
 * [page] 为引擎排版产物的模块快照（宿主映射 TextPage 而来），
 * 由模块画布 ReaderPageSnapshotCanvas 绘制；
 * [pageVersion] 变化驱动重绘。
 */
data class ReaderUiState(
    val bookName: String = "",
    // 当前书信息（换源后与路由参数不同，以引擎持有的书为准）
    val bookAuthor: String = "",
    val bookUrl: String = "",
    val chapterTitle: String = "",
    val page: ReaderPageSnapshot? = null,
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
    /** 自动翻页页脚进度条进度（0f..1f），1s 刷新一次。 */
    val autoPlayProgress: Float = 0f,
    val isLocalBook: Boolean = false,
    val inBookshelf: Boolean = false,
    val keepScreenOn: Boolean = false,
    val textBold: Boolean = false,
    val style: ReaderTextStyle = ReaderTextStyle(),
    // 水平滑动翻页触发距离（px，0 = 系统 touch slop）
    val pageTouchSlop: Int = 0,
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
 * 桥接引擎全局状态机（经 [io.legado.app.eink.contract.ReaderEngine] 端口）
 * 到 Compose StateFlow：
 * - 复用 View 版渲染引擎（宿主 ChapterProvider 排版产物经映射器转为
 *   ReaderPageSnapshot 快照进入状态，绘制由模块画布完成）；
 * - 排版参数以 ReaderTextStyle 快照整体经端口写回（与 View 版共用一套
 *   阅读配置）；
 * - 通过实现 [ReaderEngineCallback] 接收引擎状态推送。
 */
class ReaderViewModel(application: Application) : AndroidViewModel(application),
    ReaderEngineCallback {

    private val engine get() = EInkEngineRegistry.readerEngine

    private val _uiState = MutableStateFlow(
        ReaderUiState(
            keepScreenOn = EInkSettings.readerKeepScreenOn,
            // 与完整模式共用宿主 autoReadSpeed 配置（默认 10）
            autoPlayIntervalSec = engine.autoReadIntervalSec
                .coerceIn(MIN_AUTO_INTERVAL_SEC, MAX_AUTO_INTERVAL_SEC),
            textBold = engine.textBold,
            style = engine.currentStyle(),
            pageTouchSlop = engine.pageTouchSlop,
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
            engine.register(this@ReaderViewModel)

            // 首次排版前必须拿到阅读区真实尺寸：引擎宽高为 0 时
            // visibleWidth 为负数，排版会直接抛异常，导致 upContent
            // 永远不会回调（界面停留在加载中）
            withTimeoutOrNull(VIEW_SIZE_TIMEOUT_MS) { viewSizeReady.await() }

            // 换源完成后引擎持有新书（bookUrl 与路由参数不同）：旧记录连同
            // 章节已被删除，按路由参数解析要么为 null、要么从 searchBook
            // 缓存复活死源旧书——会话书是它的换源继任（同名同作者、已换
            // URL）时优先。不能以 loadedBookUrl 判断：onNotifyBookChanged
            // 在换源时已把它同步成新值，旧守卫会在重挂时误判"无变化"而
            // 回落失效参数，表现为内容上叠加"加载失败/书籍不存在"。
            // 未加书架的搜索书（详情页直接阅读）由端口转 notShelf 入库，
            // 与 View 版"未加书架直接阅读"行为一致
            val resolved = engine.resolveBook(bookUrl)
            val session = engine.sessionBook
            val book = when {
                session != null && resolved != null &&
                        session.bookUrl != resolved.bookUrl &&
                        session.name == resolved.name &&
                        session.author == resolved.author -> session

                resolved != null -> resolved
                else -> session
            }
            if (book == null) {
                _uiState.update {
                    it.copy(isLoading = false, error = "书籍不存在")
                }
                return@launch
            }

            if (engine.sessionBookUrl == book.bookUrl) {
                engine.upData(book.handle)
                // 已有排版页面（含流式排版中）直接刷新展示
                if (engine.hasLaidOutPages) {
                    loadedBookUrl = book.bookUrl
                    syncBookState(book)
                    upContent()
                    engine.upToc()
                    return@launch
                }
                // 章节跳转（目录选章等）：清空旧页面显示加载中，
                // 内容未缓存时由下载完成的 upContent 回调刷新
                _uiState.update { it.copy(isLoading = true, page = null) }
            } else {
                engine.resetData(book.handle)
                _uiState.update { it.copy(isLoading = true, page = null) }
            }
            loadedBookUrl = book.bookUrl
            syncBookState(book)

            // 前置数据初始化（详情/目录管线在宿主 bridge）：
            // E-Ink 经详情页加入书架的书没有目录记录，目录缺失时引擎的
            // loadContent 会静默失败
            when (val prep = engine.prepareBookData(book.handle)) {
                ReaderPrepResult.Success -> Unit
                ReaderPrepResult.NoSource -> {
                    _uiState.update { it.copy(isLoading = false, error = "没有书源") }
                    return@launch
                }

                is ReaderPrepResult.InfoFailure -> {
                    _uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            error = "详情页出错：${prep.cause.localizedMessage}"
                        )
                    }
                    return@launch
                }

                is ReaderPrepResult.TocFailure -> {
                    _uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            error = "目录加载失败：${prep.cause.localizedMessage}"
                        )
                    }
                    return@launch
                }
            }
            // 拉目录可能重定向 bookUrl，以引擎最终持有的书为准
            loadedBookUrl = engine.sessionBookUrl ?: book.bookUrl

            engine.clearEngineMessage()
            engine.loadContent(resetPageOffset = true)
            engine.upToc()
        }
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
            engine.updateViewSize(width, height)
            viewSizeReady.complete(Unit)
            if (loadedBookUrl != null) {
                scheduleRelayoutNow()
            }
        }
    }

    private fun syncBookState(book: ReaderBookSnapshot) {
        val inBookshelf = !book.isNotShelf
        engine.setInBookshelf(inBookshelf)
        _uiState.update {
            it.copy(
                bookName = book.name,
                bookAuthor = book.author,
                bookUrl = book.bookUrl,
                isLocalBook = book.isLocal,
                inBookshelf = inBookshelf,
            )
        }
        updateTipInfo()
    }

    /**
     * 刷新页眉/页脚信息（复刻 View 版 PageView.upTipStyle 的可见性与默认
     * 内容，可见性规则经端口计算，不开放设置）：
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
        val tip = engine.readTipVisibility()
        _uiState.update {
            it.copy(
                headerVisible = tip.headerVisible,
                footerVisible = tip.footerVisible,
                headerTime = engine.formatTimeNow(),
                batteryPercent = battery,
            )
        }
    }

    // ==================== 翻页 ====================

    fun nextPage(): Boolean {
        val moved = engine.nextPage()
        if (moved) restartAutoPlayCountdown()
        return moved
    }

    fun prevPage(): Boolean {
        val moved = engine.prevPage()
        if (moved) restartAutoPlayCountdown()
        return moved
    }

    /** 跳转到当前章指定页（页内进度条）。 */
    fun skipToPage(pageIndex: Int) {
        val pageCount = _uiState.value.pageCount
        if (pageCount <= 0) return
        engine.skipToPage(pageIndex.coerceIn(0, pageCount - 1))
        restartAutoPlayCountdown()
    }

    /** 下一章（无下一章时由引擎返回 false，状态经 onPageChanged 刷新）。 */
    fun nextChapter(): Boolean {
        val moved = engine.nextChapter()
        if (moved) restartAutoPlayCountdown()
        return moved
    }

    /** 上一章（无上一章时由引擎返回 false，状态经 onPageChanged 刷新）。 */
    fun prevChapter(): Boolean {
        val moved = engine.prevChapter()
        if (moved) restartAutoPlayCountdown()
        return moved
    }

    /**
     * 手动翻页后重置自动翻页倒计时并清零进度条（对齐宿主
     * ReadView.onPageChange → autoPager.reset：任何翻页完成都重新
     * 计满一个间隔）。仅倒计时运行中有意义；暂停态（菜单打开）收起
     * 菜单时本就按新间隔重新起算。
     */
    private fun restartAutoPlayCountdown() {
        if (autoPlayJob?.isActive != true) return
        pauseAutoPlay()
        startAutoPlayJob()
    }

    // ==================== 操作条 ====================

    fun toggleControls() {
        if (_uiState.value.controlsVisible) {
            hideControls()
        } else {
            showControls()
        }
    }

    private fun showControls() {
        // 对齐宿主：自动翻页中点按阅读菜单区域会暂停自动翻页；
        // 打开菜单后由 Route 负责自动打开进度与翻页面板。
        if (_uiState.value.autoPlay) {
            pauseAutoPlay()
        }
        _uiState.update { it.copy(controlsVisible = true) }
    }

    fun hideControls() {
        val shouldResume = _uiState.value.autoPlay && autoPlayJob?.isActive != true
        _uiState.update { if (it.controlsVisible) it.copy(controlsVisible = false) else it }
        if (shouldResume) {
            startAutoPlayJob()
        }
    }

    /**
     * 阅读界面不可见时暂停自动翻页倒计时，避免后台继续翻页
     * （退后台 ON_STOP / 应用内离开阅读目的地触发）。
     */
    fun onReaderHidden() {
        if (autoPlayJob?.isActive == true) {
            pauseAutoPlay()
        }
    }

    /**
     * 阅读界面回到前台/返回阅读目的地：自动翻页开启且未在倒计时时
     * 重新起算（菜单展开态保持暂停，收起时再起算）。
     */
    fun onReaderShown() {
        if (_uiState.value.autoPlay &&
            autoPlayJob?.isActive != true &&
            !_uiState.value.controlsVisible
        ) {
            startAutoPlayJob()
        }
    }

    /** 自动翻页：每秒推进页脚进度条，间隔到达后翻下一页，翻到书尾自动停止。 */
    fun toggleAutoPlay() {
        if (_uiState.value.autoPlay) {
            stopAutoPlay()
            return
        }
        _uiState.update { it.copy(autoPlay = true, autoPlayProgress = 0f) }
        // 面板打开时先不启动，收起菜单后再按新时长启动。
        if (!_uiState.value.controlsVisible) {
            startAutoPlayJob()
        }
    }

    private fun startAutoPlayJob() {
        if (autoPlayJob?.isActive == true) return
        _uiState.update { it.copy(autoPlayProgress = 0f) }
        autoPlayJob = viewModelScope.launch {
            var elapsedSec = 0
            while (isActive) {
                delay(1000L)
                elapsedSec += 1
                val interval = _uiState.value.autoPlayIntervalSec
                val progress = (elapsedSec.toFloat() / interval.coerceAtLeast(1)).coerceIn(0f, 1f)
                _uiState.update { it.copy(autoPlayProgress = progress) }
                if (elapsedSec >= interval) {
                    elapsedSec = 0
                    // 直连引擎而非 nextPage()：自动翻页不触发倒计时重置
                    if (!engine.nextPage()) {
                        stopAutoPlay()
                        _messages.emit(UserMessage.from(R.string.eink_reader_auto_page_end))
                        break
                    }
                }
            }
        }
    }

    private fun pauseAutoPlay() {
        autoPlayJob?.cancel()
        autoPlayJob = null
    }

    private fun stopAutoPlay() {
        autoPlayJob?.cancel()
        autoPlayJob = null
        _uiState.update { it.copy(autoPlay = false, autoPlayProgress = 0f) }
    }

    /** 设置自动翻页间隔（进度页滑条直接提交绝对值，逐档持久化对齐宿主）。 */
    fun setAutoPlayInterval(value: Int) {
        val clamped = value.coerceIn(MIN_AUTO_INTERVAL_SEC, MAX_AUTO_INTERVAL_SEC)
        _uiState.update { it.copy(autoPlayIntervalSec = clamped) }
        viewModelScope.launch(Dispatchers.IO) {
            engine.setAutoReadIntervalSec(clamped)
        }
    }

    // ==================== 顶部操作条动作 ====================

    /** 刷新当前章节：清除缓存后重新加载。 */
    fun refreshChapter() {
        viewModelScope.launch(Dispatchers.IO) {
            engine.refreshCurrentChapter()
        }
    }

    /**
     * 缓存章节（当前章起往后）。
     * @param count 向后缓存的章节数；[CACHE_ALL] 表示全本
     */
    fun cacheChapters(count: Int) {
        viewModelScope.launch {
            when (engine.startCache(count, cacheAll = count == CACHE_ALL)) {
                null -> Unit
                false -> _messages.emit(UserMessage.from(R.string.eink_reader_local_no_cache))
                true -> _messages.emit(UserMessage.from(R.string.eink_reader_cache_started))
            }
        }
    }

    /** 加入书架（仅未加书架的书；阅读页不提供移出书架）。 */
    fun addToBookshelf() {
        viewModelScope.launch(Dispatchers.IO) {
            when (engine.addSessionBookToShelf()) {
                true -> {
                    _uiState.update { it.copy(inBookshelf = true) }
                    _messages.emit(UserMessage.from(R.string.eink_added_to_bookshelf))
                }

                false -> _messages.emit(UserMessage.from(R.string.eink_operation_failed))
                null -> Unit
            }
        }
    }

    // ==================== 排版参数 ====================
    // 均为绝对值 setter：档位滑条（含 ±1 按钮）直接设置目标档位，
    // 钳制在本地完成后以快照整体经端口写回，由 applyLayoutStyle /
    // applyStyleOnly 持久化。

    fun setTextSize(value: Int) =
        applyLayoutStyle { it.copy(textSize = value.coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)) }

    /** 字距按 0.05 步进索引设置（0..[LETTER_SPACING_STEPS]），避免浮点累加漂移。 */
    fun setLetterSpacing(step: Int) = applyLayoutStyle {
        it.copy(letterSpacing = (step * LETTER_SPACING_STEP).coerceIn(0f, MAX_LETTER_SPACING))
    }

    fun setLineSpacing(value: Int) = applyLayoutStyle {
        it.copy(lineSpacing = value.coerceIn(0, MAX_LINE_SPACING))
    }

    fun setParagraphSpacing(value: Int) = applyLayoutStyle {
        it.copy(paragraphSpacing = value.coerceIn(0, MAX_PARAGRAPH_SPACING))
    }

    fun setIndent(value: Int) = applyLayoutStyle {
        it.copy(indentChars = value.coerceIn(MIN_INDENT_CHARS, MAX_INDENT_CHARS))
    }

    fun setPaddingLeft(value: Int) = applyLayoutStyle {
        it.copy(paddingLeft = value.coerceIn(0, MAX_PADDING_HORIZONTAL))
    }

    fun setPaddingTop(value: Int) = applyLayoutStyle {
        it.copy(paddingTop = value.coerceIn(0, MAX_PADDING_VERTICAL))
    }

    fun setPaddingRight(value: Int) = applyLayoutStyle {
        it.copy(paddingRight = value.coerceIn(0, MAX_PADDING_HORIZONTAL))
    }

    fun setPaddingBottom(value: Int) = applyLayoutStyle {
        it.copy(paddingBottom = value.coerceIn(0, MAX_PADDING_VERTICAL))
    }

    // ---- 页眉 / 页脚边距：不影响引擎分页，仅刷新快照即时生效。
    // 页眉/页脚字号与 View 版对齐，不做单独设置。 ----

    fun setHeaderPaddingLeft(value: Int) = applyStyleOnly {
        it.copy(headerPaddingLeft = value.coerceIn(0, MAX_PADDING_VERTICAL))
    }

    fun setHeaderPaddingTop(value: Int) = applyStyleOnly {
        it.copy(headerPaddingTop = value.coerceIn(0, MAX_PADDING_VERTICAL))
    }

    fun setHeaderPaddingRight(value: Int) = applyStyleOnly {
        it.copy(headerPaddingRight = value.coerceIn(0, MAX_PADDING_VERTICAL))
    }

    fun setHeaderPaddingBottom(value: Int) = applyStyleOnly {
        it.copy(headerPaddingBottom = value.coerceIn(0, MAX_PADDING_VERTICAL))
    }

    fun setFooterPaddingLeft(value: Int) = applyStyleOnly {
        it.copy(footerPaddingLeft = value.coerceIn(0, MAX_PADDING_VERTICAL))
    }

    fun setFooterPaddingTop(value: Int) = applyStyleOnly {
        it.copy(footerPaddingTop = value.coerceIn(0, MAX_PADDING_VERTICAL))
    }

    fun setFooterPaddingRight(value: Int) = applyStyleOnly {
        it.copy(footerPaddingRight = value.coerceIn(0, MAX_PADDING_VERTICAL))
    }

    fun setFooterPaddingBottom(value: Int) = applyStyleOnly {
        it.copy(footerPaddingBottom = value.coerceIn(0, MAX_PADDING_VERTICAL))
    }

    fun toggleTextBold() {
        engine.setTextBold(!engine.textBold)
        _uiState.update { it.copy(textBold = engine.textBold) }
        scheduleRelayout()
    }

    fun toggleKeepScreenOn() {
        _uiState.update {
            EInkSettings.readerKeepScreenOn = !it.keepScreenOn
            it.copy(keepScreenOn = !it.keepScreenOn)
        }
    }

    /**
     * 应用影响排版的参数：写回配置 → 更新画笔 → 防抖合并后重新排版
     * （保留 durChapterPos，重新排版后定位到包含该位置的页面；
     * 面板打开期间正文保持旧页渲染，新页面就绪后直接替换，实时预览）。
     */
    private fun applyLayoutStyle(change: (ReaderTextStyle) -> ReaderTextStyle) {
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

    /** 尺寸变化直接重排（无防抖）。 */
    private fun scheduleRelayoutNow() {
        relayoutJob?.cancel()
        relayoutJob = viewModelScope.launch(Dispatchers.IO) {
            relayoutEngine()
        }
    }

    /**
     * 清空章节缓存并重新排版（清缓存/加载标记/重载在端口内完成）。
     */
    private suspend fun relayoutEngine() {
        if (loadedBookUrl == null) return
        engine.relayout()
    }

    /** 应用不影响分页的参数：仅经端口写配置、刷新画笔与快照。
     *  画笔字色由模块画布每次绘制前按主题钉死。 */
    private fun applyStyleOnly(change: (ReaderTextStyle) -> ReaderTextStyle) {
        val newStyle = change(_uiState.value.style)
        engine.applyStyle(newStyle)
        _uiState.update {
            it.copy(
                style = engine.currentStyle(),
                textBold = engine.textBold,
            )
        }
    }

    // ==================== 引擎回调（ReaderEngineCallback） ====================

    override fun onUpMenuView() {
        val book = engine.sessionBook ?: return
        _uiState.update {
            it.copy(
                bookName = book.name,
                bookAuthor = book.author,
                bookUrl = book.bookUrl,
                chapterIndex = engine.durChapterIndex,
                chapterSize = engine.chapterSize,
            )
        }
    }

    override fun onLoadChapterList(book: ReaderBookSnapshot) {
        _uiState.update {
            it.copy(
                bookName = book.name,
                bookAuthor = book.author,
                bookUrl = book.bookUrl,
                chapterSize = engine.chapterSize,
            )
        }
    }

    fun upContent() {
        onUpContent(0, false, null)
    }

    override fun onUpContent(
        relativePosition: Int,
        resetPageOffset: Boolean,
        success: (() -> Unit)?
    ) {
        if (relativePosition != 0) {
            // ±1 预载章节回调：View 版只刷新离屏预载页，不更新当前显示
            success?.invoke()
            return
        }
        if (!engine.hasLaidOutPages) {
            // 章节尚未挂载或尚未排出任何页面
            val msg = engine.engineMessage
            if (msg != null) {
                _uiState.update { it.copy(isLoading = false, error = msg) }
            }
            success?.invoke()
            return
        }
        val pageIndex = engine.durPageIndex
        if (pageIndex < 0) {
            // 流式排版尚未到达阅读位置：保持现状（加载中/旧页），
            // 等待包含 durChapterPos 的页面排出，禁止回退第 0 页（章节首页闪现）
            success?.invoke()
            return
        }
        val page = engine.currentPage()
        if (page != null) {
            _uiState.update {
                it.copy(
                    page = page,
                    pageVersion = it.pageVersion + 1,
                    chapterTitle = page.title,
                    pageIndex = pageIndex,
                    pageCount = engine.currentChapterPageSize,
                    chapterIndex = engine.durChapterIndex,
                    chapterSize = engine.chapterSize,
                    readProgress = page.readProgress,
                    isLoading = false,
                    error = null,
                )
            }
            updateTipInfo()
        }
        success?.invoke()
    }

    override fun onPageChanged() {
        upContent()
    }

    override fun onContentLoadFinish() {
        upContent()
    }

    /** 排版异常（如内容为空、测量失败）：显示错误而不是停留在加载中。 */
    override fun onLayoutException(e: Throwable) {
        _uiState.update {
            it.copy(
                isLoading = false,
                error = "排版失败：${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    override fun onNotifyBookChanged() {
        engine.sessionBook?.let { book ->
            loadedBookUrl = book.bookUrl
            syncBookState(book)
            upContent()
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopAutoPlay()
        if (engine.isRegistered(this)) {
            // 落库阅读进度（更新 durChapterTime，书架按最后阅读排序据此置顶）
            engine.saveRead()
            engine.unregister(this)
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

/** 自动翻页间隔可调区间（秒），与宿主 autoReadSpeed（默认 10）一致。 */
internal const val DEFAULT_AUTO_INTERVAL_SEC = 10
internal const val MIN_AUTO_INTERVAL_SEC = 1
internal const val MAX_AUTO_INTERVAL_SEC = 120

/** 字号可调区间（sp）。 */
internal const val MIN_TEXT_SIZE = 8
internal const val MAX_TEXT_SIZE = 40

/** 字距档位滑条的步进索引上界（实际字距 = 步进 × [LETTER_SPACING_STEP]，范围 0..0.5）。 */
internal const val LETTER_SPACING_STEPS = 10
internal const val LETTER_SPACING_STEP = 0.05f
private const val MAX_LETTER_SPACING = 0.5f

/** 行距/段距内部值上界（显示值 = 内部值 / 10）。 */
internal const val MAX_LINE_SPACING = 30
internal const val MAX_PARAGRAPH_SPACING = 10

/** 正文边距上界：左右 64dp / 上下 48dp；页眉/页脚边距全部 48dp。 */
internal const val MAX_PADDING_HORIZONTAL = 64
internal const val MAX_PADDING_VERTICAL = 48
