package io.legado.app.eink.bridge

import android.os.SystemClock
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookMarking
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.repository.BookRepository
import io.legado.app.data.repository.BookmarkRepository
import io.legado.app.domain.gateway.BookMarkingGateway
import io.legado.app.domain.model.BookContentProcessEngine
import io.legado.app.domain.model.TextProcessAnchor
import io.legado.app.domain.model.TextProcessStyle
import io.legado.app.domain.usecase.BookmarkTargetVerdict
import io.legado.app.domain.usecase.RelocateMarkingTargetUseCase
import io.legado.app.domain.usecase.SaveMarkingUseCase
import io.legado.app.domain.usecase.VerifyBookmarkTargetUseCase
import io.legado.app.eink.contract.BookmarkUiModel
import io.legado.app.eink.contract.JumpResolution
import io.legado.app.eink.contract.MarkingUiModel
import io.legado.app.eink.contract.MarksEngine
import io.legado.app.eink.contract.ReaderMarkingDetail
import io.legado.app.eink.contract.ReaderPageBookmarkContent
import io.legado.app.eink.contract.ReaderSelectionCommit
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.bookmark.MarkingExporter
import io.legado.app.model.ReadBook
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.fromJsonObject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** styleJson → eink 两档图例：虚线（underlineMode 2）= 想法；其余（实线/背景/字体色/空）= 划线。 */
internal fun markingThought(styleJson: String?): Boolean =
    GSON.fromJsonObject<TextProcessStyle>(styleJson).getOrNull()?.underlineMode == 2

/** 「仍跳转」确认文案（模块弹层直接展示）。 */
internal fun confirmJumpMessage(chapterName: String): String =
    "书签创建后目录可能已变化（$chapterName），仍要跳转吗？"

/** 标记锚点（anchorJson → [TextProcessAnchor]；坏数据 null）。 */
internal fun anchorOf(marking: BookMarking): TextProcessAnchor? =
    GSON.fromJsonObject<TextProcessAnchor>(marking.anchorJson).getOrNull()

/**
 * 笔记列表按**章内正文本位置**升序（同位置按创建时间兜底）：卡片顺序与
 * 正文阅读顺序一致——补记的划线不会因为创建时间晚而排到章末。DAO 只能按
 * `chapterIndex, createdAt` 出库（位置在 anchorJson 里，SQL 排不了），
 * 故在映射后排序（纯函数，单测锚定）。
 */
internal fun orderMarkingsByPosition(markings: List<MarkingUiModel>): List<MarkingUiModel> =
    markings.sortedWith(compareBy({ it.chapterIndex }, { it.chapterPos }, { it.createdAt }))

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

/** 与宿主 ReadBookmarkDelegate/ReadBookController.addBookmark 一致：剔除正文里的排版占位符。 */
private val BOOK_TEXT_MARKS = Regex("[袮꧁]")

/**
 * 笔记状态转换（契约 v2 updateMarkingNote 的纯函数核）：按 note 派生类型并
 * 重写 styleJson——空白 = 划线（实线，note 归一空串）；非空白 = 想法（虚线）。
 * id、锚点、章节、createdAt 一律不动（原地更新，不是重建）。
 */
internal fun BookMarking.withEinkNote(note: String, now: Long): BookMarking {
    val hasThought = note.isNotBlank()
    return copy(
        note = if (hasThought) note else "",
        styleJson = GSON.toJson(einkMarkingStyle(hasThought)),
        updatedAt = now,
    )
}

/** 书签显示文本（契约 v2）：模块载荷落库前的存储规范化——剥离自家渲染占位符并 trim。 */
internal fun bookmarkDisplayText(pageText: String): String = pageText.replace(BOOK_TEXT_MARKS, "").trim()

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

/**
 * 选区定位（含**归一化兜底**）：先走 [locateInContent] 的逐字搜索，失配时改用
 * 宿主渲染层的同一把尺 [BookContentProcessEngine.resolveRange]（按非空白字符
 * 序列匹配 + 就近提示位）。
 *
 * 为什么需要兜底：选区文本与正文可能不逐字相等——跨空行/跨段选区按「正文
 * 间隙补一个换行」拼接，而正文里的段落分隔是逐字的；存锚点时
 * [BookContentProcessEngine.normalizeProcessText] 又会**逐行 trim**（部分书源
 * 正文自带段首缩进/全角空格被裁掉），与拼接口径再错开一层，逐字搜索与
 * 「全文唯一命中」全失配 → 上层报「保存失败」。编辑已不再走本路径（契约 v2
 * 按 id 更新，不重定位）；兜底此时仅剩 create 路径需要——用渲染层同款解析
 * 兜底后，逐字失配的选区仍能落锚。
 */
