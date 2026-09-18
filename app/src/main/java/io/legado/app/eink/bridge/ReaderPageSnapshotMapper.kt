package io.legado.app.eink.bridge

import android.graphics.Bitmap
import android.graphics.Paint
import android.os.Build
import io.legado.app.data.appDb
import io.legado.app.eink.contract.ReaderDecorationRun
import io.legado.app.eink.contract.ReaderImageSlot
import io.legado.app.eink.contract.ReaderPageLine
import io.legado.app.eink.contract.ReaderPageSnapshot
import io.legado.app.eink.contract.ReaderPaintSpec
import io.legado.app.model.ImageProvider
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.book.read.page.provider.ChapterProvider

/**
 * TextPage → 模块快照映射器（宿主唯一新增渲染职责）。
 *
 * 引擎排版完成后把页面映射为 [ReaderPageSnapshot]：上游排版结构的字段
 * 漂移由本映射器消化，模块画布只有一份绘制实现。API35+ 的逐字字距半格
 * 补偿在映射期算进 x 坐标，模块不再感知。
 *
 * 画笔规格只拷贝测量耦合参数（字号/字距/字体）——快照列坐标是引擎按
 * 这些参数测量的，模块必须按同值绘制才不错位；阴影/斜体等纯视觉效果
 * 不跨桥（E-Ink 阅读不渲染，既定产品取舍）。本宿主 minSdk 21：
 * 可变字重 getter 为 API 26，映射期按 SDK 门控（21~25 无可变字重排版，
 * 恒 null 安全）。
 *
 * 在引擎回调线程调用（onContentUpdated 内），产物不可变、跨线程安全。
 */
internal object ReaderPageSnapshotMapper {

    /** 生产入口：规格取自引擎当前共享画笔，补偿按真实 SDK 版本。 */
    fun map(page: TextPage): ReaderPageSnapshot {
        // 书签行一次查询双消费：页角标（位置书签）+ 划线装饰锚点（文字书签）
        val rows = currentBookmarkRows()
        return mapWithSpecs(
            page = page,
            titleSpec = ChapterProvider.titlePaint.copyPaintSpec(),
            contentSpec = ChapterProvider.contentPaint.copyPaintSpec(),
            sdkInt = Build.VERSION.SDK_INT,
            imageLoader = ::defaultImageLoader,
            bookmarkBadge = ReaderSelectionEngineImpl.supportsPageBookmark &&
                rows.any { row ->
                    row.bookText.isEmpty() &&
                        row.chapterIndex == page.chapterIndex &&
                        page.containPos(row.chapterPos)
                },
            markings = rows.asSequence()
                .filter { it.bookText.isNotEmpty() && it.chapterIndex == page.chapterIndex }
                .map { row ->
                    ReaderMarkingAnchor(
                        start = row.chapterPos,
                        text = row.bookText,
                        underlineMode = if (row.content.isEmpty()) 1 else 2,
                        markingId = row.time.toString(),
                    )
                }
                .toList(),
        )
    }

    /**
     * 当前会话书的全部书签行（主线程同步单行索引查询，宿主 lastReadBookUrl
     * 同款口径；映射在排版回调线程调用，产物不可变跨线程安全）。
     */
    private fun currentBookmarkRows(): List<io.legado.app.data.entities.Bookmark> {
        val book = ReadBook.book ?: return emptyList()
        return runCatching {
            appDb.bookmarkDao.getByBook(book.name, book.author)
        }.getOrDefault(emptyList())
    }

