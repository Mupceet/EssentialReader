package io.legado.app.feature.reader.legacy

import io.legado.app.R
import io.legado.app.constant.ReadTipType
import io.legado.app.feature.reader.core.model.ReaderTipValueContext
import io.legado.app.feature.reader.core.model.ReaderTipValueFormatter
import io.legado.app.feature.reader.core.model.ReaderTipValueType
import io.legado.app.model.ReadBook
import splitties.init.appCtx
import java.text.DecimalFormat

/**
 * 页眉/页脚信息槽位共享解析器：完整模式装饰工厂与 E-Ink 桥共用一份，
 * 取值规则（[ReaderTipValueFormatter]）、同型多槽只取首个、空文本留空
 * 只有一处实现。
 *
 * 文本化降级（E-Ink 路径用，完整模式工厂保留图形槽）：电量图形 4 种 →
 * `NN%`；时间+图形电量 2 种 → `HH:mm NN%`；箭头 2 种的文本本就是纯
 * 章节标题（[ReaderTipValueType.CHAPTER_TITLE_ARROW] 的 format 输出）。
 */
object ReaderTipSlotResolver {

    /** 六槽最终文本（宿主侧内部形态；E-Ink 桥再映射为契约 ReaderTipSlotTexts）。 */
    data class TipSlotTexts(
        val headerLeft: String,
        val headerMiddle: String,
        val headerRight: String,
        val footerLeft: String,
        val footerMiddle: String,
        val footerRight: String,
    )

    /** ReadTipType → 值类型（自 LegacyReaderPageDecorationFactory 平移，行为不变）。 */
    fun type(type: Int): ReaderTipValueType = when (type) {
        ReadTipType.tipNone -> ReaderTipValueType.NONE
        ReadTipType.tipChapterTitle -> ReaderTipValueType.CHAPTER_TITLE
        ReadTipType.tipTime -> ReaderTipValueType.TIME
        ReadTipType.tipBattery,
        ReadTipType.tipBatteryPercentage,
        ReadTipType.tipBatteryInside,
        ReadTipType.tipBatteryIcon,
        ReadTipType.tipBatteryClassic -> ReaderTipValueType.BATTERY
        ReadTipType.tipPage -> ReaderTipValueType.PAGE
        ReadTipType.tipTotalProgress -> ReaderTipValueType.TOTAL_PROGRESS
        ReadTipType.tipPageAndTotal -> ReaderTipValueType.PAGE_AND_TOTAL
        ReadTipType.tipBookName -> ReaderTipValueType.BOOK_NAME
        ReadTipType.tipTimeBattery,
        ReadTipType.tipTimeBatteryPercentage,
        ReadTipType.tipTimeBatteryClassic -> ReaderTipValueType.TIME_BATTERY
        ReadTipType.tipTotalProgress1 -> ReaderTipValueType.CHAPTER_INDEX_AND_TOTAL
        ReadTipType.tipChapterTitleArrow,
        ReadTipType.tipChapterTitleArrowClassic -> ReaderTipValueType.CHAPTER_TITLE_ARROW
        ReadTipType.tipCustom -> ReaderTipValueType.CUSTOM
        ReadTipType.tipWholeBookPage -> ReaderTipValueType.WHOLE_BOOK_PAGE
        ReadTipType.tipWholeBookPageAndProgress -> ReaderTipValueType.WHOLE_BOOK_PAGE_AND_PROGRESS
        else -> ReaderTipValueType.NONE
    }

    /** 文本化取值时的类型映射：图形型降级，文本型直通。 */
    fun textTypeOf(type: Int): ReaderTipValueType = when (type) {
        ReadTipType.tipBattery,
        ReadTipType.tipBatteryInside,
        ReadTipType.tipBatteryIcon,
        ReadTipType.tipBatteryClassic -> ReaderTipValueType.BATTERY
        ReadTipType.tipTimeBattery,
        ReadTipType.tipTimeBatteryClassic -> ReaderTipValueType.TIME_BATTERY
        else -> type(type)
    }

    /** 单槽最终文本；tipNone/空模板留空。 */
    fun textFor(type: Int, context: ReaderTipValueContext, customTemplate: String): String =
        if (type == ReadTipType.tipNone) {
            ""
        } else {
            ReaderTipValueFormatter.format(textTypeOf(type), context, customTemplate)
        }