internal fun locateSelectionInContent(content: String, expectedStart: Int, text: String): Int {
    locateInContent(content, expectedStart, text).takeIf { it >= 0 }?.let { return it }
    val normalized = BookContentProcessEngine.normalizeProcessText(text)
    if (normalized.isEmpty()) return -1
    val anchor = TextProcessAnchor(
        chapterIndex = 0,
        chapterPosition = expectedStart.coerceIn(0, content.length),
        selectedText = normalized,
        normalizedTextHash = MD5Utils.md5Encode(normalized),
    )
    return BookContentProcessEngine.resolveRange(content, anchor)?.first ?: -1
}

/** 选区前后各取 [CONTEXT_CHARS] 字符（钳制边界）。 */
internal fun extractContext(content: String, start: Int, length: Int): Pair<String, String> {
    val end = (start + length).coerceAtMost(content.length)
    val before = content.substring((start - CONTEXT_CHARS).coerceAtLeast(0), start)
    val after = content.substring(end, (end + CONTEXT_CHARS).coerceAtMost(content.length))
    return before to after
}

/** eink 书签/笔记统一端口实现：阅读内批注状态机（契约 v2：createMarking/updateMarkingNote 按 id）+ 页面书签 toggle + 列表流 + 跳目标解析 + Markdown 导出。 */
object MarksEngineImpl : MarksEngine, KoinComponent {

    private val bookRepository: BookRepository by inject()
    private val verifyUseCase = VerifyBookmarkTargetUseCase()
    private val relocateUseCase = RelocateMarkingTargetUseCase()

    private val bookMarkingGateway: BookMarkingGateway by inject()
    private val saveMarkingUseCase: SaveMarkingUseCase by inject()
    private val bookmarkRepository: BookmarkRepository by inject()

    override fun observeBookmarks(bookUrl: String): Flow<List<BookmarkUiModel>> =
        bookFlow(bookUrl) { book ->
            appDb.bookmarkDao.flowByBook(book.name, book.author)
                .map { list -> list.map { it.toUiModel() } }
        }

    override fun observeMarkings(bookUrl: String): Flow<List<MarkingUiModel>> =
        bookFlow(bookUrl) { book ->
            appDb.bookMarkingDao.flowByBook(book.name, book.author)
                // DAO 按 (chapterIndex, createdAt) 出库；卡要按「读到的先后」排，
                // 故映射出锚点位置后再按正文本位置排序（同位置按创建时间兜底）
                .map { list -> orderMarkingsByPosition(list.map { it.toUiModel() }) }
        }

    /** 解析书 → 无书发空列表，有书接 DAO 流；getBook 为一次性查询，每次重新收集才重解析书籍记录（换源替换后需重新进入页面触发新收集）。 */
    private fun <T> bookFlow(
        bookUrl: String,
        source: suspend (Book) -> Flow<List<T>>,
    ): Flow<List<T>> = flow {
        val book = bookRepository.getBook(bookUrl) ?: run {
            emit(emptyList<T>()); return@flow
        }
        emitAll(source(book))
    }

    private fun Bookmark.toUiModel() = BookmarkUiModel(
        id = time, chapterIndex = chapterIndex, chapterName = chapterName,
        bookText = bookText, content = content,
    )

    private fun BookMarking.toUiModel(): MarkingUiModel {
        val anchor = anchorOf(this)
        return MarkingUiModel(
            id = id,
            chapterIndex = chapterIndex ?: 0,
            chapterName = chapterName,
            chapterPos = anchor?.chapterPosition ?: 0,
            selectedText = anchor?.selectedText ?: "",
            note = note,
            thought = markingThought(styleJson),
            createdAt = createdAt,
        )
    }

