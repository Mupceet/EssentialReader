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
 * 结果条目身份键（与结果列表 LazyColumn 的 key 同式）。
 */
internal val SearchBookUiModel.resultKey: String
    get() = "$origin-$bookUrl"

/**
 * 将单源增量合并进已有结果：同 [resultKey] 原位覆盖，保持首见顺序。
 *
 * 上游 UseCase 的 upsertBooks 是逐源 delta，同一源返回同名同作者/同
 * bookUrl 的多条时 delta 内会重复出现同一本书；不合并直接展示会导致
 * LazyColumn key 重复崩溃。
 */
internal fun mergeSearchResults(
    current: List<SearchBookUiModel>,
    delta: List<SearchBookUiModel>,
): List<SearchBookUiModel> {
    val merged = LinkedHashMap<String, SearchBookUiModel>(current.size + delta.size)
    current.forEach { merged[it.resultKey] = it }
    delta.forEach { merged[it.resultKey] = it }
    return merged.values.toList()
}

/**
 * 与主搜索页 sortedWithSearchPriority 同语义的结果排序：
 * 精确命中（name/author 等于关键词）→ 标签命中（kind 含关键词）→
 * 包含命中（name/author 含关键词）→ 其他；前三桶按 origins 数降序稳定
 * 排序（同数保持首见顺序），其他桶保持首见顺序。
 *
 * 非 DEFAULT 匹配模式下"其他"桶的剔除已由上游 UseCase 过滤完成，
 * 这里恒按 DEFAULT 分支分桶，两种模式下顺序与主搜索页一致。
 */
internal fun sortSearchResults(
    books: List<SearchBookUiModel>,
    keyword: String,
): List<SearchBookUiModel> {
    val equalBooks = arrayListOf<SearchBookUiModel>()
    val tagsBooks = arrayListOf<SearchBookUiModel>()
    val containsBooks = arrayListOf<SearchBookUiModel>()
    val otherBooks = arrayListOf<SearchBookUiModel>()
    books.forEach { book ->
        when {
            book.name.equals(keyword, ignoreCase = true) ||
                    book.author.equals(keyword, ignoreCase = true) -> equalBooks.add(book)

            book.kind?.contains(keyword, ignoreCase = true) == true -> tagsBooks.add(book)
            book.name.contains(keyword, ignoreCase = true) ||
                    book.author.contains(keyword, ignoreCase = true) -> containsBooks.add(book)

            else -> otherBooks.add(book)
        }
    }
    return buildList(books.size) {
        addAll(equalBooks.sortedByDescending { it.originsCount })
        addAll(tagsBooks.sortedByDescending { it.originsCount })
        addAll(containsBooks.sortedByDescending { it.originsCount })
        addAll(otherBooks)
    }
}

/**
 * 将搜索历史按 chip 宽度贪心分行（每行总宽不超 [rowWidth]）。
 *
 * 历史 chip 为流式换行布局，而 E-Ink 固定页分页以 LazyColumn 项
 * （一行 chip = 一项）为单位整页翻页；「哪些词进同一行」是纯布局
 * 决策，抽离成函数以便单元测试。[labelWidth] 返回单个 chip 的完整
 * 占位宽（文字实测宽 + 内边距与描边余量，调用方按需封顶）。
 */
internal fun chunkHistoryRows(
    history: List<SearchHistoryUiModel>,
    rowWidth: Int,
    spacing: Int,
    labelWidth: (SearchHistoryUiModel) -> Int,
): List<List<SearchHistoryUiModel>> {
    if (history.isEmpty()) return emptyList()
    val maxWidth = rowWidth.coerceAtLeast(1)
    val rows = ArrayList<List<SearchHistoryUiModel>>()
    var current = ArrayList<SearchHistoryUiModel>()
    var currentWidth = 0
    for (keyword in history) {
        val width = labelWidth(keyword).coerceIn(1, maxWidth)
        if (current.isNotEmpty() && currentWidth + spacing + width > rowWidth) {
            rows.add(current)
            current = ArrayList()
            currentWidth = 0
        }
        currentWidth += if (current.isEmpty()) width else spacing + width
        current.add(keyword)
    }
    if (current.isNotEmpty()) rows.add(current)
    return rows
}

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
    /** 书源维度搜索进展（已完成源数），搜索中顶部提示用。 */
    val searchedSources: Int = 0,
    /** 参与搜索的书源总数。 */
    val totalSources: Int = 0,
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
                _uiState.update {
                    it.copy(isSearching = true, searchedSources = 0, totalSources = 0)
                }
            }

            override fun onSearchProgress(processedSources: Int, totalSources: Int) {
                _uiState.update {
                    it.copy(searchedSources = processedSources, totalSources = totalSources)
                }
            }

            override fun onSearchSuccess(books: List<SearchBookUiModel>) {
                _uiState.update { state ->
                    val merged = mergeSearchResults(state.results, books)
                    state.copy(
                        results = sortSearchResults(merged, state.searchKey),
                        isEmptyResult = merged.isEmpty()
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
            engine.observeBookshelfMatchKeys().collect { keys ->
                bookshelfKeys = keys
            }
        }
        // 搜索历史（按最近使用时间倒序）
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
            engine.recordSearchQuery(key)
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

    /** 删除单条搜索历史 */
    fun removeHistory(word: String) {
        viewModelScope.launch {
            engine.removeSearchHistory(word)
        }
    }

    override fun onCleared() {
        super.onCleared()
        searchSession.close()
    }
}
