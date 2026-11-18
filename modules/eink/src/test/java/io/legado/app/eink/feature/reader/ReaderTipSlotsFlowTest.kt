package io.legado.app.eink.feature.reader

import android.app.Application
import io.legado.app.eink.contract.BookHandle
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.contract.GlobalSettings
import io.legado.app.eink.contract.ReaderBookSnapshot
import io.legado.app.eink.contract.ReaderEngine
import io.legado.app.eink.contract.ReaderEngineCallback
import io.legado.app.eink.contract.ReaderHeaderFooterVisibility
import io.legado.app.eink.contract.ReaderPageSnapshot
import io.legado.app.eink.contract.ReaderPrepareResult
import io.legado.app.eink.contract.ReaderTextStyle
import io.legado.app.eink.contract.ReaderTipSlotTexts
import io.legado.app.eink.contract.SessionShelfOpResult
import io.legado.app.eink.contract.StartCacheResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 槽位文本流灌回归：引擎 headerFooterTipTexts 非 null 时 uiState.tipSlots
 * 如实承载（完整模式槽位配置遵从）；旧宿主默认 null 时回落字段照旧
 * （headerTime 仍由时钟对表路径写入，渲染层回落硬装配）。
 *
 * 端口注入经反射替换注册表 _readerEngine/_globalSettings（同
 * ReaderHeaderClockResumeTest）；Main 换 StandardTestDispatcher，
 * runCurrent 推进 onActivityResumed 时钟启动的立即 updateTipInfo。
 */
class ReaderTipSlotsFlowTest {

