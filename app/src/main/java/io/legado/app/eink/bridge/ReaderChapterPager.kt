package io.legado.app.eink.bridge

import io.legado.app.data.repository.HighlightRuleRepository
import io.legado.app.eink.contract.ReaderPageSnapshot
import io.legado.app.feature.reader.core.model.ReaderPage
import io.legado.app.feature.reader.core.navigation.ReaderChapterPaginationSnapshot
import io.legado.app.feature.reader.core.navigation.ReaderPageNavigator
import io.legado.app.feature.reader.legacy.LegacyReaderChapterPaginator
import io.legado.app.feature.reader.legacy.LegacyReaderChapterPaginationResult
import io.legado.app.feature.reader.legacy.LegacyReaderPaginationStyleFactory
import io.legado.app.feature.reader.legacy.paginateLegacyReaderChapterSafely
import io.legado.app.feature.reader.platform.ReaderAndroidPaginationStyle
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.ReadBook
import io.legado.app.model.reader.ReaderChapterInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * E-Ink 章节分页协调器：宿主 Compose 渲染层（ReadBookController 直排页）
 * 的 E-Ink 等价物。
 *
 * 上游排版核心重写后，带元素的页对象（ReaderPage）不再存于 ReadBook 全局
 * 状态：ReadBook 只保留章节输入窗口（处理完的正文/标题/解析结果）与字符
 * 偏移分页快照，页对象由渲染层消费章节输入、经 LegacyReaderChapterPaginator
 * 排出。E-Ink 阅读页不宿主那套渲染层，由本类承担同角色：
 *  1. 消费 [ReadBook] 当前章节输入 + E-Ink 视口 + 排版样式分页（IO）；
 *  2. 把页起点回填 ReadBook 分页快照——durPageIndex/moveToNextPage/
 *     skipToPage 等页面导航全部从快照派生，不回填则页导航停摆；
 *  3. 通知引擎页面就绪，由引擎触发阅读页重读快照。
 *
 * 分页产物按「章节身份 + 视口 + 排版样式标量」为键缓存；样式与视口变更
 * 走失效重排，旧页保留到新页落地（避免 E-Ink 空屏闪烁）。
 */
