package io.legado.app.eink.feature.bookshelf

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.eink.arch.UserMessage
import io.legado.app.eink.contract.BookshelfItemUiModel
import io.legado.app.eink.contract.BookshelfStyle
import io.legado.app.eink.contract.BookshelfTocRefreshResult
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.util.onEachParallel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * 书架屏幕 UiState（扁平布尔标志位，参考 JBusDriver UDF 模式）。
 *
 * [books] 为预抽取的稳定 UiModel（[BookshelfItemUiModel]），条目参数
 * 全稳定类型且数据未变时可跳过重组。
 */
data class BookshelfUiState(
    val books: List<BookshelfItemUiModel> = emptyList(),
    val isLoading: Boolean = true,
    /** 目录刷新进行中（首页头部刷新按钮禁用并置灰） */
    val isRefreshing: Boolean = false,
    /** 正在更新目录的书籍 bookUrl 集合（对应行角标替换为刷新态） */
    val updatingBookUrls: Set<String> = emptySet(),
    /** 书架网格布局（true = 网格，列数按屏宽自适应；false = 列表）。默认网格。 */
    val isGridLayout: Boolean = true,

    /** 书架显示样式快照（宿主设置投影，实时档）。 */
    val style: BookshelfStyle = BookshelfStyle(),
) {
    val isEmpty: Boolean get() = books.isEmpty() && !isLoading
}

/**
 * 书架 ViewModel。
 *
 * 书架数据经 BookshelfEngine 端口获取（宿主 Room 流 + UiModel 映射），
 * 与刷新标志合并为单一 UiState 流。
 *
 * 加载态只出现在首次订阅前（[stateIn] 初始值）；重新订阅（如从阅读页
 * 返回）时 [stateIn] 保留着上次数据，Room 流重发同值不触发重组，
 * 不会重复闪加载页。
 *
 * 刷新（[refresh]）复刻 View 版下拉刷新（`MainViewModel.upToc/updateToc`）：
 * 并发拉取书架书目录，进度经引擎侧 sync 保留，失败标记 updateError；
 * 进入首页（ViewModel 创建）后延迟自动刷新一次。单本书的刷新管线
 * 在宿主 bridge（BookshelfEngineImpl.refreshBookToc）。
 */
class BookshelfViewModel(application: Application) : AndroidViewModel(application) {

    private val engine get() = EInkEngineRegistry.bookshelfEngine
    private val settings get() = EInkEngineRegistry.globalSettings

    private val _messages = MutableSharedFlow<UserMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<UserMessage> = _messages.asSharedFlow()

    private val _isRefreshing = MutableStateFlow(false)
    private val _updatingUrls = MutableStateFlow<Set<String>>(emptySet())

    // 初始值读宿主快照；切换入口在首页顶栏，
    // 经 [toggleLayout] 乐观更新并反向写宿主竖屏键
    private val _isGridLayout = MutableStateFlow(true)

    // 乐观覆盖层：提交样式先行置非空，宿主快照追平（同值）后由 init
    // 收集器清除，避免落库完成与快照流发射之间的空窗回跳
    private val _styleOverride = MutableStateFlow<BookshelfStyle?>(null)

