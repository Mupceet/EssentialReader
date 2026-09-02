package io.legado.app.eink.feature.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.contract.SearchBookUiModel
import io.legado.app.eink.contract.SearchHistoryUiModel
import io.legado.app.eink.contract.SearchSession
import io.legado.app.eink.contract.SearchSessionCallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 搜索 UiState（扁平布尔标志位）。
 */
data class SearchUiState(
    val searchKey: String = "",
    val results: List<SearchBookUiModel> = emptyList(),
    val history: List<SearchHistoryUiModel> = emptyList(),
    val isSearching: Boolean = false,
    val searched: Boolean = false,
    val isEmptyResult: Boolean = false,
) {

    /** 无结果且不在搜索中 */
    val showEmpty: Boolean
        get() = searched && !isSearching && isEmptyResult
}

/**
 * 搜索 ViewModel。
 *
 * 经 SearchEngine 端口桥接宿主搜索模型（回调式 → StateFlow）与搜索
 * 历史；上游搜索实现差异由桥接层吸收。
 */
class SearchViewModel(application: Application) : AndroidViewModel(application) {

    private val engine get() = EInkEngineRegistry.searchEngine

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var bookshelfKeys: Set<String> = emptySet()

    private var searchId = 0L

    private val searchSession: SearchSession = engine.createSearchSession(
        object : SearchSessionCallback {

            override fun onSearchStart() {
                _uiState.update { it.copy(isSearching = true) }
            }

            override fun onSearchSuccess(books: List<SearchBookUiModel>) {
                _uiState.update { state ->
                    state.copy(
                        results = books,
                        isEmptyResult = books.isEmpty()
                    )
                }
            }

            override fun onSearchFinish(isEmpty: Boolean, hasMore: Boolean) {
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        isEmptyResult = isEmpty && it.results.isEmpty()
                    )
                }
            }

            override fun onSearchCancel(exception: Throwable?) {
                _uiState.update { it.copy(isSearching = false) }
            }
        }
    )

    init {
        // 书架 key 集合（判断搜索结果是否已在书架）
        viewModelScope.launch {
            engine.observeBookshelfKeys().collect { keys ->
                bookshelfKeys = keys
            }
        }
        // 搜索历史（按使用频次）
        viewModelScope.launch {
            engine.observeSearchHistory().collect { history ->
                _uiState.update { it.copy(history = history) }
            }
        }
    }

    /** 是否已在书架 */
    fun isInBookshelf(book: SearchBookUiModel): Boolean {
        val key = if (book.author.isNotBlank()) "${book.name}-${book.author}" else book.name
        return bookshelfKeys.contains(key) || bookshelfKeys.contains(book.bookUrl)
    }

    /** 更新输入（未提交搜索时） */
    fun updateKey(key: String) {
        _uiState.update { it.copy(searchKey = key) }
    }

    /** 开始搜索 */
    fun search(key: String) {
        if (key.isBlank()) return
        _uiState.update {
            it.copy(
                searchKey = key,
                results = emptyList(),
                searched = true,
                isSearching = true,
                isEmptyResult = false
            )
        }
        viewModelScope.launch {
            searchSession.cancelSearch()
            searchId = System.currentTimeMillis()
            searchSession.search(searchId, key)
            engine.recordSearchKey(key)
        }
    }

    /** 停止进行中的搜索（保留已得结果） */
    fun stopSearch() {
        searchSession.cancelSearch()
        _uiState.update { it.copy(isSearching = false) }
    }

    /** 清空搜索历史 */
    fun clearHistory() {
        viewModelScope.launch {
            engine.clearSearchHistory()
        }
    }

    override fun onCleared() {
        super.onCleared()
        searchSession.close()
    }
}
