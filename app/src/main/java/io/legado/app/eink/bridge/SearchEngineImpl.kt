package io.legado.app.eink.bridge

import io.legado.app.data.appDb
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.eink.contract.SearchBookUiModel
import io.legado.app.eink.contract.SearchEngine
import io.legado.app.eink.contract.SearchHistoryUiModel
import io.legado.app.eink.contract.SearchSession
import io.legado.app.eink.contract.SearchSessionCallback
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.config.AppConfig
import io.legado.app.model.webBook.SearchModel
import io.legado.app.ui.book.search.SearchScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import splitties.init.appCtx

/**
 * 搜索端口实现：桥接宿主 [SearchModel]（回调式）与搜索关键词 DAO。
 *
 * 本宿主 CallBack 无源粒度进展事件——onSearchProgress 以「已返回结果的
 * 源数 / scope 参与源总数」近似（无结果源不计入，进度偏低后在 finish
 * 跳满；此前版本直接不调属错误取舍，进度提示停留初始态）。
 */
internal object SearchEngineImpl : SearchEngine {

    private fun SearchBook.toUiModel() = SearchBookUiModel(
        bookUrl = bookUrl,
        name = name,
        author = author,
        kind = kind,
        originsCount = origins.size,
        coverUrl = coverUrl,
        intro = trimIntro(appCtx),
        latestChapterTitle = latestChapterTitle,
        origin = origin,
        originName = originName,
    )

    override fun observeBookshelfMatchKeys(): Flow<Set<String>> =
        appDb.bookDao.flowAll().map { books ->
            buildSet {
                books.filterNot { it.isNotShelf }.forEach {
                    add("${it.name}-${it.author}")
                    add(it.name)
                    add(it.bookUrl)
                }
            }
        }.distinctUntilChanged()

    override fun observeSearchHistory(): Flow<List<SearchHistoryUiModel>> =
        appDb.searchKeywordDao.flowByUsage().map { history ->
            history.map { SearchHistoryUiModel(word = it.word) }
        }

    override suspend fun recordSearchQuery(query: String) {
        appDb.searchKeywordDao.get(query)?.let {
            it.usage += 1
            it.lastUseTime = System.currentTimeMillis()
            appDb.searchKeywordDao.update(it)
        } ?: appDb.searchKeywordDao.insert(SearchKeyword(query, 1))
    }

    override suspend fun removeSearchHistory(word: String) {
        // 本宿主 DAO 无按词删除：先查再删
        appDb.searchKeywordDao.get(word)?.let { appDb.searchKeywordDao.delete(it) }
    }

    override suspend fun clearSearchHistory() {
        appDb.searchKeywordDao.deleteAll()
    }

    override fun createSearchSession(callback: SearchSessionCallback): SearchSession =
        SearchSessionImpl(callback)

    /**
     * 搜索会话：独立作用域（close 即取消，等价 viewModelScope 随 onCleared
     * 取消），回调结果映射为模块 UiModel。
     */
    private class SearchSessionImpl(
        private val callback: SearchSessionCallback,
    ) : SearchSession {

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        /** 已返回结果的源（origin 计数）；无结果源不推进，finish 前进度偏低。 */
        private val reportedOrigins = mutableSetOf<String>()

        private val searchScope = SearchScope(AppConfig.searchScope)

        private val totalSources = searchScope.getBookSourceParts().size

        private val searchModel = SearchModel(scope, object : SearchModel.CallBack {

            override fun getSearchScope(): SearchScope = searchScope

            override fun onSearchStart() {
                callback.onSearchStart()
                callback.onSearchProgress(0, totalSources)
            }

            override fun onSearchSuccess(searchBooks: List<SearchBook>) {
                searchBooks.forEach { reportedOrigins.addAll(it.origins) }
                callback.onSearchProgress(reportedOrigins.size, totalSources)
                callback.onSearchSuccess(searchBooks.map { it.toUiModel() })
            }

            override fun onSearchFinish(isEmpty: Boolean, hasMore: Boolean) {
                callback.onSearchFinish(isEmpty, hasMore)
            }

            override fun onSearchCancel(exception: Throwable?) {
                callback.onSearchCancel(exception)
            }
        })

        override fun search(searchId: Long, query: String) {
            searchModel.search(searchId, query)
        }

        override fun cancelSearch() {
            searchModel.cancelSearch()
        }

        override fun close() {
            searchModel.close()
            scope.cancel()
        }
    }
}
