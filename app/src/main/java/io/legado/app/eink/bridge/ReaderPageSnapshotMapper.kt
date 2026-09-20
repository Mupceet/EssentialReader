package io.legado.app.eink.bridge

import android.graphics.Bitmap
import android.graphics.Paint
import android.os.Build
import io.legado.app.data.entities.Book
import io.legado.app.eink.contract.ReaderDecorationRun
import io.legado.app.eink.contract.ReaderImageSlot
import io.legado.app.eink.contract.ReaderPageLine
import io.legado.app.eink.contract.ReaderPageSnapshot
import io.legado.app.eink.contract.ReaderPaintSpec
import io.legado.app.eink.contract.ReaderUnderlineGeometry
import io.legado.app.feature.reader.core.model.ReaderElement
import io.legado.app.feature.reader.core.model.ReaderPage
import io.legado.app.feature.reader.core.model.ReaderUnderline
import io.legado.app.feature.reader.platform.ReaderAndroidPaginationStyle
import io.legado.app.model.ImageProvider
import java.text.DecimalFormat

/**
 * ReaderPage → 模块快照映射器（宿主唯一新增渲染职责）。
 *
 * 上游排版核心（feature/reader/core）的页对象是扁平元素流：每个
 * [ReaderElement.Text] 是一个排版簇（CJK 逐字），自带测量好的横坐标与
 * 基线；同一视觉行的元素共享行顶 y。本映射器把它折叠回模块画布的
 * 「行 → 段」结构，并消化字段漂移：
 *  - 行的标题性由元素的 emphasized 承载（分页器对标题段落恒写 true）；
 *  - API35+ 的逐字字距半格补偿（View 版画布行为）在映射期算进 x 坐标，
 *    模块不再感知；
 *  - 图片元素自带最终布局矩形（缩放/居中已由分页器算好），槽位整框
 *    透传，模块按 fullLine 铺满即与引擎布局一致；
 *  - 划线/高亮样式（ReaderTextStyle.underline/backgroundArgb）折叠为行内
 *    装饰 run（相邻且样式签名 + markingId 全等的段合并——run.markingId 是
 *    模块点按命中的定位键，不同标记不并入同一 run）；字体色标记不产生
 *    装饰，颜色一律不跨桥；
 *  - 段落边界（paragraphBreaksAfter）从排版块结构推导：元素携带块序号
 *    （paragraphIndex），同一文本块的折行续行间 0、块末行 1、其后每个
 *    空行/占位块（Spacer 等，不产生行）累加 1——与分页器拼 pageText 的
 *    `append('\n')` 口径（段末/空行各一个）同构。
 *
 * 画笔规格只拷贝测量耦合参数（字号/字距/字体/可变字重）——快照坐标
 * 是引擎按这些参数测量的，模块必须按同值绘制才不错位；阴影/斜体等纯
 * 视觉效果不跨桥（E-Ink 阅读不渲染，既定产品取舍）。
 *
 * 在引擎回调线程调用，产物不可变、跨线程安全。
 */
internal object ReaderPageSnapshotMapper {

    /** 生产入口：规格取分页同源样式画笔，进度文本由分页方按页上下文计算。 */
    fun map(
        page: ReaderPage,
        paginationStyle: ReaderAndroidPaginationStyle,
        sessionBook: Book?,
        readProgress: String,
        bookmarkBadge: Boolean = false,
    ): ReaderPageSnapshot =
        mapWithSpecs(
            page = page,
            titleSpec = paginationStyle.titlePaint.copyPaintSpec(),
            contentSpec = paginationStyle.bodyPaint.copyPaintSpec(),
            sdkInt = Build.VERSION.SDK_INT,
            sessionBook = sessionBook,
            readProgress = readProgress,
            bookmarkBadge = bookmarkBadge,
            imageLoader = ::defaultImageLoader,
        )

