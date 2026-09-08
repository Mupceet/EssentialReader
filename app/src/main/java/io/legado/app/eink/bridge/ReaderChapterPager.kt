package io.legado.app.eink.bridge

import io.legado.app.data.repository.HighlightRuleRepository
import io.legado.app.eink.contract.ReaderPageSnapshot
import io.legado.app.feature.reader.core.model.ReaderPage
import io.legado.app.feature.reader.core.navigation.ReaderChapterPaginationSnapshot
import io.legado.app.feature.reader.core.navigation.ReaderPageNavigator
import io.legado.app.feature.reader.legacy.LegacyReaderChapterPaginator
import io.legado.app.feature.reader.legacy.LegacyReaderChapterPaginationResult
import io.legado.app.feature.reader.legacy.LegacyReaderPageDecorationFactory
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** 小于该值的视口高度漂移不触发重排（页眉/页脚内容后填导致的首帧漂移）。 */
private const val VIEWPORT_EPSILON_PX = 24

/**
 * E-Ink 章节分页协调器：宿主 Compose 渲染层（ReadBookController 直排页）
 * 的 E-Ink 等价物。
 *
 * **与完整模式的对齐关系**：本类与 ReadBookController（完整模式渲染控制器）
 * 调用同一个 LegacyReaderChapterPaginator——它不是旧排版引擎（旧
 * ChapterProvider 体系已整体删除），而是新核心 ReaderPaginator 的设置
 * 适配层，完整模式自己的分页也走它（ReadBookController#directReaderLayoutJob）。
 * 章节输入、样式工厂、高亮规则、revision 公式、装饰预留均与完整模式
 * 同源同参（宿主分页器代码与上游零差异）。仅 contentPadding 传 0：
 * 完整模式画布全屏、经 ReaderContentAvoidancePolicy 向分页器避让系统栏；
 * E-Ink 模块画布已在布局层（insets）避让系统栏，语义一致。页眉/页脚
 * 由模块按宿主预留高度（headerDecorationExtentPx）叠加绘制在画布上，
 * 对齐完整模式「装饰画在预留区内」的几何。
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
 * 分页产物按「章节身份 + 视口 + 排版样式标量」为键缓存，并维护
 * 当前章 ±1 的三章窗口（对齐完整模式 directReaderPages 的内存档位）：
 *  - 当前章分页落地后，后台预排相邻章（下一章优先）——正常节奏阅读时
 *    翻到章边界直接命中热页，翻章不再同步等待全章分页（完整模式
 *    「相邻章热页复用」的对齐项）；
 *  - 样式与视口变更走键失效重排，旧页保留到新页落地（避免 E-Ink
 *    空屏闪烁）；
 *  - 同键在途去重：一次装载的多个章节输入先后到达会重复触发分页请求，
 *    取消重启会作废在途进度从头重排，同键在途时直接复用。
 */
