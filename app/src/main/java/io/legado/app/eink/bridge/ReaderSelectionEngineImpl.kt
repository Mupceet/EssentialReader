package io.legado.app.eink.bridge

import io.legado.app.data.entities.Bookmark
import io.legado.app.data.repository.BookmarkRepository
import io.legado.app.eink.contract.ReaderSelectionCommit
import io.legado.app.eink.contract.ReaderSelectionDraft
import io.legado.app.eink.contract.ReaderSelectionEngine
import io.legado.app.model.ReadBook
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** 与 MarkingDelegate 一致的窗口常量。 */
private const val CONTEXT_CHARS = 48
private const val CONTEXT_SEARCH_WINDOW = 256

/**
 * 在章节全文中定位选中文本：优先提示位置精确命中，附近 ±[CONTEXT_SEARCH_WINDOW]
 * 窗口搜索纠偏，找不到返回 -1。算法与 MarkingDelegate.selectionContext 一致。
 */
internal fun locateInContent(content: String, expectedStart: Int, text: String): Int {
    if (text.isEmpty()) return -1
    val clamped = expectedStart.coerceIn(0, content.length)
    content.indexOf(text, clamped).takeIf { it >= 0 && it <= clamped + CONTEXT_SEARCH_WINDOW }
        ?.let { return it }
    val windowStart = (clamped - CONTEXT_SEARCH_WINDOW).coerceAtLeast(0)
    content.indexOf(text, windowStart).takeIf { it >= 0 && it <= clamped + CONTEXT_SEARCH_WINDOW }
        ?.let { return it }
    return -1
}

/** 选区前后各取 [CONTEXT_CHARS] 字符（钳制边界）。 */
internal fun extractContext(content: String, start: Int, length: Int): Pair<String, String> {
    val end = (start + length).coerceAtMost(content.length)
    val before = content.substring((start - CONTEXT_CHARS).coerceAtLeast(0), start)
    val after = content.substring(end, (end + CONTEXT_CHARS).coerceAtMost(content.length))
    return before to after
}

/**
 * 选区批注端口实现：把模块提交的正文空间选区解析为宿主语义字段并落库
 * bookmarks 表。定位与失效判定不在 resolveSelection 做——含标题的选区在
 * 正文空间本就找不到文本，属合法输入；saveBookmark/saveMarking 时才以
 * 提示位置为锚做窗口搜索。
 */
internal object ReaderSelectionEngineImpl : ReaderSelectionEngine, KoinComponent {

    private val bookmarkRepository: BookmarkRepository by inject()

    /** 当前会话章节的语义正文（章节不匹配返回 null）。 */
    private fun semanticContent(chapterIndex: Int): String? =
        ReadBook.readerChapterInputWindow.current
            ?.takeIf { it.chapter.index == chapterIndex }
            ?.source?.semanticContent

    /** 当前窗口标题（书签 chapterName，宿主同款口径）。 */
    private fun displayTitle(): String =
        ReadBook.readerChapterInputWindow.current?.displayTitle.orEmpty()

    override suspend fun resolveSelection(
        chapterIndex: Int,
        start: Int,
        end: Int,
        selectedText: String,
    ): ReaderSelectionDraft? {
        // 预填构造不做正文定位：含标题选区在正文空间本就找不到文本，
        // 属合法输入；定位与失效判定在 saveBookmark/saveMarking 时执行
        ReadBook.book ?: return null
        if (selectedText.isBlank()) return null
        return ReaderSelectionDraft(
            selectedText = selectedText,
            bookmarkText = selectedText,
            bookmarkContent = "",
        )
    }

    override suspend fun saveBookmark(commit: ReaderSelectionCommit): Boolean {
        val book = ReadBook.book ?: return false
        val content = semanticContent(commit.chapterIndex)
        val chapterPos = content
            ?.let { locateInContent(it, commit.start, commit.selectedText) }
            ?.takeIf { it >= 0 }
            ?: commit.start
        val bookmark = Bookmark(
            bookName = book.name,
            bookAuthor = book.author,
            bookUrl = book.bookUrl,
            chapterIndex = commit.chapterIndex,
            chapterPos = chapterPos,
            chapterName = displayTitle(),
            bookText = commit.bookmarkText,
            content = commit.bookmarkContent,
        )
        return try {
            bookmarkRepository.save(bookmark)
            true
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun saveMarking(commit: ReaderSelectionCommit): Boolean {
        // Task 9 实现：SaveMarkingUseCase + 固定实线样式 + relayout 推送
        throw NotImplementedError("Task 9")
    }
}
