package io.legado.app.eink.bridge

import io.legado.app.data.appDb
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.eink.engine.SearchEngine
import io.legado.app.eink.engine.SearchSession
import io.legado.app.eink.engine.SearchSessionCallback
import io.legado.app.eink.search.SearchBookUiModel
import io.legado.app.eink.search.SearchHistoryUiModel
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.config.AppConfig
import io.legado.app.model.webBook.SearchModel
import io.legado.app.ui.book.search.SearchScope
import splitties.init.appCtx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 搜索端口实现：桥接宿主 [SearchModel]（回调式）与搜索历史 DAO。
 *
 * 上游差异吸收点：legadoM-Ink 的 SearchModel.CallBack 多
 * onSearchProgress/onSourceStatesChanged 两个方法 —— 移植时仅需在本
 * 适配器内补两个空 override，模块侧无感。
 */
internal object SearchEngineImpl : SearchEngine {

    private fun SearchBook.toUiModel() = SearchBookUiModel(
        bookUrl = bookUrl,
        name = name,
        author = author,
        coverUrl = coverUrl,
        intro = trimIntro(appCtx),
        latestChapterTitle = latestChapterTitle,
        origin = origin,
        originName = originName,
    )

    override fun observeBookshelfKeys(): Flow<Set<String>> =
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

    override suspend fun recordSearchKey(key: String) {
        appDb.searchKeywordDao.get(key)?.let {
            it.usage += 1
            it.lastUseTime = System.currentTimeMillis()
            appDb.searchKeywordDao.update(it)
        } ?: appDb.searchKeywordDao.insert(SearchKeyword(key, 1))
    }

    override suspend fun clearSearchHistory() {
        appDb.searchKeywordDao.deleteAll()
    }

    override fun createSearchSession(callback: SearchSessionCallback): SearchSession =
        SearchSessionImpl(callback)

    /**
     * 搜索会话：独立作用域（close 即取消，等价原先的 viewModelScope
     * 随 onCleared 取消），回调映射为模块 UiModel。
     */
    private class SearchSessionImpl(
        private val callback: SearchSessionCallback,
    ) : SearchSession {

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        private val searchModel = SearchModel(scope, object : SearchModel.CallBack {

            override fun getSearchScope(): SearchScope = SearchScope(AppConfig.searchScope)

            override fun onSearchStart() {
                callback.onSearchStart()
            }

            override fun onSearchSuccess(searchBooks: List<SearchBook>) {
                callback.onSearchSuccess(searchBooks.map { it.toUiModel() })
            }

            override fun onSearchFinish(isEmpty: Boolean, hasMore: Boolean) {
                callback.onSearchFinish(isEmpty, hasMore)
            }

            override fun onSearchCancel(exception: Throwable?) {
                callback.onSearchCancel(exception)
            }
        })

        override fun search(searchId: Long, key: String) {
            searchModel.search(searchId, key)
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
