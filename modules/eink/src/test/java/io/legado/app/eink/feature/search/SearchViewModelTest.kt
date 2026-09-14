package io.legado.app.eink.feature.search

import android.app.Application
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.contract.SearchBookUiModel
import io.legado.app.eink.contract.SearchEngine
import io.legado.app.eink.contract.SearchHistoryUiModel
import io.legado.app.eink.contract.SearchSession
import io.legado.app.eink.contract.SearchSessionCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Field

/**
 * 搜索会话暂停/恢复转发回归：
 * 用户点击搜索结果进详情页时 SearchRoute 离开组合，VM 须把引擎会话挂起
 * （剩余书源不再启动），返回搜索页时恢复——对齐主搜索页离开页面挂起/
 * 回来恢复的门控语义。门控实现在宿主 SearchSessionImpl，VM 只做转发
 * 且不得把「暂停」当成「停止」翻转搜索中状态。
 *
 * VM 的端口经 EInkEngineRegistry（进程级 service locator，无卸载 API）
 * 取用；本类用反射临时替换注册表的搜索端口字段并在 finally 还原——
 * 不走 install()，避免污染 EInkEngineRegistryTest 依赖的「未注册」
 * 初始态（测试类执行顺序不可依赖）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `暂停与恢复转发到会话且不改变搜索中状态`() = runBlocking {
        val engine = FakeSearchEngine()
        withSearchEnginePatched(engine) {
            val viewModel = SearchViewModel(Application())
            val session = engine.session!!

            viewModel.search("关键词")
            withTimeout(10_000) { viewModel.uiState.first { it.isSearching } }
            assertEquals(1, session.searchCount)

            // 暂停不是停止：状态仍为搜索中，等引擎 finish 才落
            viewModel.pauseSearch()
            assertEquals(1, session.pauseCount)
            assertTrue(viewModel.uiState.value.isSearching)

            viewModel.resumeSearch()
            assertEquals(1, session.resumeCount)
            assertTrue(viewModel.uiState.value.isSearching)

            session.finish()
            withTimeout(10_000) { viewModel.uiState.first { !it.isSearching } }
        }
    }

    @Test
    fun `暂停态发起新搜索不被 VM 拒绝`() = runBlocking {
        val engine = FakeSearchEngine()
        withSearchEnginePatched(engine) {
            val viewModel = SearchViewModel(Application())
            val session = engine.session!!

            viewModel.search("第一轮")
            withTimeout(10_000) { viewModel.uiState.first { it.isSearching } }
            viewModel.pauseSearch()

            // 搜索页重新组合后未恢复就发起新搜索：新轮隐含恢复由宿主
            // 会话保证，VM 不做暂停态拦截
            viewModel.search("第二轮")
            withTimeout(10_000) { viewModel.uiState.first { it.results.isNotEmpty() } }
            assertEquals(2, session.searchCount)

            session.finish()
            withTimeout(10_000) { viewModel.uiState.first { !it.isSearching } }
        }
    }

    /** 反射替换注册表搜索端口字段，用毕还原。 */
    private fun withSearchEnginePatched(
        engine: SearchEngine,
        block: suspend () -> Unit,
    ) {
        val field = registryField()
        val old = field.get(EInkEngineRegistry)
        field.set(EInkEngineRegistry, engine)
        try {
            runBlocking { block() }
        } finally {
            field.set(EInkEngineRegistry, old)
        }
    }

    private fun registryField(): Field =
        EInkEngineRegistry::class.java.getDeclaredField("_searchEngine")
            .apply { isAccessible = true }

    private class FakeSearchEngine : SearchEngine {

        var session: FakeSearchSession? = null
            private set

        override fun observeBookshelfMatchKeys(): Flow<Set<String>> =
            MutableStateFlow(emptySet())

        override fun observeSearchHistory(): Flow<List<SearchHistoryUiModel>> =
            MutableStateFlow(emptyList())

        override suspend fun recordSearchQuery(query: String) = Unit

        override suspend fun removeSearchHistory(word: String) = Unit

        override suspend fun clearSearchHistory() = Unit

        override fun createSearchSession(callback: SearchSessionCallback): SearchSession =
            FakeSearchSession(callback).also { session = it }
    }

    /**
     * 会话桩：记录 search/pause/resume 调用次数；search 即回调
     * onSearchStart 并推一条结果，finish 供测试主动收尾。
     */
    private class FakeSearchSession(
        private val callback: SearchSessionCallback,
    ) : SearchSession {

        var searchCount = 0
            private set
        var pauseCount = 0
            private set
        var resumeCount = 0
            private set

        override fun search(searchId: Long, query: String) {
            searchCount++
            callback.onSearchStart()
            callback.onSearchSuccess(
                listOf(
                    SearchBookUiModel(
                        bookUrl = "url-$searchCount",
                        name = "书名$searchCount",
                        author = "作者",
                        coverUrl = null,
                        intro = "",
                        latestChapterTitle = null,
                        origin = "origin",
                        originName = "书源",
                    )
                )
            )
        }

        override fun cancelSearch() = Unit

        override fun pauseSearch() {
            pauseCount++
        }

        override fun resumeSearch() {
            resumeCount++
        }

        override fun close() = Unit

        fun finish() {
            callback.onSearchFinish(isEmpty = false, hasMore = false)
        }
    }
}
