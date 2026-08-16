package io.legado.app.eink.bookdetail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.eink.arch.UserMessage
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.addType
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.book.removeType
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.CancellationException
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
    val book: Book? = null,
    val isLoading: Boolean = true,
    val isInBookshelf: Boolean = false,
) {
    /** 未找到书籍且不在加载中 */
    val isEmpty: Boolean get() = book == null && !isLoading
}

/**
 * 书籍详情 ViewModel。
 *
 * 数据加载顺序（参考 View 版 `BookInfoViewModel.initData`）：
 *  1. [appDb.bookDao.getBook] 按书名/作者或 bookUrl 查书架
 *  2. [appDb.searchBookDao] 搜索结果 `toBook()`
 *
 * 不在书架且缺目录信息时，联网调用 [WebBook.getBookInfoAwait] 拉取完整详情
 * （简介/标签/最新章节/封面/字数）。书籍展示后后台预取目录入库
 * （[prefetchChapterList]），使首次进入阅读页只剩正文下载。
 */
class BookDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(BookDetailUiState())
    val uiState: StateFlow<BookDetailUiState> = _uiState.asStateFlow()

    private val _messages = MutableSharedFlow<UserMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<UserMessage> = _messages.asSharedFlow()

    private var loadedKey: String? = null

    fun loadBook(name: String, author: String, bookUrl: String) {
        val key = "$name\u0000$author\u0000$bookUrl"
        if (loadedKey == key) {
            // 同书重进（如从阅读页返回）：静默刷新书架状态与书籍信息，
            // 不重置加载态（避免闪加载页）；书可能在阅读中被移出/加回书架
            viewModelScope.launch {
                _uiState.value.book?.let { current ->
                    val fresh = appDb.bookDao.getBook(current.bookUrl) ?: return@let
                    _uiState.update {
                        it.copy(book = fresh, isInBookshelf = !fresh.isNotShelf)
                    }
                }
            }
            return
        }
        loadedKey = key
        _uiState.update { it.copy(isLoading = true, book = null) }
        viewModelScope.launch {
            val book = findBook(name, author, bookUrl)
            if (book == null) {
                _uiState.update { it.copy(isLoading = false) }
                return@launch
            }
            val inShelf = appDb.bookDao.getBook(book.bookUrl)?.let { !it.isNotShelf } ?: false
            _uiState.update {
                it.copy(book = book, isInBookshelf = inShelf, isLoading = false)
            }
            prefetchChapterList(book, inShelf)
        }
    }

    /**
     * 后台预取目录入库：把新书首次打开阅读页时"详情→目录→正文"的串行下载
     * 缩减为只剩正文下载（对齐 View 版详情页 BookInfoViewModel 的拉取时机，
     * 详情缺失时先拉详情，再拉目录）。
     *
     * 未加书架的书落 notShelf 隐藏行（同阅读页/目录页兜底行为），否则阅读页
     * 会从 SearchBook 重建 tocUrl 为空的 Book，导致目录被重复拉取。静默失败：
     * 阅读页 ReaderViewModel.initBookData 仍作为兜底。
     */
    private fun prefetchChapterList(book: Book, inShelf: Boolean) {
        if (book.isLocal) return
        viewModelScope.launch(Dispatchers.IO) {
            if (appDb.bookChapterDao.getChapterList(book.bookUrl).isNotEmpty()) return@launch
            val source = appDb.bookSourceDao.getBookSource(book.origin) ?: return@launch
            val oldBook = book.copy()
            val chapters = runCatching {
                if (book.tocUrl.isEmpty()) {
                    WebBook.getBookInfoAwait(source, book, canReName = true)
                }
                WebBook.getChapterListAwait(source, book, runPerJs = inShelf).getOrThrow()
            }.getOrElse { e ->
                if (e is CancellationException) throw e
                AppLog.put("详情页预取目录出错《${book.name}》\n${e.localizedMessage}", e)
                return@launch
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
            _uiState.update { it.copy(book = book) }
        }
    }

    private fun findBook(name: String, author: String, bookUrl: String): Book? {
        appDb.bookDao.getBook(name, author)?.let { return it }
        if (bookUrl.isNotBlank()) {
            appDb.bookDao.getBook(bookUrl)?.let { return it }
            appDb.searchBookDao.getSearchBook(bookUrl)?.toBook()?.let { return it }
        }
        appDb.searchBookDao.getFirstByNameAuthor(name, author)?.toBook()?.let { return it }
        return null
    }

    /** 加入书架（参考 View 版 `BookInfoViewModel.addToBookshelf`）。 */
    fun addToBookshelf() {
        val book = _uiState.value.book ?: return
        viewModelScope.launch {
            runCatching {
                book.removeType(BookType.notShelf)
                if (book.order == 0) {
                    book.order = appDb.bookDao.minOrder - 1
                }
                appDb.bookDao.getBook(book.name, book.author)?.let {
                    book.durChapterIndex = it.durChapterIndex
                    book.durChapterPos = it.durChapterPos
                    book.durChapterTitle = it.durChapterTitle
                }
                book.save()
            }.onSuccess {
                _uiState.update { it.copy(isInBookshelf = true) }
                _messages.emit(UserMessage.from(R.string.eink_added_to_bookshelf))
            }.onFailure {
                _messages.emit(UserMessage.from(R.string.eink_operation_failed))
            }
        }
    }

    /** 移出书架（仅已在书架的书）。 */
    fun removeFromBookshelf() {
        val book = _uiState.value.book ?: return
        viewModelScope.launch {
            runCatching {
                book.addType(BookType.notShelf)
                book.save()
            }.onSuccess {
                _uiState.update { it.copy(isInBookshelf = false) }
                _messages.emit(UserMessage.from(R.string.eink_removed_from_bookshelf))
            }.onFailure {
                _messages.emit(UserMessage.from(R.string.eink_operation_failed))
            }
        }
    }

    /** 切换书源：本期占位，后续实现跨书源搜索重载。 */
    fun changeSource() {
        viewModelScope.launch {
            _messages.emit(UserMessage.from(R.string.eink_feature_developing))
        }
    }
}