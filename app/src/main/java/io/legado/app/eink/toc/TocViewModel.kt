package io.legado.app.eink.toc

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.addType
import io.legado.app.help.book.isLocal
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 目录 UiState。
 */
data class TocUiState(
    val book: Book? = null,
    val chapters: List<BookChapter> = emptyList(),
    val isLoading: Boolean = true,
    val isReversed: Boolean = false,
    val searchKey: String = "",
    val error: String? = null,
    /** 已缓存章节的文件名集合（未缓存章节显示图标，参考 View 版） */
    val cachedFileNames: Set<String> = emptySet(),
    val isLocalBook: Boolean = false,
) {

    /** 当前阅读章节索引 */
    val durChapterIndex: Int
        get() = book?.durChapterIndex ?: 0

    /** 过滤后的章节（搜索时按标题过滤） */
    val displayChapters: List<BookChapter>
        get() = if (searchKey.isBlank()) chapters else {
            chapters.filter { it.title.contains(searchKey, ignoreCase = true) }
        }

    val isEmpty: Boolean
        get() = !isLoading && displayChapters.isEmpty()
}

/**
 * 目录 ViewModel。
 *
 * 复用 [appDb.bookDao.getBook] 与 [appDb.bookChapterDao.getChapterList]（同步 API，
 * Room 本身 main-safe，直接在协程中调用）。
 */
class TocViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(TocUiState())
    val uiState: StateFlow<TocUiState> = _uiState.asStateFlow()

    private var loadedBookUrl: String? = null

    fun loadBook(bookUrl: String) {
        if (loadedBookUrl == bookUrl) {
            // 同书重进（阅读返回后再进目录）：刷新缓存标记集合
            refreshCacheFiles()
            return
        }
        loadedBookUrl = bookUrl
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            // 书架记录优先；未加书架的搜索书转 Book 入库（notShelf，不显示于书架，
            // 与 View 版"未加书架直接阅读"行为一致），使进度与目录缓存可写
            val book = appDb.bookDao.getBook(bookUrl)
                ?: appDb.searchBookDao.getSearchBook(bookUrl)?.toBook()?.apply {
                    addType(BookType.notShelf)
                    save()
                }
            if (book == null) {
                _uiState.update { it.copy(isLoading = false, error = "书籍不存在") }
                return@launch
            }
            var chapters = appDb.bookChapterDao.getChapterList(bookUrl)
            if (chapters.isEmpty() && !book.isLocal) {
                // 目录缺失（未阅读过的新书）：从书源拉取入库
                val source = appDb.bookSourceDao.getBookSource(book.origin)
                if (source == null) {
                    _uiState.update { it.copy(isLoading = false, error = "没有书源") }
                    return@launch
                }
                try {
                    if (book.tocUrl.isEmpty()) {
                        WebBook.getBookInfoAwait(source, book)
                    }
                    chapters = WebBook.getChapterListAwait(source, book, true).getOrThrow()
                    appDb.bookChapterDao.delByBook(bookUrl)
                    appDb.bookChapterDao.insert(*chapters.toTypedArray())
                    appDb.bookDao.update(book)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    _uiState.update {
                        it.copy(isLoading = false, error = "目录加载失败：${e.localizedMessage}")
                    }
                    return@launch
                }
            }
            _uiState.update {
                it.copy(
                    book = book,
                    chapters = chapters,
                    isLoading = false
                )
            }
            refreshCacheFiles()
        }
    }

    /** 收集已缓存章节文件名集合（本地书为空集合，经 isLocalBook 视为全部已缓存）。 */
    private fun refreshCacheFiles() {
        val book = _uiState.value.book ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val cached = if (book.isLocal) emptySet() else BookHelp.getChapterFiles(book)
            _uiState.update {
                it.copy(cachedFileNames = cached, isLocalBook = book.isLocal)
            }
        }
    }

    /** 倒序/正序切换 */
    fun toggleReverse() {
        _uiState.update { it.copy(isReversed = !it.isReversed) }
    }

    /** 章节标题过滤 */
    fun search(key: String) {
        _uiState.update { it.copy(searchKey = key) }
    }

    /**
     * 跳转到指定章节：记录进度到 Book（从第 1 页开始，重置页内位置），
     * 完成后回调（用于进入阅读页，保证阅读页读取到已更新的进度）。
     */
    fun openChapter(index: Int, onSaved: (() -> Unit)? = null) {
        val book = _uiState.value.book ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val chapter = _uiState.value.chapters.getOrNull(index) ?: return@launch
            book.durChapterIndex = index
            book.durChapterPos = 0
            book.durChapterTitle = chapter.title
            book.durChapterTime = System.currentTimeMillis()
            appDb.bookDao.update(book)
            _uiState.update { it.copy(book = book) }
            onSaved?.invoke()
        }
    }
}