    /**
     * 锚点校验：划线的本质是「存储位置处的内容与存储原文吻合」——按页内
     * 行段（章内位置 → 行文本）逐字符核对锚点区间，页覆盖范围之外（跨页
     * 标记的本页外部分）放行，段间隙按段落分隔符 '\n' 核对。
     *
     * 作用：排除非划线锚点的行——完整模式快速书签的页摘录锚定在页中
     * 阅读位置（durChapterPos），与摘录起点不符，校验不过即不渲染（否则
     * 整页摘录会画成整页下划线）；同理正文变更后的陈旧标记不误划。完整
     * 模式选中书签的拼接若与渲染行字符有出入，校验保守拒绝——不产生
     * 错位下划线（列表/跳转不受影响）。
     */
    private fun validatedMarkings(
        markings: List<ReaderMarkingAnchor>,
        segmentStarts: List<Int>,
        segmentTexts: List<String>,
    ): List<ReaderMarkingAnchor> {
        if (markings.isEmpty() || segmentStarts.isEmpty()) return emptyList()
        val coverageStart = segmentStarts.first()
        val coverageEnd = segmentStarts.last() + segmentTexts.last().length
        return markings.filter { anchor ->
            if (anchor.text.isEmpty()) return@filter false
            var segmentIndex = 0
            var ok = true
            for ((offset, expected) in anchor.text.withIndex()) {
                val position = anchor.start + offset
                // 页覆盖范围之外（跨页标记的本页外部分）：无凭据即不判否
                if (position < coverageStart || position >= coverageEnd) continue
                while (
                    segmentIndex < segmentStarts.size - 1 &&
                    segmentStarts[segmentIndex + 1] <= position
                ) {
                    segmentIndex++
                }
                val indexInSegment = position - segmentStarts[segmentIndex]
                val actual = segmentTexts[segmentIndex].getOrNull(indexInSegment) ?: '\n'
                if (actual != expected) {
                    ok = false
                    break
                }
            }
            ok
        }
    }

    /** 页内标记锚点（映射期输入）：start 为章内位置，text 为划线原文。 */
    internal data class ReaderMarkingAnchor(
        val start: Int,
        val text: String,
        val underlineMode: Int,
        val markingId: String,
    )

    /** 行组装中间量（循环收集，锚点校验后统一产装饰）。 */
    private class PendingLine(
        val baseY: Float,
        val isTitle: Boolean,
        val chunks: List<String>,
        val x: FloatArray,
        val chapterPositions: IntArray,
        val top: Float,
        val bottom: Float,
        val startPos: Int,
        val textLen: Int,
    )

