package io.legado.app.eink.contract

import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * observeChapters 默认实现语义锚定：单发 loadChapters 的 flow——旧宿主
 * 零改动时「首值后不再有更新」，目录页表现为进入时快照（与流式宿主的
 * 无更新期行为一致）。
 */
class TocEngineObserveChaptersTest {

    private fun chapter(index: Int) = ChapterUiModel(
        index = index, title = "第${index + 1}章",
        url = "u$index", isVolume = false, fileName = "$index.txt",
    )

    @Test
    fun `默认实现单发loadChapters快照且不再更新`() = runTest {
        val snapshot = listOf(chapter(0), chapter(1))
        var loadCalls = 0
        val engine = object : TocEngine {
            override suspend fun resolveBook(bookUrl: String): TocBookUiModel? = null
            override suspend fun loadChapters(bookUrl: String): List<ChapterUiModel> {
                loadCalls++
                return snapshot
            }
            override suspend fun fetchChaptersFromSource(bookUrl: String) =
                TocFetchResult.NoSource
            override suspend fun cachedChapterFileNames(bookUrl: String): Set<String> = emptySet()
            override suspend fun saveReadingProgress(
                bookUrl: String,
                chapterIndex: Int,
                chapterTitle: String,
                chapterPos: Int,
            ) = Unit
        }

        val emitted = engine.observeChapters("book-a").toList()

        assertEquals("单发一次（loadChapters 查一次）", 1, loadCalls)
        assertEquals("唯一值 = loadChapters 快照", snapshot, emitted.single())
    }

    @Test
    fun `空目录同样单发空列表`() = runTest {
        val engine = object : TocEngine {
            override suspend fun resolveBook(bookUrl: String): TocBookUiModel? = null
            override suspend fun loadChapters(bookUrl: String): List<ChapterUiModel> =
                emptyList()
            override suspend fun fetchChaptersFromSource(bookUrl: String) =
                TocFetchResult.NoSource
            override suspend fun cachedChapterFileNames(bookUrl: String): Set<String> = emptySet()
            override suspend fun saveReadingProgress(
                bookUrl: String,
                chapterIndex: Int,
                chapterTitle: String,
                chapterPos: Int,
            ) = Unit
        }

        assertEquals(emptyList<ChapterUiModel>(), engine.observeChapters("book-a").single())
    }
}
