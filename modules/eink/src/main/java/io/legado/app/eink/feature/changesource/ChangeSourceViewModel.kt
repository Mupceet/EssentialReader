package io.legado.app.eink.feature.changesource

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.eink.R
import io.legado.app.eink.arch.UserMessage
import io.legado.app.eink.engine.BookHandle
import io.legado.app.eink.engine.EInkEngineRegistry
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
    val book: ChangeSourceBookUiModel? = null,
    val results: List<ChangeSourceResultUiModel> = emptyList(),
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
 * 复用 View 版换源链路（引擎侧迁移管线经 ChangeSourceEngine 端口）：
 * 跨书源并发搜索书名（校验作者）→ 选中后由宿主迁移进度并重载阅读会话。
 * VM 保留并发编排（信号量限流、超时、按到达顺序追加、去重）。
 */
class ChangeSourceViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val SEARCH_TIMEOUT_MS = 30_000L
        private const val PARALLELISM = 8
    }

    private val engine get() = EInkEngineRegistry.changeSourceEngine
    private val settings get() = EInkEngineRegistry.globalSettings

    private val _uiState = MutableStateFlow(ChangeSourceUiState())
    val uiState: StateFlow<ChangeSourceUiState> = _uiState.asStateFlow()

    private val _messages = MutableSharedFlow<UserMessage>()
    val messages: SharedFlow<UserMessage> = _messages.asSharedFlow()

    private var bookHandle: BookHandle? = null

    private var searchJob: Job? = null
    private var changeJob: Job? = null

    fun load(bookUrl: String) {
        if (_uiState.value.book != null) return
        viewModelScope.launch(Dispatchers.IO) {
            val found = engine.currentReadingBook(bookUrl)
            if (found == null) {
                _uiState.update { it.copy(error = "书籍不存在") }
                return@launch
            }
            val (handle, book) = found
            bookHandle = handle
            _uiState.update { it.copy(book = book) }
            startSearch()
        }
    }

    /**
     * 跨书源搜索：结果按到达顺序追加（E-Ink 无动画，逐条刷新即可）。
     */
    fun startSearch() {
        val book = _uiState.value.book ?: return
        val handle = bookHandle ?: return
        searchJob?.cancel()
        _uiState.update {
            ChangeSourceUiState(book = book, isSearching = true)
        }
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            val sources = engine.enabledSources()
            val checkAuthor = settings.changeSourceCheckAuthor
            _uiState.update { it.copy(totalSourceCount = sources.size) }

            val semaphore = Semaphore(min(PARALLELISM, settings.threadCount))
            val searched = AtomicInteger(0)
            coroutineScope {
                sources.map { source ->
                    launch {
                        semaphore.withPermit {
                            try {
                                withTimeout(SEARCH_TIMEOUT_MS) {
                                    engine.searchSourceBook(source, book.name, book.author, checkAuthor)
                                        .forEach { searchBook ->
                                            if (searchBook.bookUrl != book.bookUrl) {
                                                onSearchSuccess(searchBook)
                                            }
                                        }
                                }
                            } catch (e: kotlinx.coroutines.CancellationException) {
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

    /**
     * 中止搜索：取消任务并保留已到达的结果
     * （对齐主项目 ChangeBookSourceComposeViewModel.stopSearch 语义）。
     */
    fun stopSearch() {
        searchJob?.cancel()
        searchJob = null
        _uiState.update { it.copy(isSearching = false) }
    }

    /**
     * 顶栏刷新按钮入口：搜索中点击中止，否则重新搜索
     * （对齐主项目 startOrStopSearch）。
     */
    fun startOrStopSearch() {
        if (searchJob?.isActive == true) {
            stopSearch()
        } else {
            startSearch()
        }
    }

    private fun onSearchSuccess(searchBook: ChangeSourceResultUiModel) {
        _uiState.update { state ->
            // 去重：同一书源同一书籍只保留一条
            if (state.results.any { it.primary == searchBook.primary }) {
                state
            } else {
                state.copy(results = state.results + searchBook)
            }
        }
    }

    /**
     * 应用换源：宿主获取目录 → 迁移进度 → 替换记录 → 重载阅读会话。
     * 成功后由 UI 层 pop 返回阅读页（Route 层回调）。
     */
    fun changeTo(searchBook: ChangeSourceResultUiModel, onChanged: () -> Unit) {
        if (_uiState.value.isChanging) return
        val handle = bookHandle ?: return
        changeJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isChanging = true) }
            try {
                engine.changeBookSource(handle, searchBook)
                    .onSuccess { newHandle ->
                        bookHandle = newHandle
                        // 导航状态必须在主线程变更
                        withContext(Dispatchers.Main) { onChanged() }
                    }
                    .onFailure {
                        _messages.emit(UserMessage.from(R.string.eink_change_source_failed))
                    }
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