    /** 纯函数核心（单测直接喂规格与 SDK 版本）。 */
    internal fun mapWithSpecs(
        page: ReaderPage,
        titleSpec: ReaderPaintSpec,
        contentSpec: ReaderPaintSpec,
        sdkInt: Int,
        sessionBook: Book?,
        readProgress: String,
        bookmarkBadge: Boolean = false,
        imageLoader: (Book, String) -> (Int, Int) -> Bitmap?,
    ): ReaderPageSnapshot {
        val staged = ArrayList<LineBuffer>()
        val images = ArrayList<ReaderImageSlot>()
        var buffer: LineBuffer? = null
        // 段落边界推导状态（见类 KDoc）：元素 paragraphIndex 是分页器写入的块序号
        var lastTextBlockIndex: Int? = null
        var pendingBlankLines = 0
        var trailingBlockElement = false

        for (element in page.elements) {
            when (element) {
                is ReaderElement.Text -> {
                    val blockIndex = element.paragraphIndex
                    if (blockIndex != lastTextBlockIndex) {
                        if (lastTextBlockIndex == null) {
                            // 页首空行：宿主 page.text 的前导 \n（宿主落库 `bookmarkDisplayText` 的 trim 消化该前导），不计入任何行
                            pendingBlankLines = 0
                        } else {
                            // 新文本块开始 = 上一个文本块已收尾：残留 buffer 的末行先落盘，
                            // 其段末边界 = 1 + 其后累计的空行块数
                            buffer?.flushInto(staged)
                            buffer = null
                            staged.lastOrNull()?.let { it.breaksAfter = 1 + pendingBlankLines }
                            pendingBlankLines = 0
                        }
                        lastTextBlockIndex = blockIndex
                    }
                    trailingBlockElement = false
                    var line = buffer
                    // 同一视觉行的元素共享行顶 y（分页器逐行使用同一 y 值）
                    if (line != null && element.bounds.top != line.top) {
                        line.flushInto(staged)
                        line = null
                    }
                    if (line == null) {
                        line = LineBuffer().also {
                            it.top = element.bounds.top
                            it.bottom = element.bounds.bottom
                            it.baseY = element.baselinePx
                            it.isTitle = element.emphasized
                        }
                        buffer = line
                    } else if (element.bounds.bottom > line.bottom) {
                        line.bottom = element.bounds.bottom
                    }
                    val spec = if (element.emphasized) titleSpec else contentSpec
                    // API 35+ drawText 会将 letterSpacing 应用在两侧，View 版同样补偿半格
                    val halfSpacing =
                        if (sdkInt >= 35) spec.letterSpacing * spec.textSizePx * 0.5f else 0f
                    line.chunks.add(element.value)
                    line.xs.add(element.bounds.left + halfSpacing)
                    line.chapterPositions.add(element.chapterPosition)
                    line.underlineModes.add(element.style.underline?.mode ?: 0)
                    line.highlights.add(element.style.backgroundArgb != null)
                    line.markingIds.add(element.markingId.orEmpty())
                    // 下划线几何透传给模块（宿主样式即唯一真源，见 ReaderUnderlineGeometry）
                    line.underlines.add(element.style.underline?.toGeometry())
                }

                is ReaderElement.Image -> {
                    buffer?.flushInto(staged)
                    buffer = null
                    // 独立图片块：其前的文本块已收尾（pageText 里段末 \n 先于 \uFFFC）
                    trailingBlockElement = true
                    val book = sessionBook
                    images.add(
                        ReaderImageSlot(
                            x0 = element.bounds.left,
                            x1 = element.bounds.right,
                            lineTop = element.bounds.top,
                            lineBottom = element.bounds.bottom,
                            lineHeight = element.bounds.height,
                            fullLine = true,
                            loader = if (book != null) {
                                imageLoader(book, element.source)
                            } else {
                                { _, _ -> null }
                            },
                            // 交互元数据原样透传（点击动作分派用，见契约
                            // ReaderImageSlot KDoc）；与几何测量无关
                            source = element.source,
                            action = element.action,
                        )
                    )
                }

                is ReaderElement.Spacer ->
                    // 空行块（BlankLine）：宿主 pageText 为它单独补一个 \n，
                    // 累加进上一个文本块的段末边界（空行分隔 = 2）
                    pendingBlankLines++

                is ReaderElement.Rule ->
                    // 分隔线是独立块：其前的文本块已收尾（pageText 补过段末 \n），
                    // 自身不产生行也不补 \n，仅作末行段末证据
                    trailingBlockElement = true

                else -> Unit // 评论/动作/装饰等元素：E-Ink 不渲染（同 View 画布 else 分支）
            }
        }
        buffer?.flushInto(staged)
        // 末行按页内可见结构填实际边界：空行累计 +（其后还有独立非文本块时的）段末 1；
        // 页尾无任何后续元素时无法区分「同段续行跨页」与「块在页底收尾」，按续行 0 填
        // （末行值不参与模块拼装，见契约 ReaderPageLine.paragraphBreaksAfter）
        staged.lastOrNull()?.let {
            it.breaksAfter = pendingBlankLines + if (pendingBlankLines > 0 || trailingBlockElement) 1 else 0
        }
        return ReaderPageSnapshot(
            title = page.chapterTitle,
            readProgress = readProgress,
            titleSpec = titleSpec,
            contentSpec = contentSpec,
            lines = staged.map { it.toLine() },
            images = images,
            bookmarkBadge = bookmarkBadge,
        )
    }

