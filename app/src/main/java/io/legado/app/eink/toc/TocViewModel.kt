package io.legado.app.eink.toc

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
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
        if (loadedBookUrl == bookUrl) return
        loadedBookUrl = bookUrl
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val book = appDb.bookDao.getBook(bookUrl)
            if (book == null) {
                _uiState.update { it.copy(isLoading = false, error = "书籍不存在") }
                return@launch
            }
            val chapters = appDb.bookChapterDao.getChapterList(bookUrl)
            _uiState.update {
                it.copy(
                    book = book,
                    chapters = chapters,
                    isLoading = false
                )
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
     * 跳转到指定章节：记录进度到 Book，完成后回调（用于进入阅读页，
     * 保证阅读页读取到已更新的进度）。
     */
    fun openChapter(index: Int, onSaved: (() -> Unit)? = null) {
        val book = _uiState.value.book ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val chapter = _uiState.value.chapters.getOrNull(index) ?: return@launch
            book.durChapterIndex = index
            book.durChapterTitle = chapter.title
            book.durChapterTime = System.currentTimeMillis()
            appDb.bookDao.update(book)
            _uiState.update { it.copy(book = book) }
            onSaved?.invoke()
        }
    }
}
