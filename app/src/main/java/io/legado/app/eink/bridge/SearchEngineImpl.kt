package io.legado.app.eink.bridge

import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.data.local.preferences.LocalPreferencesKeys
import io.legado.app.data.local.preferences.localDataStore
import io.legado.app.domain.gateway.BookSearchGateway
import io.legado.app.domain.gateway.DownloadCacheSettingsGateway
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
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
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
internal object SearchEngineImpl : SearchEngine, KoinComponent {

    private val downloadCacheSettingsGateway: DownloadCacheSettingsGateway by inject()
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

    // 最近使用在前：与 View 搜索页空输入口径一致（flowByUsage 纯次数排序
    // 无时间维度，并列项顺序不定，旧高频词永远压过新词）
    override fun observeSearchHistory(): Flow<List<SearchHistoryUiModel>> =
        appDb.searchKeywordDao.flowByTime().map { history ->
            history.map { SearchHistoryUiModel(word = it.word) }
        }

    override suspend fun recordSearchKey(key: String) {
        appDb.searchKeywordDao.get(key)?.let {
            it.usage += 1
            it.lastUseTime = System.currentTimeMillis()
            appDb.searchKeywordDao.update(it)
        } ?: appDb.searchKeywordDao.insert(SearchKeyword(key, 1))
    }

    override suspend fun removeSearchHistory(word: String) {
        appDb.searchKeywordDao.get(word)?.let { keyword ->
            appDb.searchKeywordDao.delete(keyword)
        }
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
                    // 范围与匹配模式均沿用主搜索页的持久化 local_ui_status 键，
                    // 保证同关键词下两侧结果集一致（MATCH_MODE 非 DEFAULT 时
                    // UseCase 会在源头过滤）
                    val scopeRaw = persistedSearchScope()
                    val matchMode = MatchMode.of(persistedMatchModeValue())
                    searchUseCase
                        .execute(
                            BookSearchRequest(
                                keyword = key,
                                page = 1,
                                scope = BookSearchScope(scopeRaw),
                                matchMode = matchMode,
                                concurrency = downloadCacheSettingsGateway.currentSettings.threadCount,
                                types = null,
                            ),
                            BookSearchControl(),
                        )
                        .collect { event ->
                            when (event) {
                                SearchRunEvent.Started -> callback.onSearchStart()
                                is SearchRunEvent.Progress -> {
                                    callback.onSearchProgress(
                                        event.processedSources,
                                        event.totalSources
                                    )
                                    if (event.upsertBooks.isNotEmpty()) {
                                        callback.onSearchSuccess(
                                            event.upsertBooks.map { it.toUiModel() }
                                        )
                                    }
                                    // removedBookUrls（同书更好源替换）E-Ink 回调面
                                    // 无对应投影，忽略 —— VM 按 origin-bookUrl
                                    // 累积合并去重，同书重复增量自然覆盖
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

        private suspend fun persistedMatchModeValue(): Int =
            withContext(Dispatchers.IO) {
                runCatching {
                    appCtx.localDataStore.data.first()[LocalPreferencesKeys.MATCH_MODE]
                }.getOrNull() ?: MatchMode.DEFAULT.value
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