    /** 行内累积中的文本段；flush 落盘到暂存区，边界确定后经 [toLine] 物化。 */
    private class LineBuffer {
        val chunks = ArrayList<String>()
        val xs = ArrayList<Float>()
        val chapterPositions = ArrayList<Int>()
        // 与 chunks 平行的逐段装饰签名（underline.mode；backgroundArgb 是否非空；
        // markingId 归一后的标记身份——null 表示非用户标记来源，归一为空串）
        val underlineModes = ArrayList<Int>()
        val highlights = ArrayList<Boolean>()
        val markingIds = ArrayList<String>()
        // 与 chunks 平行的宿主下划线几何（ReaderUnderline → 契约 ReaderUnderlineGeometry）
        val underlines = ArrayList<ReaderUnderlineGeometry?>()
        var top = 0f
        var bottom = 0f
        var baseY = 0f
        var isTitle = false

        /** 本行之后的段落边界数（映射主循环按块结构回填，见类 KDoc）。 */
        var breaksAfter = 0

        /** flush 时构建的行内装饰 run（构建逻辑与合并键见 [buildDecorations]）。 */
        private var decorations: List<ReaderDecorationRun> = emptyList()

        /** flush 时把相邻同签名段合并为行内装饰 run（行内拼接文本 UTF-16 索引）。
         *  合并键 = (underlineMode, highlight, markingId) 三元组全等：
         *  不同标记不并入同一 run（run.markingId 是点按命中的定位键）。 */
        private fun buildDecorations(): List<ReaderDecorationRun> {
            val runs = ArrayList<ReaderDecorationRun>()
            var runStart = -1
            var runMode = 0
            var runHighlight = false
            var runMarkingId = ""
            var runUnderline: ReaderUnderlineGeometry? = null
            var offset = 0
            for (i in chunks.indices) {
                val mode = underlineModes[i]
                val highlight = highlights[i]
                val markingId = markingIds[i]
                val underline = underlines[i]
                val same = runStart >= 0 && mode == runMode && highlight == runHighlight &&
                    markingId == runMarkingId && underline == runUnderline
                if (!same) {
                    // 无签名段（0, false）只负责截断前序 run，自身不成 run
                    if (runStart >= 0 && (runMode != 0 || runHighlight)) {
                        runs.add(
                            ReaderDecorationRun(
                                runStart, offset, runMode, runHighlight, runMarkingId, runUnderline,
                            )
                        )
                    }
                    runStart = offset
                    runMode = mode
                    runHighlight = highlight
                    runMarkingId = markingId
                    runUnderline = underline
                }
                offset += chunks[i].length
            }
            if (runStart >= 0 && (runMode != 0 || runHighlight)) {
                runs.add(
                    ReaderDecorationRun(
                        runStart, offset, runMode, runHighlight, runMarkingId, runUnderline,
                    )
                )
            }
            return runs
        }