internal class ReaderChapterPager(
    private val onPagesReady: () -> Unit,
    private val onPagesError: (Throwable) -> Unit,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 当前章分页（交互路径，新请求到来时取消重启）。 */
    private var paginateJob: Job? = null

    /** 相邻章预排（后台路径，交互分页到来时让路）。 */
    private var adjacentJob: Job? = null

    /** 交互分页在途键（同键请求去重，防取消重启浪费）。 */
    private var inFlightKey: String? = null

    private var viewportWidth = 0
    private var viewportHeight = 0

    /**
     * 三章窗口缓存（键 = 章节下标）。copy-on-write：主线程写、任意线程读
     * （快照映射可能在 VM 的 IO 协程中被读），引用替换保证可见性。
     */
    @Volatile
    private var chapters: Map<Int, ChapterPages> = emptyMap()

    /** E-Ink 画布视口是否已就绪（就绪后重进阅读页可免首帧尺寸等待）。 */
    val hasViewport: Boolean
        get() = viewportWidth > 0 && viewportHeight > 0

    /**
     * 当前章节是否已有分页产物。产物按章节下标索引，天然只对
     * [ReadBook.durChapterIndex] 命中：目录跳章（DB 写新进度后重进 attach）
     * 与跨章翻页（durChapterIndex 已 ++ 而新章尚未分页）的窗口期内，
     * 模块靠本值区分「直接刷新旧页」与「清空显示加载中」——其他章节的
     * 缓存页绝不可作为当前章渲染。
     */
    val hasPages: Boolean
        get() = chapters[ReadBook.durChapterIndex]?.pages?.isNotEmpty() == true

    /** 当前章节页数（分页产物只含当前章节）。 */
    val currentChapterPageSize: Int
        get() = chapters[ReadBook.durChapterIndex]?.pages?.size ?: 0

    /** E-Ink 画布视口就绪/变化（等价旧 ChapterProvider.upViewSize）。 */
    fun updateViewport(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        // 页眉/页脚内容（时间/电量文本）在首次渲染后才填充，会让画布高度
        // 漂移几个像素。小于一行正文的视口漂移直接忽略（存储值也不更新，
        // 否则下一次任何触发都会带着新视口全量重排）：快照自顶部绘制，
        // 几像素差异只是页脚方向多留空白，而一次重排+换页在墨水屏上就是
        // 一次肉眼可见的抖动
        if (width == viewportWidth && abs(height - viewportHeight) < VIEWPORT_EPSILON_PX) return
        if (width == viewportWidth && height == viewportHeight) return
        viewportWidth = width
        viewportHeight = height
        requestPagination()
    }

    /** 章节输入窗口变化（内容装载/换章，等价旧 loadContent 后的排版时机）。 */
    fun onChapterInputChanged() {
        requestPagination()
    }

    /**
     * 渲染回调到达时的窗口对账：跨章翻页只平移输入窗口
     * （moveReaderChapterInputNext/Previous）不触发任何回调，分页机会
     * 藏在下一次 ±1 预载完成的 input-changed 里——慢网/预载失败时新章
     * 永远排不上。每次 upContent/pageChanged 先对账一次（键未变时是
     * 空操作），窗口平移后立即补分页。
     */
    fun syncWithWindow() {
        requestPagination()
    }

    /** 取消在途分页（注销时防迟到 commit 通知已销毁回调）；缓存保留。 */
    fun cancelPending() {
        paginateJob?.cancel()
        paginateJob = null
        adjacentJob?.cancel()
        adjacentJob = null
    }

    /** 排版样式变更（等价旧 ChapterProvider.upStyle 失效重排）。 */
    fun onStyleChanged() {
        requestPagination()
    }

    /** 换书/换会话：丢弃缓存并停止在途分页。重排与注销不清缓存——
     *  重排是否需要由缓存键判定，注销后返回阅读页要靠热缓存即时恢复。 */
    fun clear() {
        cancelPending()
        inFlightKey = null
        chapters = emptyMap()
    }

    /** 当前阅读位置所在页映射为快照；未分页或无阅读位置返回 null。 */
    fun currentPageSnapshot(): ReaderPageSnapshot? {
        // 章节守卫：按当前章下标索引缓存，新章分页落地之间返回 null
        // （模块保持旧页或显示加载中），其他章节页绝不可作为当前章渲染
        val entry = chapters[ReadBook.durChapterIndex] ?: return null
        val pages = entry.pages
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
            paginationStyle = entry.style,
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
        val input = ReadBook.readerChapterInputWindow.current
        if (input == null) {
            // 跳章清窗（upData→clearTextChapter）会连带清空 ReadBook 分页快照，
            // 而本缓存可能仍有可用产物（如跳到预排过的相邻章）：补发布一次，
            // 保证页内导航（nextPageStart 等）不断链
            republishCachedSnapshots()
            return
        }
        val style = LegacyReaderPaginationStyleFactory.create()
        val key = cacheKey(input, style)
        val entry = chapters[input.chapter.index]
        if (entry != null && entry.key == key && entry.pages.isNotEmpty()) {
            // 命中也要补发布：ReadBook 侧快照可能被外部清空（清窗/重置）；
            // 相邻章输入晚于当前章到达时，这里也是预排的补触发点
            republishCachedSnapshots()
            scheduleAdjacentPagination(style)
            return
        }
        // 同键在途去重：一次装载的三个章节输入先后到达会重复触发本方法，
        // 无去重时每次都取消在途分页从头重排（同一章被排至多 3 次）
        if (key == inFlightKey && paginateJob?.isActive == true) return
        adjacentJob?.cancel()
        paginateJob?.cancel()
        val generation = ReadBook.readerPaginationGeneration
        inFlightKey = key
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
                inFlightKey = null
                when (result) {
                    is LegacyReaderChapterPaginationResult.Success -> {
                        // 分页期间可能已换书：旧章节产物不得落进新会话
                        if (ReadBook.book?.bookUrl != input.book.bookUrl) return@withContext
                        commit(input, style, result.pages, key, generation, notify = true)
                        scheduleAdjacentPagination(style)
                    }

                    is LegacyReaderChapterPaginationResult.Unsupported ->
                        onPagesError(IllegalStateException("排版失败：${result.reason}"))
                }
            }
        }
    }

    /**
     * 后台预排相邻章（下一章优先，对齐完整模式「当前章先行发布、邻章
     * 后台补排」的节奏）。仅排当前样式/视口下缺失的章；交互分页请求
     * 到来时整体让路。产物同样发布分页快照（页内导航需要），但不通知
     * 模块重绘——模块只消费当前章页面，多余通知在墨水屏上就是一次闪屏。
     */
    private fun scheduleAdjacentPagination(style: ReaderAndroidPaginationStyle) {
        if (adjacentJob?.isActive == true) return
        val bookUrl = ReadBook.book?.bookUrl ?: return
        val window = ReadBook.readerChapterInputWindow
        val candidates = listOfNotNull(window.next, window.previous).filter { input ->
            val entry = chapters[input.chapter.index]
            if (entry == null || entry.pages.isEmpty()) return@filter true
            entry.key != cacheKey(input, style)
        }
        if (candidates.isEmpty()) return
        val generation = ReadBook.readerPaginationGeneration
        adjacentJob = scope.launch {
            val highlightRules = HighlightRuleRepository()
                .loadEnabled(ReadBookConfig.durConfig.name)
            for (input in candidates) {
                ensureActive()
                val key = cacheKey(input, style)
                val entry = chapters[input.chapter.index]
                if (entry != null && entry.key == key && entry.pages.isNotEmpty()) continue
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
                if (result !is LegacyReaderChapterPaginationResult.Success) continue
                // 预排期间可能已换书：旧章节产物不得落进新会话
                if (ReadBook.book?.bookUrl != bookUrl) return@launch
                withContext(Dispatchers.Main) {
                    if (ReadBook.book?.bookUrl == bookUrl) {
                        commit(input, style, result.pages, key, generation, notify = false)
                    }
                }
            }
        }
    }

    /** 分页产物落缓存（仅主线程调用）：窗口淘汰 + 发布分页快照。 */
    private fun commit(
        input: ReaderChapterInput,
        style: ReaderAndroidPaginationStyle,
        pages: List<ReaderPage>,
        key: String,
        generation: Long,
        notify: Boolean,
    ) {
        val index = input.chapter.index
        val next = buildMap {
            putAll(chapters)
            put(
                index,
                ChapterPages(
                    key = key,
                    pages = pages,
                    pageStarts = pages.map(ReaderPageNavigator::pageStart),
                    contentEnd = ReaderPageNavigator.pageContext(pages, pages.lastIndex)?.endPosition,
                    style = style,
                    chapterIndex = index,
                    generation = generation,
                ),
            )
        }
        // 窗口淘汰：仅保留当前章 ±1，内存档位与完整模式三章窗口一致
        val window = ReadBook.durChapterIndex - 1..ReadBook.durChapterIndex + 1
        chapters = next.filterKeys { it in window }
        publishCachedSnapshots()
        // 页面对象可能未换（同页重排），靠 pageVersion 强制模块画布重绘
        if (notify) onPagesReady()
    }

    /** 把窗口内全部缓存章的分页快照发布给 ReadBook（页内导航的数据源）。 */
    private fun publishCachedSnapshots() {
        val snapshots = chapters.values
            .sortedBy { it.chapterIndex }
            .mapNotNull { entry ->
                entry.contentEnd?.let { contentEnd ->
                    ReaderChapterPaginationSnapshot(
                        chapterIndex = entry.chapterIndex,
                        pageStarts = entry.pageStarts,
                        contentEnd = contentEnd,
                        generation = entry.generation,
                    )
                }
            }
        if (snapshots.isNotEmpty()) {
            ReadBook.publishReaderPagination(snapshots)
        }
    }

    /**
     * ReadBook 侧分页快照被外部清空（跳章清窗/会话重置）而本缓存仍持有时
     * 补发布。逐章比对已发布内容，无缺失时不做任何事（翻页热路径零开销）。
     */
    private fun republishCachedSnapshots() {
        val snapshot = chapters
        if (snapshot.isEmpty()) return
        val upToDate = snapshot.values.all { entry ->
            val published = ReadBook.readerPagination(entry.chapterIndex)
            published != null &&
                    published.pageStarts == entry.pageStarts &&
                    published.contentEnd == entry.contentEnd
        }
        if (upToDate) return
        scope.launch(Dispatchers.Main) {
            // 期间可能已换书清缓存：引用变化即放弃（publish 会读最新缓存）
            if (chapters === snapshot) publishCachedSnapshots()
        }
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
            append(decorationCacheKeyFragment(ReadBookConfig.config)).append('|')
            // 直接钉住派生 extent 值（同完整模式键）：任何现在/未来的 extent 输入
            // （含 headerMode=0 档的 hideStatusBar 门控）变化都反映到这两个值，
            // 结构性闭合装饰参数错位
            append(LegacyReaderPageDecorationFactory.headerExtentPx()).append(',')
                .append(LegacyReaderPageDecorationFactory.footerExtentPx()).append('|')
            append(ReadBookConfig.durConfig.highlightRules.hashCode())
        }

    /** 单章分页产物缓存项。 */
    private class ChapterPages(
        val key: String,
        val pages: List<ReaderPage>,
        val pageStarts: List<Int>,
        val contentEnd: Int?,
        val style: ReaderAndroidPaginationStyle,
        val chapterIndex: Int,
        val generation: Long,
    )
}

