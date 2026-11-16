package io.legado.app.feature.reader.legacy

import io.legado.app.constant.ReadTipType
import io.legado.app.feature.reader.core.model.ReaderTipValueContext
import io.legado.app.feature.reader.core.model.ReaderTipValueType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 槽位文本解析器语义锚定：视觉型降级（电量图形→NN%、时间+图形电量→
 * HH:mm NN%、箭头→纯章节标题）、同型多槽只取首个、tipNone/空模板槽
 * 留空、六槽组装。上下文构建（本地化整书页数文案）在
 * ReaderTipSlotResolverContextTest（Robolectric）。
 */
class ReaderTipSlotResolverTest {

    private val context = ReaderTipValueContext(
        bookName = "书名",
        chapterTitle = "章节",
        time = "10:05",
        batteryPercent = 85,
        chapterIndex = 2,
        chapterCount = 10,
        pageIndex = 3,
        pageCount = 15,
        readProgress = "23.4%",
    )

    @Test
    fun `视觉型电量降级为百分比文本`() {
        listOf(
            ReadTipType.tipBattery,
            ReadTipType.tipBatteryInside,
            ReadTipType.tipBatteryIcon,
            ReadTipType.tipBatteryClassic,
        ).forEach { type ->
            assertEquals(ReaderTipValueType.BATTERY, ReaderTipSlotResolver.textTypeOf(type))
            assertEquals("85%", ReaderTipSlotResolver.textFor(type, context, ""))
        }
    }

    @Test
    fun `时间加图形电量降级为时间加百分比文本`() {
        listOf(ReadTipType.tipTimeBattery, ReadTipType.tipTimeBatteryClassic).forEach { type ->
            assertEquals(ReaderTipValueType.TIME_BATTERY, ReaderTipSlotResolver.textTypeOf(type))
            assertEquals("10:05 85%", ReaderTipSlotResolver.textFor(type, context, ""))
        }
    }

    @Test
    fun `箭头槽位文本即纯章节标题`() {
        listOf(
            ReadTipType.tipChapterTitleArrow,
            ReadTipType.tipChapterTitleArrowClassic,
        ).forEach { type ->
            assertEquals("章节", ReaderTipSlotResolver.textFor(type, context, ""))
        }
    }

    @Test
    fun `文本型直通`() {
        assertEquals("10:05", ReaderTipSlotResolver.textFor(ReadTipType.tipTime, context, ""))
        assertEquals("章节", ReaderTipSlotResolver.textFor(ReadTipType.tipChapterTitle, context, ""))
        assertEquals("书名", ReaderTipSlotResolver.textFor(ReadTipType.tipBookName, context, ""))
        assertEquals("4/15  23.4%", ReaderTipSlotResolver.textFor(ReadTipType.tipPageAndTotal, context, ""))
        assertEquals("3/10", ReaderTipSlotResolver.textFor(ReadTipType.tipTotalProgress1, context, ""))
        // 电量百分比本就是文本型
        assertEquals("85%", ReaderTipSlotResolver.textFor(ReadTipType.tipBatteryPercentage, context, ""))
        assertEquals("10:05 85%", ReaderTipSlotResolver.textFor(ReadTipType.tipTimeBatteryPercentage, context, ""))
    }

    @Test
    fun `自定义模板解析`() {
        assertEquals(
            "书名 章节",
            ReaderTipSlotResolver.textFor(ReadTipType.tipCustom, context, "{BookName} {ChapterTitle}"),
        )
    }

    @Test
    fun `tipNone 与空模板留空`() {
        assertEquals("", ReaderTipSlotResolver.textFor(ReadTipType.tipNone, context, ""))
        assertEquals("", ReaderTipSlotResolver.textFor(ReadTipType.tipCustom, context, ""))
    }

    @Test
    fun `同型多槽只取首个`() {
        val texts = ReaderTipSlotResolver.bandTexts(
            context,
            listOf(
                ReadTipType.tipTime to "",
                ReadTipType.tipTime to "",     // 重复：留空
                ReadTipType.tipBookName to "",
            ),
        )
        assertEquals(listOf("10:05", "", "书名"), texts)
    }

    @Test
    fun `同型不同模板不算重复`() {
        val texts = ReaderTipSlotResolver.bandTexts(
            context,
            listOf(
                ReadTipType.tipCustom to "{BookName}",
                ReadTipType.tipCustom to "{ChapterTitle}",
            ),
        )
        assertEquals(listOf("书名", "章节"), texts)
    }

    @Test
    fun `六槽组装按配置位归位`() {
        val texts = ReaderTipSlotResolver.sixSlotTexts(
            context,
            header = listOf(
                ReadTipType.tipTime to "",
                ReadTipType.tipNone to "",
                ReadTipType.tipBattery to "",
            ),
            footer = listOf(
                ReadTipType.tipChapterTitle to "",
                ReadTipType.tipBookName to "",
                ReadTipType.tipPageAndTotal to "",
            ),
        )
        assertEquals("10:05", texts.headerLeft)
        assertEquals("", texts.headerMiddle)
        assertEquals("85%", texts.headerRight)
        assertEquals("章节", texts.footerLeft)
        assertEquals("书名", texts.footerMiddle)
        assertEquals("4/15  23.4%", texts.footerRight)
    }

    @Test
    fun `进度公式未到末页封顶 99_9`() {
        // 第 3/10 章、第 4/15 页：2/10 + 1/10*4/15 = 22.7%
        assertEquals("22.7%", ReaderTipSlotResolver.readProgress(2, 3, 15, 10))
        // 末章末页允许 100.0%
        assertEquals("100.0%", ReaderTipSlotResolver.readProgress(9, 14, 15, 10))
        // 非末页但舍入到 100.0% → 钳 99.9%（0.99 + 0.01×996/1000 = 99.996%）
        assertEquals("99.9%", ReaderTipSlotResolver.readProgress(99, 995, 1000, 100))
        // 章还没排出页且是首页 → 0.0%
        assertEquals("0.0%", ReaderTipSlotResolver.readProgress(0, 0, 0, 10))
        // 章还没排出页、非首页 → 按章计
        assertEquals("40.0%", ReaderTipSlotResolver.readProgress(3, 0, 0, 10))
    }
}
