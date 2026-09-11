package io.legado.app.eink.bridge

import android.os.SystemClock
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.repository.BookmarkRepository
import io.legado.app.domain.gateway.BookMarkingGateway
import io.legado.app.domain.model.TextProcessAnchor
import io.legado.app.domain.model.TextProcessStyle
import io.legado.app.domain.usecase.SaveMarkingUseCase
import io.legado.app.eink.contract.ReaderMarkingDetail
import io.legado.app.eink.contract.ReaderSelectionCommit
import io.legado.app.eink.contract.ReaderSelectionEngine
import io.legado.app.model.ReadBook
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** 与 MarkingDelegate 一致的窗口常量。 */
private const val CONTEXT_CHARS = 48
private const val CONTEXT_SEARCH_WINDOW = 256

/** 等待章节语义正文就绪的上限（宿主重排在途时窗口短暂为空）。 */
private const val CONTENT_WAIT_TIMEOUT_MILLIS = 1_500L

/** 等待正文就绪的轮询间隔。 */
private const val CONTENT_WAIT_POLL_MILLIS = 50L

/**
 * eink 笔记固定色：纯黑。eink 页面本就按主题黑绘制，落库色取同值后
 * 完整模式（宿主）里的同一条标记也是黑实线，两模式显示一致——
 * 用户选「画线」得到的即「黑色实线」这一承诺不因查看模式而变。
 */
private val EINK_MARKING_COLOR: Int = 0xFF000000.toInt()

/**
 * eink 笔记固定样式（无样式配置——既定产品决策）：划线实线（underlineMode=1）、
 * 想法虚线（underlineMode=2）；颜色固定纯黑（见 [EINK_MARKING_COLOR]）。
 */
internal fun einkMarkingStyle(thought: Boolean): TextProcessStyle =
    TextProcessStyle(
        underlineMode = if (thought) 2 else 1,
        underlineColor = EINK_MARKING_COLOR,
    )

/**
 * 在章节全文中定位选中文本：窗口口径基于 MarkingDelegate.selectionContext；
 * 按契约两级搜索（先提示位精确后窗口回搜），仍不命中时最后做一次
 * **全文唯一命中**兜底（见 [uniqueOccurrence]），找不到返回 -1
 * （不发散到 expectedStart）。
 */
internal fun locateInContent(content: String, expectedStart: Int, text: String): Int {
    if (text.isEmpty()) return -1
    val clamped = expectedStart.coerceIn(0, content.length)
    content.indexOf(text, clamped).takeIf { it >= 0 && it <= clamped + CONTEXT_SEARCH_WINDOW }
        ?.let { return it }
    // 回搜距离至少覆盖原文全长：点按链以行内位置作提示、selectedText 为标记
    // 完整原文，真实起点可在提示之前超过 CONTEXT_SEARCH_WINDOW 处（长标记跨页）
    val backWindow = maxOf(CONTEXT_SEARCH_WINDOW, text.length)
    val windowStart = (clamped - backWindow).coerceAtLeast(0)
    content.indexOf(text, windowStart).takeIf { it >= 0 && it <= clamped + CONTEXT_SEARCH_WINDOW }
        ?.let { return it }
    // 兜底：提示位漂移超出回搜窗口（宿主重排/内容微调后模块仍持旧页坐标）时，
    // 只要选中文本在全文**唯一**出现就仍是无歧义锚点；0 次或多次命中保持
    // 「选区失效从严」返回 -1，不猜位置
    return uniqueOccurrence(content, text)
}

/** [text] 在 [content] 中唯一出现的位置；0 次或多次命中返回 -1。 */
internal fun uniqueOccurrence(content: String, text: String): Int {
    if (text.isEmpty()) return -1
    val first = content.indexOf(text)
    if (first < 0) return -1
    return if (content.indexOf(text, first + 1) < 0) first else -1
}

/** 选区前后各取 [CONTEXT_CHARS] 字符（钳制边界）。 */
internal fun extractContext(content: String, start: Int, length: Int): Pair<String, String> {
    val end = (start + length).coerceAtMost(content.length)
    val before = content.substring((start - CONTEXT_CHARS).coerceAtLeast(0), start)
    val after = content.substring(end, (end + CONTEXT_CHARS).coerceAtMost(content.length))
    return before to after
}

/**
 * 选区批注端口实现：把模块提交的正文空间选区在章节全文中定位、构造
 * 锚点并落库 book_marks 表。定位在 saveMarking 时以提示位置为锚做窗口
 * 搜索（含标题的选区在正文空间本就找不到文本，模块侧按设计 §3.4 不落
 * 划线静默忽略，不会提交到本端口）。
 */
internal object ReaderSelectionEngineImpl : ReaderSelectionEngine, KoinComponent {

    private val bookMarkingGateway: BookMarkingGateway by inject()
    private val saveMarkingUseCase: SaveMarkingUseCase by inject()
    private val bookmarkRepository: BookmarkRepository by inject()

    /**
     * 串行化书签 toggle（宿主 ReadBookmarkDelegate.toggleForCurrentPage 同款）：
     * 先查再写不是原子的，快速连滑/连点会双双看到「空」而重复插入。锁住
     * 读-查-写整段后，两次触发退化成正确的两次 toggle（加一条再删一条）。
     */
    private val toggleMutex = Mutex()

    /** 与宿主 ReadBookmarkDelegate/ReadBookController.addBookmark 一致：剔除正文里的排版占位符。 */
    private val BOOK_TEXT_MARKS = Regex("[袮꧁]")

