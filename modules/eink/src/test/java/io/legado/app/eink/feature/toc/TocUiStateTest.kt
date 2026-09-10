package io.legado.app.eink.feature.toc

import io.legado.app.eink.contract.ChapterUiModel
import io.legado.app.eink.contract.JumpResolution
import io.legado.app.eink.contract.PendingJumpConfirm
import io.legado.app.eink.contract.TocBookUiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目录 UiState 派生逻辑：标题过滤大小写不敏感、空态只在加载完成后
 * 判定、当前章节下标缺书回落 0。
 */
class TocUiStateTest {

    private fun chapter(index: Int, title: String) = ChapterUiModel(
        index = index,
        title = title,
        url = "https://example.com/$index",
        isVolume = false,
        fileName = "$index.txt",
    )

    @Test
    fun `无搜索词时展示全部章节`() {
        val chapters = listOf(chapter(0, "第一卷 风起"), chapter(1, "第二章"))
        val state = TocUiState(chapters = chapters)
        assertEquals(chapters, state.displayChapters)
    }

    @Test
    fun `搜索词按标题大小写不敏感过滤`() {
        val chapters = listOf(
            chapter(0, "Chapter 1 Origin"),
            chapter(1, "第二章 起源"),
            chapter(2, "第三章"),
        )
        val byEnglish = TocUiState(chapters = chapters, searchKey = "origin")
        assertEquals(listOf(chapters[0]), byEnglish.displayChapters)
        val byChinese = TocUiState(chapters = chapters, searchKey = "起源")
        assertEquals(listOf(chapters[1]), byChinese.displayChapters)
    }

    @Test
    fun `空态只在非加载中且过滤后无章节时成立`() {
        val loading = TocUiState(isLoading = true)
        assertFalse(loading.isEmpty)
        val loadedEmpty = TocUiState(isLoading = false)
        assertTrue(loadedEmpty.isEmpty)
        val loaded = TocUiState(chapters = listOf(chapter(0, "第一章")), isLoading = false)
        assertFalse(loaded.isEmpty)
        val filteredOut = TocUiState(
            chapters = listOf(chapter(0, "第一章")),
            isLoading = false,
            searchKey = "不存在",
        )
        assertTrue("过滤后无命中视为空态", filteredOut.isEmpty)
    }

    @Test
    fun `当前章节下标缺书回落 0`() {
        assertEquals(0, TocUiState().currentChapterIndex)
        val state = TocUiState(
            book = TocBookUiModel(bookUrl = "u", name = "书", currentChapterIndex = 7, isLocal = false),
        )
        assertEquals(7, state.currentChapterIndex)
    }

    @Test
    fun `书签状态默认隐藏不可用且无确认`() {
        val s = TocUiState()
        assertEquals(TocTab.Chapters, s.selectedTab)
        assertTrue(s.bookmarks.isEmpty())
        assertFalse(s.marksAvailable)
        assertNull(s.pendingJump)
    }

    @Test
    fun `确认弹层状态可置入`() {
        val confirm = PendingJumpConfirm("msg", JumpResolution.Located(1, 2))
        val s = TocUiState(pendingJump = confirm)
        assertEquals(confirm, s.pendingJump)
    }
}
