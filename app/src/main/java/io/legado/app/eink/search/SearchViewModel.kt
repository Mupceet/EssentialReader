package io.legado.app.eink.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.appDb
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.config.AppConfig
import io.legado.app.model.webBook.SearchModel
import io.legado.app.ui.book.search.SearchScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 搜索 UiState（扁平布尔标志位）。
 */
data class SearchUiState(
    val searchKey: String = "",
    val results: List<SearchBook> = emptyList(),
    val history: List<SearchKeyword> = emptyList(),
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
 * 桥接 [SearchModel]（回调式）到 [StateFlow]，
 * 复用 [appDb.searchKeywordDao] 搜索历史与 [appDb.bookDao] 书架判断。
 */
class SearchViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val bookshelfKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private var searchId = 0L

    private val searchModel = SearchModel(viewModelScope, object : SearchModel.CallBack {

        override fun getSearchScope(): SearchScope = SearchScope(AppConfig.searchScope)

        override fun onSearchStart() {
            _uiState.update { it.copy(isSearching = true) }
        }

        override fun onSearchSuccess(searchBooks: List<SearchBook>) {
            _uiState.update { state ->
                state.copy(
                    results = searchBooks,
                    isEmptyResult = searchBooks.isEmpty()
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
    })

    init {
        // 书架 key 集合（判断搜索结果是否已在书架）
        viewModelScope.launch {
            appDb.bookDao.flowAll().collect { books ->
                bookshelfKeys.clear()
                books.filterNot { it.isNotShelf }.forEach {
                    bookshelfKeys.add("${it.name}-${it.author}")
                    bookshelfKeys.add(it.name)
                    bookshelfKeys.add(it.bookUrl)
                }
            }
        }
        // 搜索历史（按使用频次）
        viewModelScope.launch {
            appDb.searchKeywordDao.flowByUsage().collect { history ->
                _uiState.update { it.copy(history = history) }
            }
        }
    }

    /** 是否已在书架 */
    fun isInBookshelf(book: SearchBook): Boolean {
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
            searchModel.cancelSearch()
            searchId = System.currentTimeMillis()
            searchModel.search(searchId, key)
            appDb.searchKeywordDao.get(key)?.let {
                it.usage += 1
                it.lastUseTime = System.currentTimeMillis()
                appDb.searchKeywordDao.update(it)
            } ?: appDb.searchKeywordDao.insert(SearchKeyword(key, 1))
        }
    }

    /** 清空搜索历史 */
    fun clearHistory() {
        viewModelScope.launch {
            appDb.searchKeywordDao.deleteAll()
        }
    }

    override fun onCleared() {
        super.onCleared()
        searchModel.close()
    }
}
