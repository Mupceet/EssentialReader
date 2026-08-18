package io.legado.app.eink.bookshelf

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.eink.arch.UserMessage
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.addType
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isUpError
import io.legado.app.help.book.removeType
import io.legado.app.help.book.sync
import io.legado.app.help.config.AppConfig
import io.legado.app.model.CacheBook
import io.legado.app.model.ReadBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.onEachParallel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/**
 * 书架屏幕 UiState（扁平布尔标志位，参考 JBusDriver UDF 模式）。
 */
data class BookshelfUiState(
    val books: List<Book> = emptyList(),
    val isLoading: Boolean = true,
    /** 目录刷新进行中（首页头部刷新按钮禁用并置灰） */
    val isRefreshing: Boolean = false,
    /** 正在更新目录的书籍 bookUrl 集合（对应行角标替换为刷新态） */
    val updatingBookUrls: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = books.isEmpty() && !isLoading
}

/**
 * 书架 ViewModel。
 *
 * 书架数据直接复用 [appDb.bookDao.flowByGroup]（[BookGroup.IdAll]），
 * 与刷新标志合并为单一 UiState 流。
 *
 * 加载态只出现在首次订阅前（[stateIn] 初始值）；重新订阅（如从阅读页
 * 返回）时 [stateIn] 保留着上次数据，Room 流重发同值不触发重组，
 * 不会重复闪加载页。
 *
 * 刷新（[refresh]）复刻 View 版下拉刷新（`MainViewModel.upToc/updateToc`）：
 * 并发拉取书架书目录，进度经 [Book.sync] 保留，失败标记 updateError；
 * 进入首页（ViewModel 创建）后延迟自动刷新一次。
 */
class BookshelfViewModel(application: Application) : AndroidViewModel(application) {

    private val _messages = MutableSharedFlow<UserMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<UserMessage> = _messages.asSharedFlow()

    private val _isRefreshing = MutableStateFlow(false)
    private val _updatingUrls = MutableStateFlow<Set<String>>(emptySet())

    // notShelf 行已在 [flowByGroup] 内过滤，并在 init 中物理删除；此处
    // 直接使用查询结果，与 View 版保持一致。
    val uiState: StateFlow<BookshelfUiState> =
        combine(
            appDb.bookDao.flowByGroup(BookGroup.IdAll),
            _isRefreshing,
            _updatingUrls
        ) { books, refreshing, updatingUrls ->
            BookshelfUiState(
                books = books,
                isLoading = false,
                isRefreshing = refreshing,
                updatingBookUrls = updatingUrls,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookshelfUiState())

    private var refreshJob: Job? = null
    private var cacheBookJob: Job? = null

    init {
        // 与 View 版 MainViewModel.init 对齐：启动时物理删除未加书架
        //（notShelf）的隐藏行。详情页/搜索/阅读页的预取可能写入 notShelf 行，
        // 统一在此清理，后续查询与刷新无需再逐个过滤。
        viewModelScope.launch(Dispatchers.IO) {
            appDb.bookDao.deleteNotShelfBook()
        }
        // 与 View 版 MainActivity 自动刷新一致：仅在设置开启时，
        // 进入首页延迟 1 秒自动刷新一次；ViewModel 常驻，
        // 从阅读页/搜索返回不会重复触发。
        if (AppConfig.autoRefreshBook) {
            viewModelScope.launch {
                delay(AUTO_REFRESH_DELAY_MS)
                refresh()
            }
        }
    }

    /**
     * 刷新书架：进行中时直接忽略，与 View 版 upToc 的排队去重行为一致。
     * View 版下拉刷新和菜单“更新目录”都不会中断当前任务，重复触发只会排队
     * 当前书架中尚未处理的书籍；E-Ink 首页展示全部书籍，因此这里简化为
     * 活动期间 no-op。
     */
    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch(Dispatchers.IO) {
            val myJob = coroutineContext[Job]
            _isRefreshing.value = true
            _updatingUrls.value = emptySet()
            try {
                val books = appDb.bookDao.flowByGroup(BookGroup.IdAll).first()
                    .filter { !it.isLocal && it.canUpdate }
                books.asFlow()
                    .onEachParallel(min(AppConfig.threadCount, AppConst.MAX_THREAD)) { book ->
                        updateToc(book.bookUrl)
                    }
                    .collect()
            } finally {
                if (refreshJob === myJob) {
                    _isRefreshing.value = false
                    // 无论正常完成还是被取消/失败，都启动预缓存泵处理已入队
                    // 章节，避免重复点击刷新导致上一轮已入队任务被遗留
                    //（E-Ink 无前台服务，泵需在本进程内及时运转）。
                    startCacheBook()
                }
            }
        }
    }

    /**
     * 预缓存泵（对齐 View 版 `MainViewModel.cacheBook`）：消费
     * [addDownload] 入队的章节，已缓存跳过、失败重试 3 次。目录刷新
     * 进行中暂停泵（目录优先，对齐 View 版 workingState 联动）。
     * View 版的 CacheBookService 前台服务判断省略——E-Ink 模式与
     * 完整模式互斥（切换即 CLEAR_TASK），服务不会并行运行。
     */
    private fun startCacheBook() {
        if (AppConfig.preDownloadNum == 0) return
        if (cacheBookJob?.isActive == true) return
        cacheBookJob = viewModelScope.launch(Dispatchers.IO) {
            launch {
                while (isActive && CacheBook.isRun) {
                    CacheBook.setWorkingState(refreshJob?.isActive != true)
                    delay(1000)
                }
            }
            CacheBook.startProcessJob(Dispatchers.IO)
        }
    }

    /**
     * 目录刷新完成后入队预缓存章节（对齐 View 版
     * `MainViewModel.addDownload`）：当前进度起往后 preDownloadNum 章。
     */
    private fun addDownload(source: BookSource, book: Book) {
        if (AppConfig.preDownloadNum == 0) return
        val endIndex = min(
            book.totalChapterNum - 1,
            book.durChapterIndex.plus(AppConfig.preDownloadNum)
        )
        CacheBook.getOrCreate(source, book).addDownload(book.durChapterIndex, endIndex)
    }

    /**
     * 更新一本书的目录（复刻 View 版 `MainViewModel.updateToc`）：
     * 缺 tocUrl 先拉详情、否则执行书源 preUpdateJs；拉目录后同步保留
     * 阅读进度与配置，重定向时替换书架记录并迁移缓存目录；失败标记
     * updateError；成功后入队预缓存（[addDownload]）。
     */
    private suspend fun updateToc(bookUrl: String) {
        _updatingUrls.update { it + bookUrl }
        try {
            val book = appDb.bookDao.getBook(bookUrl) ?: return
            val source = appDb.bookSourceDao.getBookSource(book.origin)
            if (source == null) {
                if (!book.isUpError) {
                    book.addType(BookType.updateError)
                    appDb.bookDao.update(book)
                }
                return
            }
            kotlin.runCatching {
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
                addDownload(source, book)
            }.onFailure {
                currentCoroutineContext().ensureActive()
                AppLog.put("${book.name} 更新目录失败\n${it.localizedMessage}", it)
                //这里可能因为时间太长书籍信息已经更改,所以重新获取
                appDb.bookDao.getBook(book.bookUrl)?.let { curBook ->
                    curBook.addType(BookType.updateError)
                    appDb.bookDao.update(curBook)
                }
            }
        } finally {
            _updatingUrls.update { it - bookUrl }
        }
    }

    companion object {
        /** 进入首页自动刷新的延迟（毫秒），对齐 View 版节奏 */
        private const val AUTO_REFRESH_DELAY_MS = 1000L
    }
}
