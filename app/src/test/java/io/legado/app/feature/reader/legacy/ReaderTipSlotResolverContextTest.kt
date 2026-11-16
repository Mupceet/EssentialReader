package io.legado.app.feature.reader.legacy

import io.legado.app.R
import io.legado.app.constant.ReadTipType
import io.legado.app.feature.reader.core.model.ReaderTipValueFormatter
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import splitties.init.appCtx
import splitties.init.injectAsAppCtx

/**
 * buildContext 的上下文组装：页数未知给 `-`、整书页数无状态时本地化
 * `- / -` 兜底、时间空则现取、电量钳制。整书页数文案断言用
 * appCtx.getString 同源对照（锁定的是分支行为不是文案翻译）。
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class, sdk = [35])
class ReaderTipSlotResolverContextTest {

    @Before
    fun setUp() {
        // buildContext 经 splitties appCtx 取本地化整书页数文案：注入 Robolectric 应用
        RuntimeEnvironment.getApplication().injectAsAppCtx()
    }

    @Test
    fun `章未排出页时页数未知给横杠且进度按章计`() {
        val context = ReaderTipSlotResolver.buildContext(
            bookName = "书名",
            chapterTitle = "章节",
            chapterIndex = 3,
            pageIndex = 0,
            chapterPageCount = 0,
            chapterCount = 10,
            time = "10:05",
            batteryPercent = 85,
        )
        assertEquals(ReaderTipValueFormatter.UNKNOWN_PAGE_COUNT, context.pageCountText)
        assertEquals("40.0%", context.readProgress)
        assertEquals("1/-", ReaderTipSlotResolver.textFor(ReadTipType.tipPage, context, ""))
    }

    @Test
    fun `无整书分页状态时整书页数兜底文案`() {
        val context = ReaderTipSlotResolver.buildContext(
            bookName = "书名",
            chapterTitle = "章节",
            chapterIndex = 0,
            pageIndex = 0,
            chapterPageCount = 5,
            chapterCount = 1,
            time = "10:05",
            batteryPercent = 85,
        )
        assertEquals(
            appCtx.getString(R.string.whole_book_page_unavailable),
            ReaderTipSlotResolver.textFor(ReadTipType.tipWholeBookPage, context, ""),
        )
    }

    @Test
    fun `时间原样透传且电量超界钳制`() {
        val context = ReaderTipSlotResolver.buildContext(
            bookName = "书名",
            chapterTitle = "章节",
            chapterIndex = 0,
            pageIndex = 0,
            chapterPageCount = 5,
            chapterCount = 1,
            time = "",
            batteryPercent = 120,
        )
        // 时间兜底（空则现取）是完整模式工厂的调用侧兼容细节，解析器原样透传
        assertEquals("", context.time)
        assertEquals(100, context.batteryPercent)
    }
}