        fun flushInto(lines: MutableList<LineBuffer>) {
            if (chunks.isEmpty()) return
            decorations = buildDecorations()
            lines.add(this)
        }

        /** 边界回填完成后物化为契约行（paragraphBreaksAfter 见映射主循环）。 */
        fun toLine(): ReaderPageLine = ReaderPageLine(
            baseY = baseY,
            isTitle = isTitle,
            chunks = chunks,
            x = xs.toFloatArray(),
            chapterPositions = chapterPositions.toIntArray(),
            top = top,
            bottom = bottom,
            paragraphBreaksAfter = breaksAfter,
            decorations = decorations,
        )
    }

    /**
     * 阅读进度文本（沿用旧 TextPage.readProgress 公式）：章节进度加上页内
     * 进度折算，"0.0%" 格式；未到末章末页却算出 100.0% 时钳到 "99.9%"。
     */
    internal fun readProgress(
        chapterIndex: Int,
        localPageIndex: Int,
        chapterPageCount: Int,
        chapterSize: Int,
    ): String {
        val formatter = DecimalFormat("0.0%")
        if (chapterSize == 0 || chapterPageCount == 0 && chapterIndex == 0) {
            return "0.0%"
        }
        if (chapterPageCount == 0) {
            return formatter.format((chapterIndex + 1.0f) / chapterSize.toDouble())
        }
        var percent = formatter.format(
            chapterIndex * 1.0f / chapterSize +
                1.0f / chapterSize * ((localPageIndex + 1) / chapterPageCount.toDouble())
        )
        if (percent == "100.0%" &&
            (chapterIndex + 1 != chapterSize || localPageIndex + 1 != chapterPageCount)
        ) {
            percent = "99.9%"
        }
        return percent
    }

    /**
     * 位图解析闭包：捕获映射时那一次的会话书（换书瞬间旧页不误取新书目录，
     * 与 View 版 ImageColumn 一致）；尺寸 ≤ 0 直接返回 null（对齐旧画布
     * 取图前防护），异常吞并返回 null，由画布跳过槽位。
     */
    private fun defaultImageLoader(book: Book, src: String): (Int, Int) -> Bitmap? =
        { w, h ->
            if (w <= 0 || h <= 0) {
                null
            } else {
                runCatching { ImageProvider.getImage(book, src, w, h) }.getOrNull()
            }
        }
}

/**
 * 引擎画笔 → 渲染规格（只拷贝测量耦合参数；color 由模块主题自涂，
 * 阴影/斜体/linearText 等纯视觉效果不跨桥）。这些 getter 的 API 级别
 * 均 ≤ 26（= :app minSdk），无需门控。
 */
internal fun Paint.copyPaintSpec(): ReaderPaintSpec = ReaderPaintSpec(
    textSizePx = textSize, // API 1
    letterSpacing = letterSpacing, // API 21
    typeface = typeface, // API 1
    fontVariationSettings = fontVariationSettings, // API 26 = minSdk
)

/**
 * 宿主下划线样式 → 模块绘制几何（px）：字段一一对应，模块不再自拟公式。
 * 完整模式画布用 `y = 行盒下沿 + offsetPx`，模块据此绘制即逐像素对齐
 * （见 [ReaderUnderlineGeometry]）。
 */
internal fun ReaderUnderline.toGeometry(): ReaderUnderlineGeometry = ReaderUnderlineGeometry(
    widthPx = widthPx,
    offsetPx = offsetPx,
    dashOnPx = dashOnPx,
    dashOffPx = dashOffPx,
    waveAmplitudePx = waveAmplitudePx,
    waveLengthPx = waveLengthPx,
    doubleLineGapPx = doubleLineGapPx,
)