    /** 当前会话章节的语义正文（章节不匹配返回 null）。 */
    private fun semanticContent(chapterIndex: Int): String? =
        ReadBook.readerChapterInputWindow.current
            ?.takeIf { it.chapter.index == chapterIndex }
            ?.source?.semanticContent

    /**
     * 有界等待当前章语义正文就绪：宿主重排（[ReaderEngineImpl.relayout] →
     * `clearTextChapter` → 异步 `loadContent`）期间内容窗口会短暂为空，用户在
     * 这段窗口内保存会被误判为「选区失效」。等内容重新发布后再取，超时仍无
     * 内容才按失败处理。
     */
    private suspend fun awaitSemanticContent(
        chapterIndex: Int,
        timeoutMillis: Long = CONTENT_WAIT_TIMEOUT_MILLIS,
    ): String? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (true) {
            semanticContent(chapterIndex)?.let { return it }
            if (SystemClock.elapsedRealtime() >= deadline) return null
            delay(CONTENT_WAIT_POLL_MILLIS)
        }
    }

    /** 当前窗口标题（标记 chapterName，宿主同款口径）。 */
    private fun displayTitle(): String =
        ReadBook.readerChapterInputWindow.current?.displayTitle.orEmpty()

    override suspend fun saveMarking(commit: ReaderSelectionCommit): Boolean {
        val book = ReadBook.book ?: run {
            AppLog.put("eink saveMarking: 无会话书")
            return false
        }
        val content = awaitSemanticContent(commit.chapterIndex) ?: run {
            AppLog.put("eink saveMarking: 章节内容未就绪 chapter=${commit.chapterIndex}")
            return false
        }
        val located = locateInContent(content, commit.start, commit.selectedText)
        // 选区失效从严：标记是文本锚点，定位不到即视为失效，不回退提示位
        if (located < 0) {
            AppLog.put(
                "eink saveMarking: 选区定位失败 chapter=${commit.chapterIndex} " +
                    "start=${commit.start} len=${commit.selectedText.length} " +
                    "text=${commit.selectedText.take(24)}"
            )
            return false
        }
        val (before, after) = extractContext(content, located, commit.selectedText.length)
        return try {
            saveMarkingUseCase.save(
                bookName = book.name,
                bookAuthor = book.author,
                bookUrl = book.bookUrl,
                chapterIndex = commit.chapterIndex,
                chapterPosition = located,
                selectedText = commit.selectedText,
                style = einkMarkingStyle(commit.thought),
                chapterName = displayTitle(),
                note = if (commit.thought) commit.note else "",
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

    /** 按 id 删 book_marks 后触发当前章重排（新快照经 onContentUpdated 推送）。 */
    override suspend fun deleteMarking(markingId: String): Boolean {
        ReadBook.book ?: return false
        return try {
            bookMarkingGateway.delete(markingId)
            ReaderEngineImpl.relayout()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.put("eink deleteMarking failed: ${e.message}", e)
            false
        }
    }

    /** 按 id 读 book_marks 映射详情（只读，不触发重排）。null = 标记不存在。 */
    override suspend fun findMarking(markingId: String): ReaderMarkingDetail? {
        ReadBook.book ?: return null
        val mark = bookMarkingGateway.getById(markingId) ?: return null
        val anchor = GSON.fromJsonObject<TextProcessAnchor>(mark.anchorJson).getOrNull()
        val style = GSON.fromJsonObject<TextProcessStyle>(mark.styleJson).getOrNull()
        return ReaderMarkingDetail(
            selectedText = anchor?.selectedText.orEmpty(),
            note = mark.note,
            thought = style?.underlineMode == 2,
        )
    }

    /**
     * 当前页书签 toggle（v2 Task 9，设计 §7）：宿主快速书签语义镜像
     * （ReadBookmarkDelegate.toggleForCurrentPage）——本页区间无书签则存一条
     * （页位置 + 页文本为标题，无编辑层），有则删离当前阅读位置最近的一条；
     * 成功后触发当前章重排，角标随新快照推送。null = 无会话书/当前页无法
     * 定位/落库异常；true = 本次添加；false = 本次移除。
     */
    override suspend fun togglePageBookmark(): Boolean? = try {
        toggleMutex.withLock {
            val book = ReadBook.book ?: return@withLock null
            val meta = ReaderEngineImpl.currentPageMeta() ?: return@withLock null
            val existing = bookmarkRepository.getByChapterRange(
                bookName = book.name,
                bookAuthor = book.author,
                chapterIndex = meta.chapterIndex,
                startPos = meta.bodyStart,
                endPos = meta.bodyEnd,
            )
            if (existing.isEmpty()) {
                bookmarkRepository.save(
                    Bookmark(
                        bookName = book.name,
                        bookAuthor = book.author,
                        bookUrl = book.bookUrl,
                        chapterIndex = meta.chapterIndex,
                        chapterName = meta.chapterTitle,
                        chapterPos = ReadBook.durChapterPos,
                        bookText = meta.text.replace(BOOK_TEXT_MARKS, "").trim(),
                        content = "",
                    )
                )
                // 角标随新快照刷新（保持页内位置）
                ReaderEngineImpl.relayout()
                true
            } else {
                // 只删离当前阅读位置最近的一条：同一页可能有多条书签，不应整页误删
                val nearest = existing.minByOrNull { abs(it.chapterPos - ReadBook.durChapterPos) }
                    ?: return@withLock null
                bookmarkRepository.delete(nearest)
                ReaderEngineImpl.relayout()
                false
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.put("eink togglePageBookmark failed: ${e.message}", e)
        null
    }
}