    private val engine = TipSlotsEngine()
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        injectEngines(engine, TipSlotsSettings())
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        injectEngines(null, null)
    }

    @Test
    fun `引擎槽位文本写入 uiState`() {
        engine.tipSlotTexts = ReaderTipSlotTexts(
            headerLeft = "书名", headerMiddle = "", headerRight = "10:05",
            footerLeft = "章节", footerMiddle = "60%", footerRight = "4/15",
        )
        val vm = ReaderViewModel(Application())
        vm.onActivityResumed()
        testDispatcher.scheduler.runCurrent()
        val slots = vm.uiState.value.tipSlots
        assertEquals("书名", slots?.headerLeft)
        assertEquals("", slots?.headerMiddle)
        assertEquals("10:05", slots?.headerRight)
        assertEquals("章节", slots?.footerLeft)
        assertEquals("60%", slots?.footerMiddle)
        assertEquals("4/15", slots?.footerRight)
    }

    @Test
    fun `旧宿主 null 回落且时间字段照旧`() {
        engine.tipSlotTexts = null
        val vm = ReaderViewModel(Application())
        vm.onActivityResumed()
        testDispatcher.scheduler.runCurrent()
        assertNull(vm.uiState.value.tipSlots)
        assertEquals("10:00", vm.uiState.value.headerTime)
    }

    @Test
    fun `uiState 槽位选择器回落映射`() {
        // 直接构造 UiState 锁定选择器：tipSlots 优先、null 回落硬装配
        val withSlots = ReaderUiState(
            tipSlots = ReaderTipSlotTexts(
                headerLeft = "L", headerMiddle = "M", headerRight = "R",
                footerLeft = "FL", footerMiddle = "FM", footerRight = "FR",
            ),
        )
        assertEquals("L", withSlots.headerLeftText)
        assertEquals("M", withSlots.headerMiddleText)
        assertEquals("R", withSlots.headerRightText)
        assertEquals("FL", withSlots.footerLeftText)
        assertEquals("FM", withSlots.footerMiddleText)
        assertEquals("FR", withSlots.footerRightText)

        val legacy = ReaderUiState(
            headerTime = "10:00",
            batteryPercent = 85,
            chapterTitle = "章节",
            readProgress = "12.3%",
        )
        // 无页数信息时 pageIndicator 为空、pageAndTotal 仅进度
        assertEquals("10:00", legacy.headerLeftText)
        assertEquals("", legacy.headerMiddleText)
        assertEquals("85%", legacy.headerRightText)
        assertEquals("章节", legacy.footerLeftText)
        assertEquals("", legacy.footerMiddleText)
        assertEquals("12.3%", legacy.footerRightText)
    }

    private fun injectEngines(engine: ReaderEngine?, settings: GlobalSettings?) {
        EInkEngineRegistry::class.java.getDeclaredField("_readerEngine").apply {
            isAccessible = true
        }.set(EInkEngineRegistry, engine)
        EInkEngineRegistry::class.java.getDeclaredField("_globalSettings").apply {
            isAccessible = true
        }.set(EInkEngineRegistry, settings)
    }

    private class TipSlotsSettings : GlobalSettings {
        override var autoRefreshBook = false
        override var defaultToRead = false
        override var volumeKeyPage = false
        override var useDefaultCover = false
        override var pullDownBookmark = false
        override var readerSourceInfoBar = false
        override var hideStatusBar = false
        override var showReviewBubbles = true
        override val threadCount = 1
        override val useAntiAlias = false
        override val preDownloadChapterCount = 0
        override val changeSourceCheckAuthor = true
        override var fontScaleSetting: Int? = null
        override var recentFontPathsEncoding = ""
    }

    /** 槽位文本可控的最小引擎桩（成员清单同 ReaderHeaderClockResumeTest.HeaderClockEngine）。 */
    private class TipSlotsEngine : ReaderEngine {
        var tipSlotTexts: ReaderTipSlotTexts? = null

        override fun applyStyle(style: ReaderTextStyle) = Unit
        override fun currentStyle(): ReaderTextStyle = ReaderTextStyle()
        override fun register(callback: ReaderEngineCallback) = Unit
        override fun unregister(callback: ReaderEngineCallback) = Unit
        override fun isRegistered(callback: ReaderEngineCallback): Boolean = false
        override fun saveReadingProgress() = Unit
        override val sessionBook: ReaderBookSnapshot? get() = null
        override val sessionBookUrl: String? get() = null
        override val chapterSize: Int get() = 0
        override val currentChapterIndex: Int get() = 0
        override val currentPageIndex: Int get() = 0
        override val engineMessage: String? get() = null
        override val hasLaidOutPages: Boolean get() = false
        override val currentChapterPageSize: Int get() = 0
        override fun currentPage(): ReaderPageSnapshot? = null
        override fun loadBook(book: BookHandle) = Unit
        override fun reloadBook(book: BookHandle) = Unit
        override fun setInBookshelf(value: Boolean) = Unit
        override fun clearEngineMessage() = Unit
        override fun loadContent(resetPageOffset: Boolean) = Unit
        override fun loadContent(chapterIndex: Int, resetPageOffset: Boolean) = Unit
        override fun refreshToc() = Unit
        override suspend fun resolveBook(bookUrl: String): ReaderBookSnapshot? = null
        override suspend fun prepareBookData(book: BookHandle): ReaderPrepareResult =
            ReaderPrepareResult.Success
        override fun nextPage(): Boolean = false
        override fun prevPage(): Boolean = false
        override fun skipToPage(pageIndex: Int) = Unit
        override fun nextChapter(): Boolean = false
        override fun prevChapter(): Boolean = false
        override fun jumpToPosition(chapterIndex: Int, chapterPos: Int): Boolean = false
        override val autoReadIntervalSec: Int get() = 10
        override suspend fun setAutoReadIntervalSec(value: Int) = Unit
        override suspend fun refreshCurrentChapter() = Unit
        override fun startCache(count: Int, cacheAll: Boolean): StartCacheResult =
            StartCacheResult.NothingToCache
        override suspend fun addSessionBookToShelf(): SessionShelfOpResult =
            SessionShelfOpResult.NotApplicable
        override suspend fun removeSessionBookFromShelf(): SessionShelfOpResult =
            SessionShelfOpResult.NotApplicable
        override fun updateViewSize(
            width: Int,
            height: Int,
            contentPadding: io.legado.app.eink.contract.ReaderEngine.ReaderContentPadding,
        ) = Unit
        override fun relayout() = Unit
        override val pageTouchSlop: Int get() = 0
        override fun headerFooterVisibility(): ReaderHeaderFooterVisibility =
            ReaderHeaderFooterVisibility(headerVisible = true, footerVisible = true)
        override fun formatTimeNow(): String = "10:00"
        override fun headerFooterTipTexts(): ReaderTipSlotTexts? = tipSlotTexts
    }
}