internal class ReaderChapterPager(
    private val onPagesReady: () -> Unit,
    private val onPagesError: (Throwable) -> Unit,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var paginateJob: Job? = null

    private var viewportWidth = 0
    private var viewportHeight = 0

    private var cacheKey: String? = null
    private var cachedPages: List<ReaderPage> = emptyList()
    private var cachedStyle: ReaderAndroidPaginationStyle? = null

    /** 当前章节是否已有分页产物（等价旧 curTextChapter.pages 非空）。 */
    val hasPages: Boolean get() = cachedPages.isNotEmpty()

    /** 当前章节页数（分页产物只含当前章节）。 */
    val currentChapterPageSize: Int get() = cachedPages.size

    /**
     * 分页同源样式。快照画笔规格必须与排版测量同值，映射时从这里取
     * title/body 画笔，而不是重新 create()（期间设置可能已变）。
     */
    val paginationStyle: ReaderAndroidPaginationStyle? get() = cachedStyle

    /** E-Ink 画布视口就绪/变化（等价旧 ChapterProvider.upViewSize）。 */
    fun updateViewport(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width == viewportWidth && height == viewportHeight) return
        viewportWidth = width
        viewportHeight = height
        requestPagination()
    }

    /** 章节输入窗口变化（内容装载/换章，等价旧 loadContent 后的排版时机）。 */
    fun onChapterInputChanged() {
        requestPagination()
    }

    /** 排版样式变更（等价旧 ChapterProvider.upStyle 失效重排）。 */
    fun onStyleChanged() {
        requestPagination()
    }

    /** 换书/换会话：丢弃缓存并停止在途分页。重排与注销不清缓存——
     *  重排是否需要由缓存键判定，注销后返回阅读页要靠热缓存即时恢复。 */
    fun clear() {
        paginateJob?.cancel()
        paginateJob = null
        cacheKey = null
        cachedPages = emptyList()
        cachedStyle = null
    }

    /** 当前阅读位置所在页映射为快照；未分页或无阅读位置返回 null。 */
    fun currentPageSnapshot(): ReaderPageSnapshot? {
        val style = cachedStyle ?: return null
        val pages = cachedPages
        if (pages.isEmpty()) return null
        val book = ReadBook.book ?: return null
        val index = ReaderPageNavigator.locate(
            pages,
            ReadBook.durChapterIndex,
            ReadBook.durChapterPos,
        )
        val page = pages.getOrNull(index) ?: return null
        return ReaderPageSnapshotMapper.map(
            page = page,
            paginationStyle = style,
            sessionBook = book,
            readProgress = ReaderPageSnapshotMapper.readProgress(
                chapterIndex = page.id.chapterIndex,
                localPageIndex = index,
                chapterPageCount = pages.size,
                chapterSize = ReadBook.chapterSize,
            ),
        )
    }

    private fun requestPagination() {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        val input = ReadBook.readerChapterInputWindow.current ?: return
        val style = LegacyReaderPaginationStyleFactory.create()
        val key = cacheKey(input, style)
        if (key == cacheKey && cachedPages.isNotEmpty()) return
        paginateJob?.cancel()
        val generation = ReadBook.readerPaginationGeneration
        paginateJob = scope.launch {
            val highlightRules = HighlightRuleRepository()
                .loadEnabled(ReadBookConfig.durConfig.name)
            val result = paginateLegacyReaderChapterSafely {
                LegacyReaderChapterPaginator.paginate(
                    book = input.book,
                    bookSource = input.bookSource,
                    chapter = input.chapter,
                    displayTitle = input.displayTitle,
                    content = input.content,
                    source = input.source,
                    revision = 31L * key.hashCode() + input.chapter.index,
                    viewportWidthPx = viewportWidth,
                    viewportHeightPx = viewportHeight,
                    paginationStyle = style,
                    highlightRules = highlightRules,
                )
            }
            withContext(Dispatchers.Main) {
                when (result) {
                    is LegacyReaderChapterPaginationResult.Success -> {
                        // 分页期间可能已换书：旧章节产物不得落进新会话
                        if (ReadBook.book?.bookUrl != input.book.bookUrl) return@withContext
                        commit(input, style, result.pages, key, generation)
                    }

                    is LegacyReaderChapterPaginationResult.Unsupported ->
                        onPagesError(IllegalStateException("排版失败：${result.reason}"))
                }
            }
        }
    }

    private fun commit(
        input: ReaderChapterInput,
        style: ReaderAndroidPaginationStyle,
        pages: List<ReaderPage>,
        key: String,
        generation: Long,
    ) {
        cacheKey = key
        cachedPages = pages
        cachedStyle = style
        val chapterIndex = input.chapter.index
        ReaderPageNavigator.pageContext(pages, pages.lastIndex)?.endPosition?.let { contentEnd ->
            ReadBook.publishReaderPagination(
                listOf(
                    ReaderChapterPaginationSnapshot(
                        chapterIndex = chapterIndex,
                        pageStarts = pages.map(ReaderPageNavigator::pageStart),
                        contentEnd = contentEnd,
                        generation = generation,
                    )
                )
            )
        }
        onPagesReady()
    }

    /** 缓存键：章节身份 + 视口 + 影响排版的全部样式标量（对照直排层 key）。 */
    private fun cacheKey(input: ReaderChapterInput, style: ReaderAndroidPaginationStyle): String =
        buildString {
            append(input.chapter.index).append('|')
            append(input.chapter.url).append('|')
            append(input.chapter.baseUrl).append('|')
            append(input.displayTitle).append('|')
            append(input.chapter.isVolume).append('|')
            append(input.contentHash).append('|')
            append(input.contentProcessesHash).append('|')
            append(input.sourceHash).append('|')
            append(input.book.bookUrl).append('|')
            append(input.book.origin).append('|')
            append(input.bookSourceHash).append('|')
            append(input.book.getImageStyle()).append('|')
            append(viewportWidth).append('x').append(viewportHeight).append('|')
            append(style.columnMode).append('|')
            append(style.isScroll).append('|')
            append(style.textBottomJustify).append('|')
            append(style.pageUnderline).append('|')
            append(style.emphasisUnderlineStyle).append('|')
            append(style.bodyPaint.textSize).append('|')
            append(style.titlePaint.textSize).append('|')
            append(style.bodyPaint.letterSpacing).append('|')
            append(style.bodyStyle.fontWeight).append('|')
            append(style.titleStyle.fontWeight).append('|')
            append(style.bodyStyle.fontPath).append('|')
            append(style.titleStyle.fontPath).append('|')
            append(style.bodyStyle.italic).append('|')
            append(style.bodyStyle.fontFamily).append('|')
            append(style.bodyStyle.linearText).append('|')
            append(ReadBookConfig.paragraphIndent).append('|')
            append(ReadBookConfig.textFullJustify).append('|')
            append(ReadBookConfig.titleMode).append('|')
            append(style.paddingLeftPx).append(',').append(style.paddingTopPx)
                .append(',').append(style.paddingRightPx)
                .append(',').append(style.paddingBottomPx).append('|')
            append(style.lineSpacingExtra).append('|')
            append(style.titleLineSpacingExtra).append('|')
            append(style.titleLineSpacingSub).append('|')
            append(style.titleSegmentation).append('|')
            append(style.titleTopSpacingPx).append('|')
            append(style.titleBottomSpacingPx).append('|')
            append(style.paragraphSpacing).append('|')
            append(ReadBookConfig.durConfig.highlightRules.hashCode())
        }
}