    override suspend fun resolveBookmarkJump(bookmarkId: Long): JumpResolution {
        val bookmark = appDb.bookmarkDao.getById(bookmarkId)
            ?: return JumpResolution.Failed("书签不存在")
        val book = bookRepository.getBook(bookmark.bookName, bookmark.bookAuthor)
            ?: return JumpResolution.Failed("书籍不存在")
        val targetTitle = bookRepository.getChapterTitle(book.name, book.author, bookmark.chapterIndex)
        return when (verifyUseCase.verify(book.bookUrl, targetTitle, bookmark.bookUrl, bookmark.chapterName)) {
            BookmarkTargetVerdict.Match ->
                JumpResolution.Located(bookmark.chapterIndex, bookmark.chapterPos)
            else -> JumpResolution.NeedConfirm(
                message = confirmJumpMessage(bookmark.chapterName),
                fallback = JumpResolution.Located(bookmark.chapterIndex, bookmark.chapterPos),
            )
        }
    }

    override suspend fun resolveMarkingJump(markingId: String): JumpResolution {
        val marking = appDb.bookMarkingDao.getById(markingId)
            ?: return JumpResolution.Failed("标记不存在")
        val anchor = GSON.fromJsonObject<TextProcessAnchor>(marking.anchorJson).getOrNull()
            ?: return JumpResolution.Failed("标记数据异常")
        val chapterIndex = marking.chapterIndex ?: anchor.chapterIndex
        val stored = JumpResolution.Located(chapterIndex, anchor.chapterPosition ?: 0)
        val book = bookRepository.getBook(marking.bookName, marking.bookAuthor)
            ?: return JumpResolution.Failed("书籍不存在")
        val targetTitle = bookRepository.getChapterTitle(book.name, book.author, chapterIndex)
        return when (verifyUseCase.verify(book.bookUrl, targetTitle, marking.bookUrl, marking.chapterName)) {
            BookmarkTargetVerdict.Match -> stored
            else -> relocateMarking(book, marking.chapterName, anchor)
                ?.let { JumpResolution.Located(it.chapterIndex, it.chapterPosition) }
                ?: JumpResolution.NeedConfirm(confirmJumpMessage(marking.chapterName), stored)
        }
    }

    /**
     * 本地重定位（对齐 ReadBookmarkNavigateDelegate.relocateMarking：仅本地已缓存章节，
     * 不发起网络）；章节内容读取为磁盘 IO，统一切到 [Dispatchers.IO]（同
     * [MarkingExporter.exportToUri] 先例）。
     */
    private suspend fun relocateMarking(
        book: Book,
        chapterName: String,
        anchor: TextProcessAnchor,
    ): RelocateMarkingTargetUseCase.Target? = withContext(Dispatchers.IO) {
        val processor = ContentProcessor.get(book)
        val candidates = bookRepository.getChapters(book.bookUrl)
            .asSequence()
            .filter {
                it.index == anchor.chapterIndex ||
                        (chapterName.isNotBlank() && it.title == chapterName)
            }
            .distinctBy { it.index }
            .mapNotNull { chapter ->
                val rawContent = BookHelp.getContent(book, chapter) ?: return@mapNotNull null
                RelocateMarkingTargetUseCase.Candidate(
                    chapterIndex = chapter.index,
                    content = processor.getContent(book, chapter, rawContent, includeTitle = false)
                        .toString(),
                )
            }
            .toList()
        relocateUseCase.locate(anchor, candidates)
    }

    override suspend fun exportMarkingsMarkdown(bookUrl: String, uri: String): Boolean {
        val book = bookRepository.getBook(bookUrl) ?: return false
        val markings = appDb.bookMarkingDao.getByBook(book.name, book.author, null)
        if (markings.isEmpty()) return false
        val content = MarkingExporter.formatToMarkdown(book.name, book.author, markings)
        return MarkingExporter.exportToUri(android.net.Uri.parse(uri), content)
    }

    // —— 阅读内：笔记状态机与页面书签 ——

    /**
     * 串行化书签 toggle（宿主 ReadBookmarkDelegate.toggleForCurrentPage 同款）：
     * 先查再写不是原子的，快速连滑/连点会双双看到「空」而重复插入。锁住
     * 读-查-写整段后，两次触发退化成正确的两次 toggle（加一条再删一条）。
     */
    private val toggleMutex = Mutex()

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