/**
 * 页眉/页脚装饰的缓存键片段：这些参数经 extent（按字号/字体度量/
 * 上下边距/分割线推导分页预留高度）影响正文分页，任一变化必须触发
 * 重排。修复缺陷：改页眉上下边距/字号后条带高度变化但缓存键不含
 * 这些键，旧页坐标继续使用导致错位。
 *
 * 左右边距（headerPaddingLeft/Right、footerPaddingLeft/Right）仅
 * 条带内部绘制、不影响 extent，不入键——即时生效不重排。
 *
 * 采样 share-aware 的 [ReadBookConfig.config]，与 extent 工厂同源
 * （shareLayout 开启时 durConfig 不反映有效值）。
 *
 * 键中另行钉住派生 extent 值；本片段保留是为显式声明意图与可读性。
 */
internal fun decorationCacheKeyFragment(config: ReadBookConfig.Config): String = buildString {
    append(config.headerFontSize).append(',')
    append(config.footerFontSize).append(',')
    append(config.headerFont).append(',')
    append(config.footerFont).append(',')
    append(config.applyHeaderStyle).append(',')
    append(config.headerMode).append(',')
    append(config.footerMode).append(',')
    append(config.showHeaderLine).append(',')
    append(config.showFooterLine).append(',')
    append(config.headerPaddingTop).append(',').append(config.headerPaddingBottom).append(',')
    append(config.footerPaddingTop).append(',').append(config.footerPaddingBottom)
}