    // notShelf 行已在宿主查询内过滤，并由宿主物理删除；此处
    // 直接使用查询结果，与 View 版保持一致。
    // UiModel 映射放在宿主 bridge：只在 Room 发射（books 表变化）时执行
    // 一次，刷新期间 updatingUrls 频繁翻转时 combine 复用缓存的最新列表。
    // 样式双流（快照 + 乐观覆盖）先归一再并入 UiState：kotlinx combine
    // 类型化重载至多 5 流，覆盖非空时优先于快照。
    val uiState: StateFlow<BookshelfUiState> =
        combine(
            engine.observeShelf(),
            combine(engine.style, _styleOverride) { snapshot, styleOverride ->
                styleOverride ?: snapshot
            },
            _isRefreshing,
            _updatingUrls,
            _isGridLayout
        ) { books, style, refreshing, updatingUrls, isGridLayout ->
            BookshelfUiState(
                books = books,
                isLoading = false,
                isRefreshing = refreshing,
                updatingBookUrls = updatingUrls,
                isGridLayout = isGridLayout,
                style = style,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookshelfUiState())

    /**
     * 切换书架布局：乐观更新，经 [setStyle] 反向写宿主竖屏键。
     */
    fun toggleLayout() {
        val target = !_isGridLayout.value
        _isGridLayout.value = target
        updateStyle(uiState.value.style.copy(isGridLayout = target))
    }

    /**
     * 提交书架样式（个性化配置面板写入口）：乐观置覆盖层，落库后由
     * 快照流追平清除。
     */
    fun updateStyle(style: BookshelfStyle) {
        _styleOverride.value = style
        viewModelScope.launch { engine.setStyle(style) }
    }

    private var refreshJob: Job? = null

    // 刷新性能日志：与 View 版 MainViewModel 同 tag（ShelfBench），便于两版对拍与刷新慢的问题定位
    private val benchInFlight = AtomicInteger(0)
    private val benchOk = AtomicInteger(0)
    private val benchErr = AtomicInteger(0)
    private var benchPeak = 0

    private fun benchReset() = synchronized(this) {
        benchInFlight.set(0)
        benchOk.set(0)
        benchErr.set(0)
        benchPeak = 0
    }

    private fun benchTrackPeak(cur: Int) = synchronized(this) {
        if (cur > benchPeak) benchPeak = cur
    }

    init {
        // 与 View 版 MainViewModel.init 对齐：启动时物理删除未加书架
        //（notShelf）的隐藏行。详情页/搜索/阅读页的预取可能写入 notShelf 行，
        // 统一在此清理，后续查询与刷新无需再逐个过滤。
        viewModelScope.launch(Dispatchers.IO) {
            engine.deleteBooksNotInBookshelf()
        }
        // 布局默认值读宿主快照（冷 Flow，首个 UiState 理论上可能先于本读取
        // 渲染默认布局，下一帧自愈；开关与完整模式互斥，会话内不再变化）。
        // 切换的持久化经 [toggleLayout] 反向写宿主后由 style 重发同值快照，
        // 本读取只负责启动种子
        val styleFlow = engine.style
        viewModelScope.launch {
            _isGridLayout.value = styleFlow.first().isGridLayout
        }
        // 乐观覆盖在宿主快照追平后清除，避免落库完成与流发射之间的空窗回跳
        viewModelScope.launch {
            styleFlow.collect { snapshot ->
                if (_styleOverride.value == snapshot) _styleOverride.value = null
            }
        }
        // 与 View 版 MainActivity 自动刷新一致：仅在设置开启时，
        // 进入首页延迟 1 秒自动刷新一次；ViewModel 常驻，
        // 从阅读页/搜索返回不会重复触发。
        if (settings.autoRefreshBook) {
            viewModelScope.launch {
                delay(AUTO_REFRESH_DELAY_MS)
                refresh("auto")
            }
        }
        Log.i(
            TAG,
            "EInk VM init autoRefreshBook=${settings.autoRefreshBook} threadCount=${settings.threadCount}"
        )
    }

    /**
     * 刷新书架：进行中时直接忽略，与 View 版 upToc 的排队去重行为一致。
     * View 版下拉刷新和菜单“更新目录”都不会中断当前任务，重复触发只会排队
     * 当前书架中尚未处理的书籍；E-Ink 首页展示全部书籍，因此这里简化为
     * 活动期间 no-op。
     */
    fun refresh() = refresh("manual")

    fun refresh(trigger: String) {
        if (refreshJob?.isActive == true) {
            Log.i(TAG, "EInk refresh ignored (job active) trigger=$trigger")
            return
        }
        refreshJob = viewModelScope.launch(Dispatchers.IO) {
            val myJob = coroutineContext[Job]
            val benchStart = SystemClock.elapsedRealtime()
            benchReset()
            _isRefreshing.value = true
            _updatingUrls.value = emptySet()
            try {
                val books = engine.updatableBooks()
                val concurrency = min(settings.threadCount, MAX_REFRESH_CONCURRENCY)
                Log.i(
                    TAG,
                    "EInk refresh start trigger=$trigger scope=${books.size} concurrency=$concurrency"
                )
                books.asFlow()
                    .onEachParallel(concurrency) { book ->
                        updateToc(book.bookUrl, books)
                    }
                    .collect()
            } finally {
                if (refreshJob === myJob) {
                    _isRefreshing.value = false
                    Log.i(
                        TAG,
                        "EInk refresh end elapsedMs=${SystemClock.elapsedRealtime() - benchStart} " +
                                "ok=${benchOk.get()} err=${benchErr.get()} peakInFlight=$benchPeak"
                    )
                    // 兜底触发预缓存泵：刷新被取消时，最后一批入队可能
                    // 落在逐书触发之后；泵幂等复用，重复触发无代价。泵在
                    // 进程级作用域运行，本 VM 随后销毁也不影响其继续消费。
                    startCacheBook()
                }
            }
        }
    }

    /**
     * 触发预缓存泵（消费逻辑对齐 View 版 `MainViewModel.cacheBook`）：
     * 消费宿主入队的章节，已缓存跳过、失败重试 3 次。
     *
     * 与 View 版的调度差异：不整轮刷新结束后才启动，也不在刷新期间
     * 暂停——原「目录优先」会让多书刷新的几分钟里一章不下，而目录刷新
     * 与章节下载并发额度分离（threadCount vs cacheBookThreadCount），
     * 且均为挂起式网络请求，可安全并行。泵随每本书入队触发（见
     * [updateToc]），此处亦为刷新结束/取消的兜底。View 版的
     * CacheBookService 前台服务判断省略——E-Ink 模式与完整模式互斥
     * （切换即 CLEAR_TASK），服务不会并行运行。
     */
    private fun startCacheBook() {
        if (settings.preDownloadChapterCount == 0) return
        CacheBookPump.start(engine)
    }

    /**
     * 更新一本书的目录（管线在宿主 bridge）：此处只保留
     * updating 标记与 ShelfBench 统计/日志。
     */
    private suspend fun updateToc(bookUrl: String, books: List<BookshelfItemUiModel>) {
        _updatingUrls.update { it + bookUrl }
        val benchStart = SystemClock.elapsedRealtime()
        val benchCur = benchInFlight.incrementAndGet()
        benchTrackPeak(benchCur)
        var benchResult = "noBook"
        val benchName = books.find { it.bookUrl == bookUrl }?.name ?: ""
        try {
            benchResult = when (engine.refreshBookToc(bookUrl)) {
                BookshelfTocRefreshResult.OK -> "ok"
                BookshelfTocRefreshResult.NO_BOOK -> "noBook"
                BookshelfTocRefreshResult.NO_SOURCE -> "noSource"
                BookshelfTocRefreshResult.ERROR -> "error"
            }
            if (benchResult == "ok") {
                // 该书预缓存已在宿主入队，立即触发泵消费，不等整轮刷新
                // 结束（泵幂等复用，逐书触发无重复代价）
                startCacheBook()
            }
        } finally {
            benchInFlight.decrementAndGet()
            if (benchResult == "ok") benchOk.incrementAndGet() else benchErr.incrementAndGet()
            Log.i(
                TAG,
                "EInk book done name=<$benchName> result=$benchResult " +
                        "inFlight=${benchInFlight.get()} " +
                        "elapsedMs=${SystemClock.elapsedRealtime() - benchStart} url=$bookUrl"
            )
            _updatingUrls.update { it - bookUrl }
        }
    }

    companion object {
        private const val TAG = "ShelfBench"

        /**
         * 目录刷新并发上限：取用户 threadCount 设置，不再压到
         * AppConst.MAX_THREAD（那是固定线程池的 CPU 侧尺寸上限，目录刷新
         * 的网络请求为挂起式、不受线程数约束）；仅以 OkHttp Dispatcher
         * 全局 maxRequests（默认 64）为防护上界，超过它请求只在 OkHttp
         * 内排队，无任何收益。
         */
        private const val MAX_REFRESH_CONCURRENCY = 64

        /** 进入首页自动刷新的延迟（毫秒），对齐 View 版节奏 */
        private const val AUTO_REFRESH_DELAY_MS = 1000L
    }
}