    /**
     * 新建笔记（契约 v2，仅新选区入口；已有标记上的动作走 updateMarkingNote/
     * deleteMarking）：等正文就绪 → 定位 → 锚点构造 → 落库 → 重排。
     * 类型由 note 派生：空白 = 划线（实线，note 归一空串），非空白 = 想法
     * （虚线）。归一化兜底与选区失效从严见 [locateSelectionInContent]。
     */
    override suspend fun createMarking(commit: ReaderSelectionCommit): Boolean {
        val book = ReadBook.book ?: run {
            AppLog.put("eink createMarking: 无会话书")
            return false
        }
        val content = awaitSemanticContent(commit.chapterIndex) ?: run {
            AppLog.put("eink createMarking: 章节内容未就绪 chapter=${commit.chapterIndex}")
            return false
        }
        // 归一化兜底：编辑已不再走本路径（契约 v2 按 id 更新），跨段/跨空行选区
        // 的拼接口径与正文不逐字相等，需走渲染层同款解析（见函数 KDoc）
        val located = locateSelectionInContent(content, commit.start, commit.selectedText)
        // 选区失效从严：标记是文本锚点，定位不到即视为失效，不回退提示位
        if (located < 0) {
            AppLog.put(
                "eink createMarking: 选区定位失败 chapter=${commit.chapterIndex} " +
                    "start=${commit.start} len=${commit.selectedText.length} " +
                    "text=${commit.selectedText.take(24)}"
            )
            return false
        }
        val (before, after) = extractContext(content, located, commit.selectedText.length)
        val hasThought = commit.note.isNotBlank()
        return try {
            saveMarkingUseCase.save(
                bookName = book.name,
                bookAuthor = book.author,
                bookUrl = book.bookUrl,
                chapterIndex = commit.chapterIndex,
                chapterPosition = located,
                selectedText = commit.selectedText,
                style = einkMarkingStyle(hasThought),
                chapterName = displayTitle(),
                note = if (hasThought) commit.note else "",
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
            AppLog.put("eink createMarking failed: ${e.message}", e)
            false
        }
    }

    /**
     * 状态转换唯一路径（契约 v2）：按 id 改 note + 样式，锚点不动、**不做选区
     * 重定位**（编辑已存标记不再可能因定位失败而保存失败）。null = 无会话书/
     * 标记不存在；false = 更新失败。成功后 relayout，新快照带新样式装饰推送。
     */
    override suspend fun updateMarkingNote(markingId: String, note: String): Boolean? = try {
        ReadBook.book ?: return null
        val mark = bookMarkingGateway.getById(markingId) ?: return null
        bookMarkingGateway.upsert(mark.withEinkNote(note, System.currentTimeMillis()))
        ReaderEngineImpl.relayout()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // upsert 成功但 relayout 抛异常时误报 false——更新已落库（按 id 幂等，重试安全），
        // 将在下次成功重排时呈现
        AppLog.put("eink updateMarkingNote failed: ${e.message}", e)
        false
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

    /** 按 id 读 book_marks 映射详情（只读，不触发重排；类型由 note 派生）。null = 标记不存在。 */
    override suspend fun findMarking(markingId: String): ReaderMarkingDetail? {
        ReadBook.book ?: return null
        val mark = bookMarkingGateway.getById(markingId) ?: return null
        val anchor = GSON.fromJsonObject<TextProcessAnchor>(mark.anchorJson).getOrNull()
        return ReaderMarkingDetail(
            selectedText = anchor?.selectedText.orEmpty(),
            note = mark.note,
        )
    }

    /**
     * 当前页书签 toggle（契约 v2，设计 §7）：宿主快速书签语义镜像
     * （ReadBookmarkDelegate.toggleForCurrentPage）——本页区间无书签则存一条
     * （页位置 + 页文本为标题，无编辑层），有则删离当前阅读位置最近的一条；
     * 成功后触发当前章重排，角标随新快照推送。显示字段来源改为模块载荷
     * [content]（存储规范化：占位符剥离 + trim）；同页判定（页正文区间）与
     * 「删最近一条」按宿主分页事实。null = 无会话书/当前页无法定位/落库异常；
     * true = 本次添加；false = 本次移除。
     */
    override suspend fun togglePageBookmark(content: ReaderPageBookmarkContent): Boolean? = try {
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
                        chapterName = content.chapterName,
                        chapterPos = ReadBook.durChapterPos,
                        bookText = bookmarkDisplayText(content.pageText),
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
