package io.legado.app.eink.changesource

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchBook
import io.legado.app.eink.arch.UserMessage
import io.legado.app.help.book.primaryStr
import io.legado.app.help.book.releaseHtmlData
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadBook
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * 换源 UiState。
 */
data class ChangeSourceUiState(
    val book: Book? = null,
    val results: List<SearchBook> = emptyList(),
    val isSearching: Boolean = false,
    val searchedCount: Int = 0,
    val totalSourceCount: Int = 0,
    val isChanging: Boolean = false,
    val error: String? = null,
) {
    val isEmpty: Boolean
        get() = !isSearching && results.isEmpty() && error == null && book != null
}

/**
 * 换源 ViewModel。
 *
 * 简化复用 View 版 [io.legado.app.ui.book.changesource.ChangeBookSourceViewModel]
 * 的核心链路：跨书源搜索书名（校验作者）→ 选中后获取目录 →
 * [Book.migrateTo] 迁移进度 → 替换数据库记录 → 重载 [ReadBook] 会话。
 */
class ChangeSourceViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val SEARCH_TIMEOUT_MS = 30_000L
        private const val PARALLELISM = 8
    }

    private val _uiState = MutableStateFlow(ChangeSourceUiState())
    val uiState: StateFlow<ChangeSourceUiState> = _uiState.asStateFlow()

    private val _messages = MutableSharedFlow<UserMessage>()
    val messages: SharedFlow<UserMessage> = _messages.asSharedFlow()

    private var searchJob: Job? = null
    private var changeJob: Job? = null

    fun load(bookUrl: String) {
        if (_uiState.value.book != null) return
        viewModelScope.launch(Dispatchers.IO) {
            val book = ReadBook.book?.takeIf { it.bookUrl == bookUrl }
                ?: appDb.bookDao.getBook(bookUrl)
            if (book == null) {
                _uiState.update { it.copy(error = "书籍不存在") }
                return@launch
            }
            _uiState.update { it.copy(book = book) }
            startSearch()
        }
    }

    /**
     * 跨书源搜索：结果按到达顺序追加（E-Ink 无动画，逐条刷新即可）。
     */
    fun startSearch() {
        val book = _uiState.value.book ?: return
        searchJob?.cancel()
        _uiState.update {
            ChangeSourceUiState(book = book, isSearching = true)
        }
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            val sources = appDb.bookSourceDao.allEnabledPart
                .mapNotNull { it.getBookSource() }
                .filter { !it.bookSourceUrl.isBlank() }
            val author = book.author.replace(AppPattern.authorRegex, "")
            val checkAuthor = AppConfig.changeSourceCheckAuthor
            _uiState.update { it.copy(totalSourceCount = sources.size) }

            val semaphore = Semaphore(min(PARALLELISM, AppConfig.threadCount))
            val searched = AtomicInteger(0)
            coroutineScope {
                sources.map { source ->
                    launch {
                        semaphore.withPermit {
                            try {
                                withTimeout(SEARCH_TIMEOUT_MS) {
                                    WebBook.searchBookAwait(
                                        source,
                                        book.name,
                                        filter = { fName, fAuthor ->
                                            fName == book.name &&
                                                    (!checkAuthor || fAuthor.contains(author))
                                        }
                                    ).forEach { searchBook ->
                                        if (searchBook.bookUrl != book.bookUrl) {
                                            onSearchSuccess(searchBook, book)
                                        }
                                    }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Throwable) {
                                // 单个书源失败不影响整体
                            }
                            _uiState.update {
                                it.copy(searchedCount = searched.incrementAndGet())
                            }
                        }
                    }
                }.joinAll()
            }
            _uiState.update { it.copy(isSearching = false) }
            if (_uiState.value.results.isEmpty()) {
                _messages.emit(UserMessage.from(R.string.eink_change_source_no_result))
            }
        }
    }

    private fun onSearchSuccess(searchBook: SearchBook, book: Book) {
        searchBook.releaseHtmlData()
        _uiState.update { state ->
            // 去重：同一书源同一书籍只保留一条
            if (state.results.any { it.primaryStr() == searchBook.primaryStr() }) {
                state
            } else {
                state.copy(results = state.results + searchBook)
            }
        }
    }

    /**
     * 应用换源：获取目录 → 迁移进度 → 替换记录 → 重载阅读会话。
     * 成功后由 UI 层 pop 返回阅读页（Route 层回调）。
     */
    fun changeTo(searchBook: SearchBook, onChanged: () -> Unit) {
        if (_uiState.value.isChanging) return
        val oldBook = _uiState.value.book ?: return
        changeJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isChanging = true) }
            try {
                val source = appDb.bookSourceDao.getBookSource(searchBook.origin)
                    ?: throw IllegalStateException("书源不存在")
                val newBook = searchBook.toBook()
                if (newBook.tocUrl.isEmpty()) {
                    WebBook.getBookInfoAwait(source, newBook)
                }
                val toc = WebBook.getChapterListAwait(source, newBook).getOrThrow()

                oldBook.migrateTo(newBook, toc)
                newBook.removeType(BookType.updateError)
                oldBook.delete()
                appDb.bookDao.insert(newBook)
                appDb.bookChapterDao.insert(*toc.toTypedArray())

                // 重载引擎会话；阅读页返回时会采用引擎当前书籍
                ReadBook.resetData(newBook)
                ReadBook.loadContent(resetPageOffset = true)

                // 导航状态必须在主线程变更
                withContext(Dispatchers.Main) { onChanged() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.put("换源失败\n${e.localizedMessage}", e)
                _messages.emit(UserMessage.from(R.string.eink_change_source_failed))
            } finally {
                _uiState.update { it.copy(isChanging = false) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        searchJob?.cancel()
        changeJob?.cancel()
    }
}
