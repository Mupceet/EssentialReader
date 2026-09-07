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

    /** 缓存分页产物所属章节；与 ReadBook.durChapterIndex 不一致视为无产物。 */
    private var cachedChapterIndex: Int = -1

    /**
     * 当前章节是否已有分页产物。必须校验章节一致：目录跳章（DB 写新进度后
     * 重进 attach）与跨章翻页（durChapterIndex 已 ++ 而新章尚未分页）的
     * 窗口期内，模块靠本值区分「直接刷新旧页」与「清空显示加载中」——
     * 旧章缓存页绝不可作为新章渲染。
     */
    val hasPages: Boolean
        get() = cachedPages.isNotEmpty() && cachedChapterIndex == ReadBook.durChapterIndex

    /** 当前章节页数（分页产物只含当前章节）。 */
    val currentChapterPageSize: Int
        get() = if (cachedChapterIndex == ReadBook.durChapterIndex) cachedPages.size else 0

    /**
     * 分页同源样式。快照画笔规格必须与排版测量同值，映射时从这里取
     * title/body 画笔，而不是重新 create()（期间设置可能已变）。
     */
    val paginationStyle: ReaderAndroidPaginationStyle? get() = cachedStyle

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
        cachedChapterIndex = -1
    }

    /** 当前阅读位置所在页映射为快照；未分页或无阅读位置返回 null。 */
    fun currentPageSnapshot(): ReaderPageSnapshot? {
        val style = cachedStyle ?: return null
        val pages = cachedPages
        if (pages.isEmpty()) return null
        // 章节守卫：章节切换到新章分页落地之间返回 null（模块保持旧页或
        // 显示加载中），旧章页绝不可作为新章渲染
        if (cachedChapterIndex != ReadBook.durChapterIndex) return null
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
        cachedChapterIndex = input.chapter.index
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
            append(decorationCacheKeyFragment(ReadBookConfig.config)).append('|')
            // 直接钉住派生 extent 值（同完整模式键）：任何现在/未来的 extent 输入
            // （含 headerMode=0 档的 hideStatusBar 门控）变化都反映到这两个值，
            // 结构性闭合装饰参数错位
            append(LegacyReaderPageDecorationFactory.headerExtentPx()).append(',')
                .append(LegacyReaderPageDecorationFactory.footerExtentPx()).append('|')
            append(ReadBookConfig.durConfig.highlightRules.hashCode())
        }
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