    /** 纯函数核心（单测直接喂规格与 SDK 版本）。 */
    internal fun mapWithSpecs(
        page: TextPage,
        titleSpec: ReaderPaintSpec,
        contentSpec: ReaderPaintSpec,
        sdkInt: Int,
        imageLoader: (String) -> (Int, Int) -> Bitmap?,
        bookmarkBadge: Boolean = false,
        markings: List<ReaderMarkingAnchor> = emptyList(),
    ): ReaderPageSnapshot {
        val images = ArrayList<ReaderImageSlot>()
        // 行段（章内位置 → 行文本）与待组装行：循环后锚点校验再产装饰
        val segmentStarts = ArrayList<Int>()
        val segmentTexts = ArrayList<String>()
        val pendingLines = ArrayList<PendingLine>()
        for (line in page.lines) {
            val spec = if (line.isTitle) titleSpec else contentSpec
            // API 35+ drawText 会将 letterSpacing 应用在两侧，引擎画布同样补偿半格
            val halfSpacing =
                if (sdkInt >= 35) spec.letterSpacing * spec.textSizePx * 0.5f else 0f
            val chunks = ArrayList<String>()
            val xs = ArrayList<Float>()
            // 各文本段首字符的章内位置：行 chapterPosition 起步，按行内元素
            // 字符占位累计（文本段按段长；图片/评论等替换字符各占 1 位）
            val positions = ArrayList<Int>()
            var position = line.chapterPosition
            for (column in line.columns) {
                when (column) {
                    is TextColumn -> {
                        chunks.add(column.charData)
                        xs.add(column.start + halfSpacing)
                        positions.add(position)
                        position += column.charData.length
                    }

                    is ImageColumn -> {
                        images.add(
                            ReaderImageSlot(
                                x0 = column.start,
                                x1 = column.end,
                                lineTop = line.lineTop,
                                lineBottom = line.lineBottom,
                                lineHeight = line.height,
                                fullLine = line.isImage,
                                loader = imageLoader(column.src),
                                source = column.src,
                            )
                        )
                        position += 1
                    }

                    else -> {
                        // 评论列/HTML 列等：E-Ink 不渲染（同引擎画布 else 分支），
                        // 字符占位照计（评论列为单字符替换）
                        position += 1
                    }
                }
            }
            if (chunks.isNotEmpty()) {
                segmentStarts.add(line.chapterPosition)
                segmentTexts.add(chunks.joinToString(""))
                pendingLines.add(
                    PendingLine(
                        baseY = line.lineBase,
                        isTitle = line.isTitle,
                        chunks = chunks,
                        x = xs.toFloatArray(),
                        chapterPositions = positions.toIntArray(),
                        top = line.lineTop,
                        bottom = line.lineBottom,
                        startPos = line.chapterPosition,
                        textLen = chunks.sumOf { it.length },
                    )
                )
            }
        }
        // 锚点校验后的行装饰：区间 = 锚点区间与行文本的交（跨长按原文
        // 长度近似；含图片/替换字符的行两套索引有少量漂移，钳制后最多
        // 划短不划错行），实线 = 划线 / 虚线 = 想法
        val validated = validatedMarkings(markings, segmentStarts, segmentTexts)
        val lines = pendingLines.map { pending ->
            ReaderPageLine(
                baseY = pending.baseY,
                isTitle = pending.isTitle,
                chunks = pending.chunks,
                x = pending.x,
                chapterPositions = pending.chapterPositions,
                top = pending.top,
                bottom = pending.bottom,
                decorations = if (validated.isEmpty()) {
                    emptyList()
                } else {
                    validated.mapNotNull { anchor ->
                        val start = (anchor.start - pending.startPos).coerceAtLeast(0)
                        val end = (anchor.start + anchor.text.length - pending.startPos)
                            .coerceAtMost(pending.textLen)
                        if (end > start) {
                            ReaderDecorationRun(
                                start = start,
                                end = end,
                                underlineMode = anchor.underlineMode,
                                highlight = false,
                                markingId = anchor.markingId,
                            )
                        } else {
                            null
                        }
                    }
                },
            )
        }
        return ReaderPageSnapshot(
            title = page.title,
            readProgress = page.readProgress,
            titleSpec = titleSpec,
            contentSpec = contentSpec,
            lines = lines,
            images = images,
            bookmarkBadge = bookmarkBadge,
        )
    }

    /**
     * 位图解析闭包：本宿主 ImageColumn 无 book 字段（引擎绘制时读全局
     * ReadBook.book），映射期同样按映射时刻的全局书籍取图——换书瞬间
     * 旧页若仍绘制，取到新书的目录（与引擎画布行为一致）；尺寸 ≤ 0
     * 直接返回 null，异常吞并返回 null，由画布跳过槽位。
     */
    private fun defaultImageLoader(src: String): (Int, Int) -> Bitmap? =
        { w, h ->
            val book = ReadBook.book
            if (w <= 0 || h <= 0 || book == null) {
                null
            } else {
                runCatching { ImageProvider.getImage(book, src, w, h) }.getOrNull()
            }
        }
}

/**
 * 引擎画笔 → 渲染规格（只拷贝测量耦合参数；color 由模块主题自涂，
 * 阴影/斜体等纯视觉效果不跨桥）。fontVariationSettings 为 API 26，
 * minSdk 21 宿主需门控。
 */
internal fun Paint.copyPaintSpec(): ReaderPaintSpec = ReaderPaintSpec(
    textSizePx = textSize, // API 1
    letterSpacing = letterSpacing, // API 21
    typeface = typeface, // API 1
    fontVariationSettings =
        if (Build.VERSION.SDK_INT >= 26) fontVariationSettings else null, // API 26
)
