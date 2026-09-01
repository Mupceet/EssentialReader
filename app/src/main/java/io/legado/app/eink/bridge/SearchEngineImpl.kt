package io.legado.app.eink.bridge

import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.data.local.preferences.LocalPreferencesKeys
import io.legado.app.data.local.preferences.localDataStore
import io.legado.app.domain.gateway.BookSearchGateway
import io.legado.app.domain.model.BookSearchScope
import io.legado.app.domain.model.MatchMode
import io.legado.app.domain.usecase.BookSearchRequest
import io.legado.app.domain.usecase.BookSearchControl
import io.legado.app.domain.usecase.SearchBooksUseCase
import io.legado.app.domain.usecase.SearchRunEvent
import io.legado.app.eink.contract.SearchEngine
import io.legado.app.eink.contract.SearchSession
import io.legado.app.eink.contract.SearchSessionCallback
import io.legado.app.eink.contract.SearchBookUiModel
import io.legado.app.eink.contract.SearchHistoryUiModel
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import kotlin.coroutines.cancellation.CancellationException

/**
 * 搜索端口实现。
 *
 * 本上游 SearchModel 已删除，多源并发搜索重构为 SearchBooksUseCase
 * （Flow<SearchRunEvent>）—— 这里以最小 BookSearchGateway 实现直接
 * 驱动 UseCase，事件映射为模块回调；搜索范围沿用 View 搜索页持久化的
 * local_ui_status "search_scope" 键。
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
     * 搜索会话：独立作用域（close 即取消），每次 [SearchSession.search]
     * 起一轮新的全源第 1 页搜索（与 E-Ink 搜索页交互一致）。
     */
    private class SearchSessionImpl(
        private val callback: SearchSessionCallback,
    ) : SearchSession {

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val searchUseCase = SearchBooksUseCase(AppDbSearchGateway)
        private var searchJob: Job? = null

        override fun search(searchId: Long, key: String) {
            cancelSearch()
            searchJob = scope.launch {
                try {
                    val scopeRaw = persistedSearchScope()
                    searchUseCase
                        .execute(
                            BookSearchRequest(
                                keyword = key,
                                page = 1,
                                scope = BookSearchScope(scopeRaw),
                                matchMode = MatchMode.DEFAULT,
                                concurrency = AppConfig.threadCount,
                                types = null,
                            ),
                            BookSearchControl(),
                        )
                        .collect { event ->
                            when (event) {
                                SearchRunEvent.Started -> callback.onSearchStart()
                                is SearchRunEvent.Progress -> {
                                    if (event.upsertBooks.isNotEmpty()) {
                                        callback.onSearchSuccess(
                                            event.upsertBooks.map { it.toUiModel() }
                                        )
                                    }
                                    // removedBookUrls（同书更好源替换）E-Ink 回调面
                                    // 无对应投影，忽略 —— VM 按 bookUrl|origin 去重，
                                    // 旧条目自然被新源结果覆盖展示
                                }
                                is SearchRunEvent.Finished ->
                                    callback.onSearchFinish(event.isEmpty, event.hasMore)
                            }
                        }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    callback.onSearchCancel(e)
                }
            }
        }

        override fun cancelSearch() {
            searchJob?.cancel()
            searchJob = null
        }

        override fun close() {
            cancelSearch()
            scope.cancel()
        }

        private suspend fun persistedSearchScope(): String =
            withContext(Dispatchers.IO) {
                runCatching {
                    appCtx.localDataStore.data.first()[LocalPreferencesKeys.SEARCH_SCOPE]
                }.getOrNull() ?: ""
            }
    }
}

/** BookSearchGateway 的 DAO 直连最小实现（与 SearchRepositoryImpl 同语义）。 */
private object AppDbSearchGateway : BookSearchGateway {

    override suspend fun getBookSourceParts(
        scope: BookSearchScope,
    ): List<BookSourcePart> = withContext(Dispatchers.IO) {
        val selected = linkedSetOf<BookSourcePart>()
        when {
            scope.isAll -> selected.addAll(appDb.bookSourceDao.allEnabledPart)
            scope.isSource -> scope.sourceUrls.forEach { sourceUrl ->
                appDb.bookSourceDao.getBookSourcePart(sourceUrl)?.let { selected.add(it) }
            }
            else -> scope.groupNames.forEach { groupName ->
                selected.addAll(appDb.bookSourceDao.getEnabledPartByGroup(groupName))
            }
        }
        if (selected.isEmpty()) {
            appDb.bookSourceDao.allEnabledPart
        } else {
            selected.toList().sortedBy { it.customOrder }
        }
    }

    override suspend fun getBookSource(sourceUrl: String): io.legado.app.data.entities.BookSource? =
        withContext(Dispatchers.IO) {
            appDb.bookSourceDao.getBookSource(sourceUrl)
        }

    override suspend fun saveSearchBooks(books: List<SearchBook>) {
        if (books.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                appDb.searchBookDao.insert(books)
            }
        }
    }
}
