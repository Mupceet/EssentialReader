package io.legado.app.eink.feature.bookdetail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.eink.R
import io.legado.app.eink.arch.UserMessage
import io.legado.app.eink.contract.BookHandle
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.contract.BookDetailPrefetchResult
import io.legado.app.eink.contract.BookDetailUiModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 书籍详情 UiState（扁平布尔标志位，遵循 E-Ink UDF 约定）。
 */
data class BookDetailUiState(
    val book: BookDetailUiModel? = null,
    val isLoading: Boolean = true,
    val isInBookshelf: Boolean = false,
) {
    /** 未找到书籍且不在加载中 */
    val isEmpty: Boolean get() = book == null && !isLoading
}

/**
 * 书籍详情 ViewModel。
 *
 * 数据加载与书架操作经 BookDetailEngine 端口（宿主查找链/预取管线），
 * 引擎身份（[BookHandle]）由 VM 持有并随操作回传——实体可能被预取
 * 就地更新或重定向替换。
 *
 * 数据加载顺序（参考 View 版 `BookInfoViewModel.initData`）：
 *  1. 宿主按书名/作者或 bookUrl 查书架
 *  2. 宿主从搜索记录转 Book
 *
 * 不在书架且缺目录信息时，宿主联网拉取完整详情（简介/标签/最新章节/
 * 封面/字数）。书籍展示后后台预取目录入库，使首次进入阅读页只剩正文
 * 下载。
 */
class BookDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val engine get() = EInkEngineRegistry.bookDetailEngine

    private val _uiState = MutableStateFlow(BookDetailUiState())
    val uiState: StateFlow<BookDetailUiState> = _uiState.asStateFlow()

    private val _messages = MutableSharedFlow<UserMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<UserMessage> = _messages.asSharedFlow()

    private var loadedKey: String? = null
    private var bookHandle: BookHandle? = null

    init {
        // 换源成功后跟随刷新：换源会删除旧记录并以新 bookUrl 重建（阅读
        // 会话已由引擎重载）。本页位于换源页下方时 ViewModel 仍存活，
        // 按事件重载，pop 返回即见新源数据（含从下层阅读页发起的换源）
        viewModelScope.launch {
            EInkEngineRegistry.changeSourceEngine.bookChanged.collect { newBookUrl ->
                val current = _uiState.value.book ?: return@collect
                if (current.bookUrl == newBookUrl) return@collect
                val fresh = engine.loadBookDetail(newBookUrl) ?: return@collect
                // 句柄同步替换：后续加/移书架作用于新书记录
                val found = engine.findBook(fresh.name, fresh.displayAuthor, newBookUrl)
                if (found != null) {
                    bookHandle = found.first
                }
                val inShelf = engine.isBookInBookshelf(newBookUrl)
                _uiState.update {
                    it.copy(book = found?.second ?: fresh, isInBookshelf = inShelf)
                }
            }
        }
    }

    fun loadBook(name: String, author: String, bookUrl: String) {
        val key = "$name\u0000$author\u0000$bookUrl"
        if (loadedKey == key) {
            // 同书重进（如从阅读页返回）：静默刷新书架状态与书籍信息，
            // 不重置加载态（避免闪加载页）；书可能在阅读中被移出/加回书架
            viewModelScope.launch {
                _uiState.value.book?.let { current ->
                    val fresh = engine.loadBookDetail(current.bookUrl) ?: return@let
                    val inShelf = engine.isBookInBookshelf(current.bookUrl)
                    _uiState.update {
                        it.copy(book = fresh, isInBookshelf = inShelf)
                    }
                }
            }
            return
        }
        loadedKey = key
        _uiState.update { it.copy(isLoading = true, book = null) }
        viewModelScope.launch {
            val found = engine.findBook(name, author, bookUrl)
            if (found == null) {
                _uiState.update { it.copy(isLoading = false) }
                return@launch
            }
            val (handle, book) = found
            bookHandle = handle
            val inShelf = engine.isBookInBookshelf(book.bookUrl)
            _uiState.update {
                it.copy(book = book, isInBookshelf = inShelf, isLoading = false)
            }
            prefetchChapterList(inShelf)
        }
    }

    /**
     * 后台预取目录入库：把新书首次打开阅读页时"详情→目录→正文"的串行
     * 下载缩减为只剩正文下载（对齐 View 版详情页的拉取时机，详情缺失
     * 时先拉详情，再拉目录）。管线在宿主 bridge，静默失败：阅读页
     * 仍作为兜底。
     */
    private fun prefetchChapterList(inShelf: Boolean) {
        val handle = bookHandle ?: return
        viewModelScope.launch(Dispatchers.IO) {
            when (val result = engine.prefetchChapters(handle, inShelf)) {
                is BookDetailPrefetchResult.Updated -> {
                    bookHandle = result.handle
                    _uiState.update { it.copy(book = result.model) }
                }
                BookDetailPrefetchResult.Skipped -> Unit
            }
        }
    }

    /** 加入书架（参考 View 版 `BookInfoViewModel.addToBookshelf`）。 */
    fun addToBookshelf() {
        val handle = bookHandle ?: return
        viewModelScope.launch {
            val ok = engine.addToBookshelf(handle)
            if (ok) {
                _uiState.update { it.copy(isInBookshelf = true) }
                _messages.emit(UserMessage.from(R.string.eink_added_to_bookshelf))
            } else {
                _messages.emit(UserMessage.from(R.string.eink_operation_failed))
            }
        }
    }

    /** 移出书架（仅已在书架的书）。 */
    fun removeFromBookshelf() {
        val handle = bookHandle ?: return
        viewModelScope.launch {
            val ok = engine.removeFromBookshelf(handle)
            if (ok) {
                _uiState.update { it.copy(isInBookshelf = false) }
                _messages.emit(UserMessage.from(R.string.eink_removed_from_bookshelf))
            } else {
                _messages.emit(UserMessage.from(R.string.eink_operation_failed))
            }
        }
    }
}
