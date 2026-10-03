package io.legado.app.eink.feature.toc

import android.app.Application
import io.legado.app.eink.contract.ChapterUiModel
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.contract.JumpResolution
import io.legado.app.eink.contract.TocBookUiModel
import io.legado.app.eink.contract.TocEngine
import io.legado.app.eink.contract.TocFetchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Field

/**
 * 目录章节点按的跳转分发回归：点按必须经 jumpTarget 与书签/笔记同一条
 * 链路发出目标（Located/章首），而非静默落库——原实现的静默失败点
 * （book 未就绪 / 下标越界）无任何用户反馈，且落库与重挂载重读进度的
 * 竞态会表现为「点目录跳不过去」（见 TocViewModel.onChapterClick KDoc）。
 *
 * 测试风格与 ChangeSourceViewModelTest 一致：VM 硬编码 Dispatchers.IO
 * （真实线程），runBlocking + 真实时间等待，仅 setMain(Unconfined) 满足
 * viewModelScope；端口经反射替换注册表字段并在 finally 还原。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TocViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `章节点按发出跳转目标并乐观跟进当前章`() = runBlocking {
        withTocEnginePatched(FakeTocEngine()) {
            val viewModel = TocViewModel(Application())
            viewModel.loadBook("book-1")
            withTimeout(10_000) {
                viewModel.uiState.first { it.book != null && it.chapters.isNotEmpty() }
            }

            val jumps = mutableListOf<JumpResolution.Located>()
            val collector = launch(Dispatchers.Unconfined) {
                viewModel.jumpTarget.collect { jumps += it }
            }
            try {
                viewModel.onChapterClick(2)

                // Unconfined 收集器在 tryEmit 时同步收件，点按返回即可断言
                assertEquals(JumpResolution.Located(chapterIndex = 2, chapterPos = 0), jumps.single())
                assertEquals(2, viewModel.uiState.value.currentChapterIndex)
            } finally {
                collector.cancel()
            }
        }
    }

    @Test
    fun `目录未就绪点按提示且不发跳转目标`() = runBlocking {
        withTocEnginePatched(FakeTocEngine()) {
            val viewModel = TocViewModel(Application())

            val jumps = mutableListOf<JumpResolution.Located>()
            val messages = mutableListOf<String>()
            val jumpCollector = launch(Dispatchers.Unconfined) {
                viewModel.jumpTarget.collect { jumps += it }
            }
            val messageCollector = launch(Dispatchers.Unconfined) {
                viewModel.messages.collect { messages += it }
            }
            try {
                viewModel.onChapterClick(0)

                // Unconfined 收集器在 tryEmit 时同步收件，点按返回即可断言
                assertEquals(1, messages.size)
                assertTrue(messages.single().contains("目录"))
                delay(200)
                assertTrue(jumps.isEmpty())
            } finally {
                jumpCollector.cancel()
                messageCollector.cancel()
            }
        }
    }

    @Test
    fun `章节下标越界点按提示且不发跳转目标`() = runBlocking {
        withTocEnginePatched(FakeTocEngine()) {
            val viewModel = TocViewModel(Application())
            viewModel.loadBook("book-1")
            withTimeout(10_000) {
                viewModel.uiState.first { it.book != null && it.chapters.isNotEmpty() }
            }

            val jumps = mutableListOf<JumpResolution.Located>()
            val messages = mutableListOf<String>()
            val jumpCollector = launch(Dispatchers.Unconfined) {
                viewModel.jumpTarget.collect { jumps += it }
            }
            val messageCollector = launch(Dispatchers.Unconfined) {
                viewModel.messages.collect { messages += it }
            }
            try {
                viewModel.onChapterClick(99)

                // Unconfined 收集器在 tryEmit 时同步收件，点按返回即可断言
                assertEquals(1, messages.size)
                assertTrue(messages.single().contains("章节"))
                delay(200)
                assertTrue(jumps.isEmpty())
            } finally {
                jumpCollector.cancel()
                messageCollector.cancel()
            }
        }
    }

    /** 反射替换注册表目录端口（marks 显式置空防他测残留），用毕还原。 */
    private suspend fun withTocEnginePatched(engine: TocEngine, block: suspend () -> Unit) {
        val tocField = registryField("_tocEngine")
        val marksField = registryField("_marksEngine")
        val oldToc = tocField.get(EInkEngineRegistry)
        val oldMarks = marksField.get(EInkEngineRegistry)
        tocField.set(EInkEngineRegistry, engine)
        marksField.set(EInkEngineRegistry, null)
        try {
            block()
        } finally {
            tocField.set(EInkEngineRegistry, oldToc)
            marksField.set(EInkEngineRegistry, oldMarks)
        }
    }

    private fun registryField(name: String): Field =
        EInkEngineRegistry::class.java.getDeclaredField(name).apply { isAccessible = true }

    /** 目录端口假实现：单书三章直读，无联网、无缓存标记。 */
    private class FakeTocEngine : TocEngine {

        override suspend fun resolveBook(bookUrl: String): TocBookUiModel =
            TocBookUiModel(
                bookUrl = bookUrl,
                name = "书名",
                currentChapterIndex = 0,
                isLocal = false,
            )

        override suspend fun loadChapters(bookUrl: String): List<ChapterUiModel> =
            listOf(
                chapter(0, "第一章"),
                chapter(1, "第二章"),
                chapter(2, "第三章"),
            )

        override suspend fun fetchChaptersFromSource(bookUrl: String): TocFetchResult =
            TocFetchResult.Failure(UnsupportedOperationException("测试桩不联网"))

        override suspend fun cachedChapterFileNames(bookUrl: String): Set<String> = emptySet()

        override suspend fun saveReadingProgress(
            bookUrl: String,
            chapterIndex: Int,
            chapterTitle: String,
            chapterPos: Int,
        ) = Unit

        private fun chapter(index: Int, title: String) = ChapterUiModel(
            index = index,
            title = title,
            url = "chapter-$index",
            isVolume = false,
            fileName = "file-$index",
        )
    }
}