    /**
     * 一个条带（页眉或页脚）按配置位产出槽位文本：同（类型+模板）多槽只取
     * 首个、其余留空——对照完整模式工厂 tips() 的 seen 去重语义；空串 =
     * 该槽不绘制。
     */
    fun bandTexts(context: ReaderTipValueContext, slots: List<Pair<Int, String>>): List<String> {
        val seen = mutableSetOf<Pair<Int, String>>()
        return slots.map { (type, template) ->
            if (!seen.add(type to template)) "" else textFor(type, context, template)
        }
    }

    /** 六槽组装（header/footer 各按 左/中/右 顺序传入槽位配置）。 */
    fun sixSlotTexts(
        context: ReaderTipValueContext,
        header: List<Pair<Int, String>>,
        footer: List<Pair<Int, String>>,
    ): TipSlotTexts {
        val headerTexts = bandTexts(context, header)
        val footerTexts = bandTexts(context, footer)
        fun at(texts: List<String>, index: Int): String = texts.getOrElse(index) { "" }
        return TipSlotTexts(
            headerLeft = at(headerTexts, 0),
            headerMiddle = at(headerTexts, 1),
            headerRight = at(headerTexts, 2),
            footerLeft = at(footerTexts, 0),
            footerMiddle = at(footerTexts, 1),
            footerRight = at(footerTexts, 2),
        )
    }

    /**
     * 阅读进度，逐条对照旧 `TextPage.readProgress`（自工厂平移，行为不变）：
     * `chapterSize == 0` 或"首页且章还没排出页" → `0.0%`；章还没排出页 → 按章计；
     * 其余按"章 + 页"计，且未真正读完时不允许显示 100%。
     */
    fun readProgress(chapterIndex: Int, pageIndex: Int, pageCount: Int, chapterCount: Int): String {
        if (chapterCount == 0 || (pageCount == 0 && chapterIndex == 0)) return ZERO_PROGRESS
        val formatter = DecimalFormat("0.0%")
        if (pageCount == 0) {
            return formatter.format((chapterIndex + 1.0f) / chapterCount.toDouble())
        }
        var progress = formatter.format(
            chapterIndex * 1.0f / chapterCount +
                1.0f / chapterCount * (pageIndex + 1) / pageCount.toDouble(),
        )
        if (progress == "100.0%" && (chapterIndex + 1 != chapterCount || pageIndex + 1 != pageCount)) {
            progress = "99.9%"
        }
        return progress
    }

    /**
     * 槽位取值上下文（自工厂 create() 内联块平移，行为不变）：时间由调用方
     * 传入现值（完整模式工厂保留「空则现取」兜底，E-Ink 桥恒传现值）、
     * 电量钳 0..100、章数至少 1、页数未知给 `-`、整书页数文案本地化并带
     * `- / -` 兜底。整书页数状态取 [ReadBook.getWholeBookPageState]（两模式
     * 共用同一份全局分页状态）。
     */
    fun buildContext(
        bookName: String,
        chapterTitle: String,
        chapterIndex: Int,
        pageIndex: Int,
        chapterPageCount: Int,
        chapterCount: Int,
        time: String,
        batteryPercent: Int,
    ): ReaderTipValueContext {
        val wholeBook = ReadBook.getWholeBookPageState(chapterIndex, pageIndex)
        return ReaderTipValueContext(
            bookName = bookName,
            chapterTitle = chapterTitle,
            time = time,
            batteryPercent = batteryPercent.coerceIn(0, 100),
            chapterIndex = chapterIndex,
            chapterCount = chapterCount.coerceAtLeast(1),
            pageIndex = pageIndex,
            pageCount = chapterPageCount.coerceAtLeast(1),
            readProgress = readProgress(
                chapterIndex = chapterIndex,
                pageIndex = pageIndex,
                pageCount = chapterPageCount,
                chapterCount = chapterCount,
            ),
            pageCountText = if (chapterPageCount <= 0) {
                ReaderTipValueFormatter.UNKNOWN_PAGE_COUNT
            } else {
                chapterPageCount.toString()
            },
            wholeBookPageText = when {
                wholeBook == null -> appCtx.getString(R.string.whole_book_page_unavailable)
                !wholeBook.estimated && wholeBook.allPreviousChaptersExact -> appCtx.getString(
                    R.string.whole_book_page_format,
                    wholeBook.currentPage,
                    wholeBook.totalPages,
                )

                else -> appCtx.getString(
                    R.string.whole_book_page_estimated_format,
                    wholeBook.currentPage.toString(),
                    wholeBook.totalPages.toString(),
                )
            },
            wholeBookPageIndex = wholeBook?.currentPage,
            wholeBookPageCount = wholeBook?.totalPages,
        )
    }

    private const val ZERO_PROGRESS = "0.0%"
}
