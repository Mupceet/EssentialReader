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
import io.legado.app.feature.reader.core.model.ReaderElement
import io.legado.app.feature.reader.core.model.ReaderPage
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
 *    装饰，颜色一律不跨桥。
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
        val lines = ArrayList<ReaderPageLine>()
        val images = ArrayList<ReaderImageSlot>()
        var buffer: LineBuffer? = null

        for (element in page.elements) {
            when (element) {
                is ReaderElement.Text -> {
                    var line = buffer
                    // 同一视觉行的元素共享行顶 y（分页器逐行使用同一 y 值）
                    if (line != null && element.bounds.top != line.top) {
                        line.flushInto(lines)
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
                }

                is ReaderElement.Image -> {
                    buffer?.flushInto(lines)
                    buffer = null
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
                        )
                    )
                }

                else -> Unit // 评论/动作/装饰等元素：E-Ink 不渲染（同 View 画布 else 分支）
            }
        }
        buffer?.flushInto(lines)
        return ReaderPageSnapshot(
            title = page.chapterTitle,
            readProgress = readProgress,
            titleSpec = titleSpec,
            contentSpec = contentSpec,
            lines = lines,
            images = images,
            bookmarkBadge = bookmarkBadge,
        )
    }

    /** 行内累积中的文本段；flush 时按「行 → 段」结构落盘。 */
    private class LineBuffer {
        val chunks = ArrayList<String>()
        val xs = ArrayList<Float>()
        val chapterPositions = ArrayList<Int>()
        // 与 chunks 平行的逐段装饰签名（underline.mode；backgroundArgb 是否非空；
        // markingId 归一后的标记身份——null 表示非用户标记来源，归一为空串）
        val underlineModes = ArrayList<Int>()
        val highlights = ArrayList<Boolean>()
        val markingIds = ArrayList<String>()
        var top = 0f
        var bottom = 0f
        var baseY = 0f
        var isTitle = false

        /** flush 时把相邻同签名段合并为行内装饰 run（行内拼接文本 UTF-16 索引）。
         *  合并键 = (underlineMode, highlight, markingId) 三元组全等：
         *  不同标记不并入同一 run（run.markingId 是点按命中的定位键）。 */
        private fun buildDecorations(): List<ReaderDecorationRun> {
            val runs = ArrayList<ReaderDecorationRun>()
            var runStart = -1
            var runMode = 0
            var runHighlight = false
            var runMarkingId = ""
            var offset = 0
            for (i in chunks.indices) {
                val mode = underlineModes[i]
                val highlight = highlights[i]
                val markingId = markingIds[i]
                val same = runStart >= 0 && mode == runMode && highlight == runHighlight &&
                    markingId == runMarkingId
                if (!same) {
                    // 无签名段（0, false）只负责截断前序 run，自身不成 run
                    if (runStart >= 0 && (runMode != 0 || runHighlight)) {
                        runs.add(
                            ReaderDecorationRun(runStart, offset, runMode, runHighlight, runMarkingId)
                        )
                    }
                    runStart = offset
                    runMode = mode
                    runHighlight = highlight
                    runMarkingId = markingId
                }
                offset += chunks[i].length
            }
            if (runStart >= 0 && (runMode != 0 || runHighlight)) {
                runs.add(
                    ReaderDecorationRun(runStart, offset, runMode, runHighlight, runMarkingId)
                )
            }
            return runs
        }

        fun flushInto(lines: MutableList<ReaderPageLine>) {
            if (chunks.isEmpty()) return
            lines.add(
                ReaderPageLine(
                    baseY = baseY,
                    isTitle = isTitle,
                    chunks = chunks,
                    x = xs.toFloatArray(),
                    chapterPositions = chapterPositions.toIntArray(),
                    top = top,
                    bottom = bottom,
                    decorations = buildDecorations(),
                )
            )
        }
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
