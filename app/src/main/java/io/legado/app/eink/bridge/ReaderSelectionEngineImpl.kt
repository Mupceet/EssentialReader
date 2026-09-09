package io.legado.app.eink.bridge

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.repository.BookmarkRepository
import io.legado.app.domain.model.TextProcessStyle
import io.legado.app.domain.usecase.SaveMarkingUseCase
import io.legado.app.eink.contract.ReaderSelectionCommit
import io.legado.app.eink.contract.ReaderSelectionDraft
import io.legado.app.eink.contract.ReaderSelectionEngine
import io.legado.app.model.ReadBook
import kotlin.coroutines.cancellation.CancellationException
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** 与 MarkingDelegate 一致的窗口常量。 */
private const val CONTEXT_CHARS = 48
private const val CONTEXT_SEARCH_WINDOW = 256

/** eink 笔记固定色：宿主划线渲染的回退默认（灰绿），eink 页面按主题黑绘制。 */
private val EINK_MARKING_COLOR: Int = 0xFF63C37D.toInt()

/**
 * eink 创建的笔记固定实线样式（无样式配置——既定产品决策）；颜色为宿主渲染
 * 回退默认，eink 页面按主题黑绘制。
 */
internal fun einkMarkingStyle(): TextProcessStyle =
    TextProcessStyle(underlineMode = 1, underlineColor = EINK_MARKING_COLOR)

/**
 * 在章节全文中定位选中文本：窗口口径基于 MarkingDelegate.selectionContext；
 * 按契约改为两级搜索（先提示位精确后窗口回搜），找不到返回 -1
 * （不发散到 expectedStart）。
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
    private val saveMarkingUseCase: SaveMarkingUseCase by inject()

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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.put("eink saveBookmark failed: ${e.message}", e)
            false
        }
    }

    override suspend fun saveMarking(commit: ReaderSelectionCommit): Boolean {
        val book = ReadBook.book ?: return false
        val content = semanticContent(commit.chapterIndex) ?: return false
        val located = locateInContent(content, commit.start, commit.selectedText)
        // 选区失效从严：笔记是文本锚点，与书签的宽松回退策略不同
        if (located < 0) return false
        val (before, after) = extractContext(content, located, commit.selectedText.length)
        return try {
            saveMarkingUseCase.save(
                bookName = book.name,
                bookAuthor = book.author,
                bookUrl = book.bookUrl,
                chapterIndex = commit.chapterIndex,
                chapterPosition = located,
                selectedText = commit.selectedText,
                style = einkMarkingStyle(),
                chapterName = displayTitle(),
                note = commit.note,
                contextBefore = before,
                contextAfter = after,
            )
            // 新快照经 onContentUpdated 推送（保持页内位置），模块随重绘清选区
            ReaderEngineImpl.relayout()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // save 成功但 relayout 抛异常时误报失败——标记已落库（锚点 upsert 幂等，重试安全），将在下次成功重排时出现
            AppLog.put("eink saveMarking failed: ${e.message}", e)
            false
        }
    }
}
