package io.legado.app.eink.feature.changesource

import android.app.Application
import io.legado.app.eink.contract.BookHandle
import io.legado.app.eink.contract.ChangeSourceBookUiModel
import io.legado.app.eink.contract.ChangeSourceEngine
import io.legado.app.eink.contract.ChangeSourceResultUiModel
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.contract.GlobalSettings
import io.legado.app.eink.contract.SearchResultHandle
import io.legado.app.eink.contract.SourceHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Field
import java.lang.reflect.Proxy

/**
 * 换源 VM 编排回归：搜索进行中选中某个结果应用换源，搜索必须
 * 立即中止（否则换源迁移与并发搜索抢网络，且选中即返回上一级，
 * 落选源的搜索结果无处展示）。
 *
 * VM 的 load/startSearch/changeTo 硬编码 Dispatchers.IO（真实线程），
 * 与 runTest 虚拟时钟混排会互抢：runTest 会在等待体挂起时激进推进
 * 虚拟时间，内部 withTimeout 提前触发。故本类用 runBlocking + 真实
 * 时间等待，仅 Dispatchers.setMain(Unconfined) 满足 viewModelScope
 * 的 Main 依赖（changeTo 成功回调经 withContext(Main) 内联执行）。
 *
 * ChangeSourceViewModel 的端口经 EInkEngineRegistry（进程级 service
 * locator，无卸载 API）取用；本类用反射临时替换注册表的换源端口与
 * 设置端口字段并在 finally 还原 —— 不走 install()，避免污染
 * EInkEngineRegistryTest 依赖的「未注册」初始态（测试类执行顺序
 * 不可依赖）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChangeSourceViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `搜索进行中选中结果换源即中止搜索并完成换源`() = runBlocking {
        val engine = FakeChangeSourceEngine()
        withRegistryPatched(engine) {
            val viewModel = ChangeSourceViewModel(Application())
            val onChangedCalled = CompletableDeferred<Unit>()

            viewModel.load("url-old")
            withTimeout(10_000) { viewModel.uiState.first { it.isSearching } }
            // 搜索协程已进入引擎单源调用并挂起，保证"搜索进行中"前提成立
            withTimeout(10_000) { engine.searchStarted.await() }

            viewModel.changeTo(pickedResult) { onChangedCalled.complete(Unit) }

            // 选中即停：单源搜索协程被取消（若不取消，此处超时失败）
            withTimeout(10_000) { engine.searchCancelled.await() }
            withTimeout(10_000) { onChangedCalled.await() }

            assertSame(pickedResult, engine.changeCalledWith)
            assertFalse(viewModel.uiState.value.isSearching)
        }
    }

    @Test
    fun `缓存命中时进入即展示历史结果且不发起搜索`() = runBlocking {
        val cachedResult = cachedResultOf(bookUrl = "url-cached", origin = "origin-cached")
        // 当前书源自己的历史记录不进结果列表——它由常驻首项「当前源」行承担
        val currentSourceRecord = cachedResultOf(bookUrl = "url-old", origin = "origin-old")
        val engine = FakeChangeSourceEngine(
            cached = listOf(currentSourceRecord, cachedResult),
        )
        withRegistryPatched(engine) {
            val viewModel = ChangeSourceViewModel(Application())

            viewModel.load("url-old")
            withTimeout(10_000) { viewModel.uiState.first { it.results.isNotEmpty() } }

            val state = viewModel.uiState.value
            assertFalse(state.isSearching)
            assertEquals(listOf(cachedResult), state.results)
            // 常驻首项的当前源数据来自进入时的书籍快照，与缓存无关
            assertEquals("origin-old", state.current?.origin)
            assertEquals("旧书源", state.current?.originName)
            // 缓存命中路径不得发起任何网络搜索
            assertTrue(engine.searchCalls.isEmpty())
        }
    }

    @Test
    fun `缓存为空时进入即发起全新搜索`() = runBlocking {
        val engine = FakeChangeSourceEngine(cached = emptyList())
        withRegistryPatched(engine) {
            val viewModel = ChangeSourceViewModel(Application())

            viewModel.load("url-old")
            withTimeout(10_000) { viewModel.uiState.first { it.isSearching } }
            withTimeout(10_000) { engine.searchStarted.await() }

            assertTrue(engine.searchCalls.isNotEmpty())
        }
    }

    @Test
    fun `搜索结果中当前源不进列表且当前源行常驻`() = runBlocking {
        // 当前源的搜索命中（含同源不同 bookUrl 的别版）不进结果列表，
        // 避免出现两行「当前源」；其它源结果正常追加
        val sameOriginOtherEdition = cachedResultOf(bookUrl = "url-old-2nd", origin = "origin-old")
        val otherOrigin = cachedResultOf(bookUrl = "url-new", origin = "origin-new")
        val engine = FakeChangeSourceEngine(
            cached = emptyList(),
            searchResults = listOf(sameOriginOtherEdition, otherOrigin),
            suspendInSearch = false,
        )
        withRegistryPatched(engine) {
            val viewModel = ChangeSourceViewModel(Application())

            viewModel.load("url-old")
            withTimeout(10_000) {
                viewModel.uiState.first { !it.isSearching && it.results.isNotEmpty() }
            }

            val state = viewModel.uiState.value
            assertEquals(listOf(otherOrigin), state.results)
            assertEquals("origin-old", state.current?.origin)
            assertFalse(state.isSearching)
        }
    }

    private fun cachedResultOf(bookUrl: String, origin: String) = ChangeSourceResultUiModel(
        handle = object : SearchResultHandle {},
        bookUrl = bookUrl,
        name = "书名",
        author = "作者",
        origin = origin,
        originName = "书源-$origin",
        latestChapter = null,
        deduplicationKey = "$origin|$bookUrl",
    )

    private val pickedResult = ChangeSourceResultUiModel(
        handle = object : SearchResultHandle {},
        bookUrl = "url-new",
        name = "书名",
        author = "作者",
        origin = "origin-new",
        originName = "新书源",
        latestChapter = null,
        deduplicationKey = "origin-new|url-new",
    )

    /** 反射替换注册表私有字段（仅换源端口 + 设置端口），用毕还原。 */
    private suspend fun withRegistryPatched(
        engine: ChangeSourceEngine,
        block: suspend () -> Unit,
    ) {
        val engineField = registryField("_changeSourceEngine")
        val settingsField = registryField("_globalSettings")
        val oldEngine = engineField.get(EInkEngineRegistry)
        val oldSettings = settingsField.get(EInkEngineRegistry)
        engineField.set(EInkEngineRegistry, engine)
        settingsField.set(EInkEngineRegistry, stubSettings())
        try {
            block()
        } finally {
            engineField.set(EInkEngineRegistry, oldEngine)
            settingsField.set(EInkEngineRegistry, oldSettings)
        }
    }

    private fun registryField(name: String): Field =
        EInkEngineRegistry::class.java.getDeclaredField(name).apply { isAccessible = true }

    /** JDK 代理桩：只需 threadCount >= 1（并发信号量），其余返回默认值。 */
    private fun stubSettings(): GlobalSettings = Proxy.newProxyInstance(
        GlobalSettings::class.java.classLoader,
        arrayOf(GlobalSettings::class.java),
    ) { _, method, _ ->
        when (method.name) {
            // Kotlin 属性 getter 的 JVM 名为 getThreadCount
            "getThreadCount", "threadCount" -> 2
            else -> when (method.returnType) {
                Boolean::class.java -> false
                Int::class.java -> 0
                Long::class.java -> 0L
                else -> null
            }
        }
    } as GlobalSettings

    /**
     * 换源端口假实现：单源搜索默认进入后挂起（awaitCancellation）模拟
     * 进行中的网络请求，取消时落标记；[suspendInSearch] 为 false 时直接
     * 返回 [searchResults]（跑完即止，验证搜索完成态）。changeBookSource
     * 记录入参并返回新句柄（成功路径）；cachedSourceBooks 返回构造时
     * 给定的历史缓存（默认空）。
     */
    private class FakeChangeSourceEngine(
        private val cached: List<ChangeSourceResultUiModel> = emptyList(),
        private val searchResults: List<ChangeSourceResultUiModel> = emptyList(),
        private val suspendInSearch: Boolean = true,
    ) : ChangeSourceEngine {

        val searchStarted = CompletableDeferred<Unit>()
        val searchCancelled = CompletableDeferred<Unit>()
        val searchCalls = mutableListOf<SourceHandle>()
        var changeCalledWith: ChangeSourceResultUiModel? = null

        override val bookChanged = MutableSharedFlow<String>()

        override suspend fun currentReadingBook(
            bookUrl: String,
        ): Pair<BookHandle, ChangeSourceBookUiModel> = Pair(
            OldBookHandle,
            ChangeSourceBookUiModel(
                bookUrl = "url-old",
                name = "书名",
                author = "作者",
                origin = "origin-old",
                originName = "旧书源",
            ),
        )

        override suspend fun cachedSourceBooks(
            name: String,
            author: String,
            checkAuthor: Boolean,
        ): List<ChangeSourceResultUiModel> = cached

        override fun enabledSources(): List<SourceHandle> =
            listOf(SourceHandleStub("source-1"), SourceHandleStub("source-2"))

        override suspend fun searchSourceBook(
            source: SourceHandle,
            name: String,
            author: String,
            checkAuthor: Boolean,
        ): List<ChangeSourceResultUiModel> {
            searchCalls += source
            searchStarted.complete(Unit)
            if (!suspendInSearch) return searchResults
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                searchCancelled.complete(Unit)
                throw e
            }
        }

        override suspend fun changeBookSource(
            bookHandle: BookHandle,
            result: ChangeSourceResultUiModel,
        ): Result<BookHandle> {
            changeCalledWith = result
            return Result.success(NewBookHandle)
        }

        object OldBookHandle : BookHandle
        object NewBookHandle : BookHandle
        private data class SourceHandleStub(override val url: String) : SourceHandle
    }
}
